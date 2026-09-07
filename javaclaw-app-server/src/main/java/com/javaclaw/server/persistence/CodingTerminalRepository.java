package com.javaclaw.server.persistence;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;

/** PTY 的持久输出、输入意图及终态；裸 PID 与待发送 stdin 永不持久化。 */
public final class CodingTerminalRepository {
    private final H2Transactions transactions;
    private final AttachmentService attachments;
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建 PTY 证据服务。
     *
     * @param database data-v6 数据库
     * @param attachments 内容寻址 Blob 服务
     * @param json 规范 JSON
     * @param clock 平台时钟
     */
    public CodingTerminalRepository(
            H2Database database, AttachmentService attachments, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(database);
        this.attachments = attachments;
        this.json = json;
        this.clock = clock;
    }

    /**
     * 在启动 PTY 前创建记录；进程尚未确认时保留 STARTING。
     *
     * @param operation 当前已持久化操作
     * @param command 平台解析的真实命令
     */
    public void create(CodingOperationRepository.Intent operation, CorePayloads.Command command) {
        execute(connection -> {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO CORE.CODING_TERMINAL
                    (ID,OPERATION_ID,TURN_ID,WORKSPACE_ID,STATE,COMMAND_JSON,CREATED_AT,UPDATED_AT)
                    VALUES (?,?,?,?,'STARTING',?,?,?)
                    """)) {
                insert.setString(1, operation.id());
                insert.setString(2, operation.id());
                insert.setString(3, operation.turnId().toString());
                insert.setString(4, operation.workspaceId().toString());
                insert.setString(5, json.encode(command).json());
                insert.setObject(6, now());
                insert.setObject(7, now());
                insert.executeUpdate();
            }
            return null;
        });
    }

    /**
     * 记录运行或真实终态；终态冲突必须显式失败。
     *
     * @param id 平台 session ID
     * @param state RUNNING、COMPLETED、FAILED、CANCELLED、TIMED_OUT 或 UNKNOWN_OUTCOME
     * @param exitCode 已观察的退出码，未知时为空
     */
    public void state(String id, String state, Optional<Integer> exitCode) {
        Objects.requireNonNull(exitCode, "exitCode");
        if (!List.of("RUNNING", "COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT", "UNKNOWN_OUTCOME")
                        .contains(state)
                || "RUNNING".equals(state) && exitCode.isPresent()) {
            throw new IllegalArgumentException("PTY 状态或运行中退出码无效");
        }
        execute(connection -> {
            Snapshot before = require(connection, id);
            if (!List.of("STARTING", "RUNNING").contains(before.state())) {
                if (!before.state().equals(state) || !before.exitCode().equals(exitCode)) {
                    throw new PersistenceException("PTY 终态冲突");
                }
                return null;
            }
            try (var update = connection.prepareStatement(
                    "UPDATE CORE.CODING_TERMINAL SET STATE=?,EXIT_CODE=?,UPDATED_AT=? WHERE ID=?")) {
                update.setString(1, state);
                update.setObject(2, exitCode.orElse(null));
                update.setObject(3, now());
                update.setString(4, id);
                update.executeUpdate();
            }
            return null;
        });
    }

    /**
     * 顺序追加有界输出，Blob 先于索引提交；失败不得在相同位置覆盖不同数据。
     *
     * @param owner 真实归属
     * @param bytes 不超过 64 KiB 的输出块
     * @param maximumBytes 本次会话冻结保留上限
     * @return 已保留的新尾部游标
     */
    public long append(Snapshot owner, byte[] bytes, long maximumBytes) {
        if (bytes.length == 0 || bytes.length > 64 * 1024 || maximumBytes < 1) {
            throw new IllegalArgumentException("PTY 输出块大小越界");
        }
        execute(connection -> requireOwner(connection, owner, false));
        CanonicalPayload key = json.encode(new ChunkIdentity(owner.id(), owner.outputBytes(), bytes));
        var metadata = attachments.store(
                AttachmentScope.workspace(owner.workspaceId()),
                new CommandIdentity("coding/terminal-output", "terminal-output-" + key.sha256(), 0, key.sha256()),
                "application/octet-stream",
                bytes);
        return execute(connection -> {
            Snapshot current = requireOwner(connection, owner, true);
            if (current.outputBytes() != owner.outputBytes()
                    || current.outputBytes() > maximumBytes
                    || bytes.length > maximumBytes - current.outputBytes()) {
                throw new PersistenceException("PTY 输出游标或保留预算冲突");
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO CORE.CODING_TERMINAL_OUTPUT
                    (SESSION_ID,OFFSET_BYTES,CONTENT_DIGEST,CONTENT_LENGTH) VALUES (?,?,?,?)
                    """)) {
                insert.setString(1, owner.id());
                insert.setLong(2, current.outputBytes());
                insert.setString(3, metadata.digest());
                insert.setInt(4, bytes.length);
                insert.executeUpdate();
            }
            long next = current.outputBytes() + bytes.length;
            try (var update = connection.prepareStatement(
                    "UPDATE CORE.CODING_TERMINAL SET OUTPUT_BYTES=?,UPDATED_AT=? WHERE ID=?")) {
                update.setLong(1, next);
                update.setObject(2, now());
                update.setString(3, owner.id());
                update.executeUpdate();
            }
            return next;
        });
    }

    /**
     * 查询权威归属；资源 ID 本身不携带授权。
     *
     * @param workspaceId 已验证 Workspace
     * @param id 资源标识
     * @return 持久快照
     */
    public Snapshot read(WorkspaceId workspaceId, String id) {
        return execute(connection -> {
            Snapshot result = require(connection, id, false);
            if (!result.workspaceId().equals(workspaceId)) {
                throw new SecurityException("PTY 不属于当前 Workspace");
            }
            return result;
        });
    }

    /**
     * 按原始字节游标读取 Blob 输出；最多 1 MiB，不以字符数改变游标。
     *
     * @param owner 已验证归属
     * @param offset 起始字节
     * @param maximumBytes 返回上限
     * @return 不超过该快照尾部的有界原始字节；新追加内容由下一次快照读取
     */
    public byte[] output(Snapshot owner, long offset, int maximumBytes) {
        if (offset < 0 || maximumBytes < 1 || maximumBytes > 1024 * 1024 || offset > owner.outputBytes()) {
            throw new IllegalArgumentException("PTY 输出页游标或大小越界");
        }
        int retained = (int) Math.min(maximumBytes, owner.outputBytes() - offset);
        List<Chunk> chunks = execute(connection -> {
            Snapshot current = requireOwner(connection, owner, false);
            if (owner.outputBytes() > current.outputBytes()) {
                throw new SecurityException("PTY 快照游标超出持久输出");
            }
            return retained == 0 ? List.of() : chunks(connection, owner.id(), offset, retained);
        });
        ByteArrayOutputStream output = new ByteArrayOutputStream(retained);
        for (Chunk chunk : chunks) {
            byte[] content = attachments
                    .read(AttachmentScope.workspace(owner.workspaceId()), chunk.digest())
                    .content();
            if (content.length != chunk.length()) {
                throw new PersistenceException("PTY Blob 长度不一致");
            }
            int start = (int) Math.max(0, offset - chunk.offset());
            if (chunk.offset() + start != offset + output.size()) {
                throw new PersistenceException("PTY 输出索引不连续");
            }
            int count = Math.min(content.length - start, retained - output.size());
            output.write(content, start, count);
        }
        if (output.size() != retained) {
            throw new PersistenceException("PTY 输出索引缺失");
        }
        return output.toByteArray();
    }

    /**
     * 提交 stdin 的下一序号意图，只存摘要；同序号已完成可重读，结果未知不可再次发送。
     *
     * @param owner 当前 Turn 已验证会话
     * @param sequence 连续输入序号，从 1 开始
     * @param digest 输入摘要
     * @return 首次意图为 true，已确认完成的同参请求为 false
     */
    public boolean inputIntent(Snapshot owner, long sequence, String digest) {
        requireInputIdentity(sequence, digest);
        return execute(connection -> {
            requireOwner(connection, owner, true);
            try (var query = connection.prepareStatement(
                    "SELECT INPUT_SEQUENCE,INPUT_DIGEST,INPUT_STATE FROM CORE.CODING_TERMINAL WHERE ID=?")) {
                query.setString(1, owner.id());
                try (var rows = query.executeQuery()) {
                    rows.next();
                    long previous = rows.getLong(1);
                    if (sequence == previous
                            && digest.equals(rows.getString(2))
                            && "DELIVERED".equals(rows.getString(3))) {
                        return false;
                    }
                    if (sequence != previous + 1 || "INTENT".equals(rows.getString(3))) {
                        throw new SecurityException("PTY 输入序号冲突或上次送达结果未知");
                    }
                }
            }
            try (var update = connection.prepareStatement("""
                    UPDATE CORE.CODING_TERMINAL SET INPUT_SEQUENCE=?,INPUT_DIGEST=?,INPUT_STATE='INTENT',UPDATED_AT=?
                    WHERE ID=? AND STATE='RUNNING'
                    """)) {
                update.setLong(1, sequence);
                update.setString(2, digest);
                update.setObject(3, now());
                update.setString(4, owner.id());
                if (update.executeUpdate() != 1) {
                    throw new SecurityException("PTY 已结束");
                }
            }
            return true;
        });
    }

    /**
     * 确认一条已发送输入；持久失败后不得重发。
     *
     * @param id 会话
     * @param sequence 已发送序号
     * @param digest 已发送正文摘要
     */
    public void inputDelivered(String id, long sequence, String digest) {
        requireInputIdentity(sequence, digest);
        execute(connection -> {
            try (var update = connection.prepareStatement("""
                    UPDATE CORE.CODING_TERMINAL SET INPUT_STATE='DELIVERED',UPDATED_AT=?
                    WHERE ID=? AND INPUT_SEQUENCE=? AND INPUT_DIGEST=? AND INPUT_STATE='INTENT'
                    """)) {
                update.setObject(1, now());
                update.setString(2, id);
                update.setLong(3, sequence);
                update.setString(4, digest);
                if (update.executeUpdate() != 1) {
                    throw new PersistenceException("PTY 输入回执冲突");
                }
            }
            return null;
        });
    }

    /** 启动恢复只标记结果未知，绝不恢复 PID、重发 stdin 或重启命令。 */
    public void recoverInterrupted() {
        execute(connection -> {
            try (var update = connection.prepareStatement("""
                    UPDATE CORE.CODING_TERMINAL SET STATE='UNKNOWN_OUTCOME',UPDATED_AT=CURRENT_TIMESTAMP
                    WHERE STATE IN ('STARTING','RUNNING')
                    """)) {
                update.executeUpdate();
            }
            try (var update = connection.prepareStatement("""
                    UPDATE CORE.CODING_OPERATION SET STATE='UNKNOWN_OUTCOME',UPDATED_AT=CURRENT_TIMESTAMP
                    WHERE STATE='STARTED'
                    """)) {
                update.executeUpdate();
            }
            return null;
        });
    }

    static List<CorePayloads.Command> drainFinalFacts(Connection connection, TurnId turnId, CanonicalJson json)
            throws SQLException {
        List<CorePayloads.Command> facts = new ArrayList<>();
        try (var query = connection.prepareStatement("""
                SELECT ID,COMMAND_JSON,EXIT_CODE FROM CORE.CODING_TERMINAL WHERE TURN_ID=?
                AND STATE NOT IN ('STARTING','RUNNING') AND FINAL_FACT_RECORDED=FALSE FOR UPDATE
                """)) {
            query.setString(1, turnId.toString());
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    var command = json.decode(new CanonicalPayload(rows.getString(2)), CorePayloads.Command.class);
                    facts.add(new CorePayloads.Command(
                            command.commandId(),
                            command.argv(),
                            command.workingDirectory(),
                            Optional.ofNullable(rows.getObject(3, Integer.class))));
                    try (var update = connection.prepareStatement(
                            "UPDATE CORE.CODING_TERMINAL SET FINAL_FACT_RECORDED=TRUE WHERE ID=?")) {
                        update.setString(1, rows.getString(1));
                        update.executeUpdate();
                    }
                }
            }
        }
        return List.copyOf(facts);
    }

    private static List<Chunk> chunks(Connection connection, String id, long offset, int maximum) throws SQLException {
        List<Chunk> chunks = new ArrayList<>();
        try (var query = connection.prepareStatement("""
                SELECT OFFSET_BYTES,CONTENT_DIGEST,CONTENT_LENGTH FROM CORE.CODING_TERMINAL_OUTPUT
                WHERE SESSION_ID=? AND OFFSET_BYTES+CONTENT_LENGTH>? AND OFFSET_BYTES<? ORDER BY OFFSET_BYTES
                """)) {
            query.setString(1, id);
            query.setLong(2, offset);
            query.setLong(3, offset > Long.MAX_VALUE - maximum ? Long.MAX_VALUE : offset + maximum);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    chunks.add(new Chunk(rows.getLong(1), rows.getString(2), rows.getInt(3)));
                }
            }
        }
        return chunks;
    }

    private Snapshot require(Connection connection, String id) throws SQLException {
        return require(connection, id, true);
    }

    private Snapshot requireOwner(Connection connection, Snapshot owner, boolean lock) throws SQLException {
        Snapshot current = require(connection, owner.id(), lock);
        if (!current.workspaceId().equals(owner.workspaceId())
                || !current.turnId().equals(owner.turnId())) {
            throw new SecurityException("PTY 快照不属于当前 Workspace 或 Turn");
        }
        return current;
    }

    private static void requireInputIdentity(long sequence, String digest) {
        if (sequence < 1 || digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("PTY 输入需要正序号及 SHA-256 摘要");
        }
    }

    private Snapshot require(Connection connection, String id, boolean lock) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT * FROM CORE.CODING_TERMINAL WHERE ID=?" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, id);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    throw new SecurityException("PTY 记录不存在");
                }
                return new Snapshot(
                        id,
                        TurnId.parse(row.getString("TURN_ID")),
                        WorkspaceId.parse(row.getString("WORKSPACE_ID")),
                        row.getString("STATE"),
                        Optional.ofNullable(row.getObject("EXIT_CODE", Integer.class)),
                        row.getLong("OUTPUT_BYTES"));
            }
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return transactions.execute(work);
            } catch (SQLException conflict) {
                if (!"40001".equals(conflict.getSQLState()) || attempt == 2) {
                    throw new PersistenceException("PTY 证据事务失败", conflict);
                }
                // 仅重试已回滚的纯 SQL 事务；不再次发送 stdin、启动进程或写入项目文件。
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new PersistenceException("PTY 证据事务失败", failure);
            }
        }
        throw new IllegalStateException("PTY 事务重试状态无效");
    }

    /**
     * 不含原生句柄的会话快照。
     *
     * @param id 会话标识
     * @param turnId 唯一 Turn
     * @param workspaceId 唯一 Workspace
     * @param state 持久状态，包括 UNKNOWN_OUTCOME
     * @param exitCode 实际退出码，未观察时为空
     * @param outputBytes 已保留输出尾部，单位字节
     */
    public record Snapshot(
            String id,
            TurnId turnId,
            WorkspaceId workspaceId,
            String state,
            Optional<Integer> exitCode,
            long outputBytes) {}

    private record Chunk(long offset, String digest, int length) {}

    private record ChunkIdentity(String sessionId, long offset, byte[] content) {}
}
