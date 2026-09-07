package com.javaclaw.server.persistence;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;

/**
 * batch 输出的追加账本；CAS 正文先于索引提交，不持有 JDBC 连接写 Blob。
 *
 * <p>两个通道共享提交顺序的字节游标，后续 stdout 不能改变已读 stderr 的位置。操作及 Turn 结束后禁止追加。
 */
public final class CodingCommandStreamRepository {
    private final H2Transactions transactions;
    private final AttachmentService attachments;
    private final CanonicalJson json;

    /**
     * 绑定同一个 data-v6 数据库和附件服务。
     *
     * @param database 服务端数据库
     * @param attachments 按 Workspace 隔离的 CAS
     * @param json 规范 JSON
     */
    public CodingCommandStreamRepository(H2Database database, AttachmentService attachments, CanonicalJson json) {
        transactions = new H2Transactions(database);
        this.attachments = attachments;
        this.json = json;
    }

    /**
     * 在外部启动之前创建唯一输出流；已有流不允许重新开始。
     *
     * @param workspaceId 权威 Workspace
     * @param turnId 权威 Turn
     * @param operationId 已准备的命令或依赖操作
     * @param maximumBytes 冻结输出上限，1 到 1 MiB
     * @return 初始字节游标为零的所有权快照
     */
    public Snapshot create(WorkspaceId workspaceId, TurnId turnId, String operationId, long maximumBytes) {
        if (maximumBytes < 1 || maximumBytes > 1_048_576) {
            throw new IllegalArgumentException("命令输出上限越界");
        }
        var owner = new Snapshot(operationId, turnId, workspaceId, 0, maximumBytes, "RUNNING", Optional.empty());
        return execute(connection -> {
            requireOperation(connection, owner, "PREPARED", true);
            try (var insert = connection.prepareStatement("""
                INSERT INTO CORE.CODING_COMMAND_STREAM
                (OPERATION_ID,TURN_ID,WORKSPACE_ID,MAXIMUM_BYTES,UPDATED_AT)
                VALUES (?,?,?,?,CURRENT_TIMESTAMP)
                """)) {
                insert.setString(1, operationId);
                insert.setString(2, turnId.toString());
                insert.setString(3, workspaceId.toString());
                insert.setLong(4, maximumBytes);
                insert.executeUpdate();
            }
            return owner;
        });
    }

    /**
     * 追加单个已保留帧；调用方串行推进快照，过期游标不覆盖既有证据。
     *
     * @param owner 最近提交的所有权和尾部快照
     * @param channel stdout 或 stderr
     * @param bytes 非空原始字节，最多 64 KiB
     * @return 提交后的快照
     */
    public Snapshot append(Snapshot owner, String channel, byte[] bytes) {
        if (!Set.of("stdout", "stderr").contains(channel) || bytes.length == 0 || bytes.length > 65_536) {
            throw new IllegalArgumentException("命令输出帧通道或大小无效");
        }
        byte[] content = bytes.clone();
        execute(connection -> writable(connection, owner, content.length, true));
        var identity = json.encode(new ChunkIdentity(owner.operationId(), owner.outputBytes(), channel, content));
        String digest = attachments
                .store(
                        AttachmentScope.workspace(owner.workspaceId()),
                        new CommandIdentity(
                                "coding/command-chunk", "command-chunk-" + identity.sha256(), 0, identity.sha256()),
                        "application/octet-stream",
                        content)
                .digest();
        return execute(connection -> {
            Snapshot current = writable(connection, owner, content.length, true);
            try (var insert = connection.prepareStatement("""
                INSERT INTO CORE.CODING_COMMAND_CHUNK
                (OPERATION_ID,OFFSET_BYTES,CHANNEL,CONTENT_DIGEST,CONTENT_LENGTH) VALUES (?,?,?,?,?)
                """)) {
                insert.setString(1, owner.operationId());
                insert.setLong(2, current.outputBytes());
                insert.setString(3, channel);
                insert.setString(4, digest);
                insert.setInt(5, content.length);
                insert.executeUpdate();
            }
            long next = current.outputBytes() + content.length;
            try (var update = connection.prepareStatement("""
                UPDATE CORE.CODING_COMMAND_STREAM SET OUTPUT_BYTES=?,UPDATED_AT=CURRENT_TIMESTAMP
                WHERE OPERATION_ID=?
                """)) {
                update.setLong(1, next);
                update.setString(2, owner.operationId());
                update.executeUpdate();
            }
            return new Snapshot(
                    owner.operationId(),
                    owner.turnId(),
                    owner.workspaceId(),
                    next,
                    current.maximumBytes(),
                    current.state(),
                    current.exitCode());
        });
    }

    /**
     * 记录已观察的原生退出；允许 Turn 先结束后的可信收尾，不替代最终工具记账事务。
     *
     * <p>operation 必须仍为 STARTED；已 UNKNOWN 或已封闭的输出流不能被成功回执覆盖。
     *
     * @param owner 最后已提交输出快照
     * @param state COMPLETED、FAILED、CANCELLED 或 TIMED_OUT
     * @param exitCode 原生实际退出码
     */
    public void complete(Snapshot owner, String state, int exitCode) {
        if (!Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT").contains(state)) {
            throw new IllegalArgumentException("命令输出终态无效");
        }
        execute(connection -> {
            writable(connection, owner, 0, false);
            try (var update = connection.prepareStatement("""
                UPDATE CORE.CODING_COMMAND_STREAM SET STATE=?,EXIT_CODE=?,UPDATED_AT=CURRENT_TIMESTAMP
                WHERE OPERATION_ID=?
                """)) {
                update.setString(1, state);
                update.setInt(2, exitCode);
                update.setString(3, owner.operationId());
                update.executeUpdate();
            }
            return null;
        });
    }

    /**
     * 查询流存在性；缺失表示尚未启动或历史版本未创建流，跨归属仍拒绝。
     *
     * @param workspaceId 当前 Workspace
     * @param turnId 已经校验的 Turn
     * @param operationId 服务端操作标识
     * @return 已提交输出快照
     */
    public Optional<Snapshot> find(WorkspaceId workspaceId, TurnId turnId, String operationId) {
        return execute(connection -> {
            Optional<Snapshot> found = read(connection, operationId, false);
            found.ifPresent(value -> requireOwner(value, workspaceId, turnId));
            return found;
        });
    }

    /**
     * 按快照尾部读取一个有界页，正文在事务之外验证 CAS 归属和长度。
     *
     * @param owner 已验证的快照
     * @param offset 原始总字节游标
     * @param maximumBytes 页大小，1 到 1 MiB
     * @return 两通道原始字节和稳定的下一游标
     */
    public Page page(Snapshot owner, long offset, int maximumBytes) {
        requirePageLimits(owner, offset, maximumBytes);
        int retained = (int) Math.min(maximumBytes, owner.outputBytes() - offset);
        List<Chunk> chunks = execute(connection -> {
            Snapshot current = read(connection, owner.operationId(), false).orElseThrow();
            requireOwner(current, owner.workspaceId(), owner.turnId());
            if (owner.outputBytes() > current.outputBytes() || owner.maximumBytes() != current.maximumBytes()) {
                throw new SecurityException("命令输出快照超过持久范围");
            }
            return retained == 0 ? List.of() : chunks(connection, owner.operationId(), offset, retained);
        });
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int read = 0;
        for (Chunk chunk : chunks) {
            byte[] content = attachments
                    .read(AttachmentScope.workspace(owner.workspaceId()), chunk.digest())
                    .content();
            int start = (int) Math.max(0, offset - chunk.offset());
            if (content.length != chunk.length() || chunk.offset() + start != offset + read) {
                throw new PersistenceException("命令输出 Blob 长度或游标不连续");
            }
            int count = Math.min(content.length - start, retained - read);
            (chunk.channel().equals("stdout") ? stdout : stderr).write(content, start, count);
            read += count;
        }
        if (read != retained) {
            throw new PersistenceException("命令输出索引缺失");
        }
        long next = offset + read;
        return new Page(
                stdout.toByteArray(),
                stderr.toByteArray(),
                next,
                next < owner.outputBytes() || owner.outputBytes() == owner.maximumBytes());
    }

    private static void requirePageLimits(Snapshot owner, long offset, int maximumBytes) {
        if (offset < 0 || offset > owner.outputBytes() || maximumBytes < 1 || maximumBytes > 1_048_576) {
            throw new IllegalArgumentException("命令输出页游标或大小无效");
        }
    }

    /**
     * 读取指定通道在游标之前的最多三个字节，供 UTF-8 跨页解码；不改变页的原始字节游标。
     *
     * @param owner 已验证的输出快照
     * @param offset 页起始总字节游标
     * @param channel stdout 或 stderr
     * @return 按通道顺序排列的有界前缀，可能为空
     */
    public byte[] prefix(Snapshot owner, long offset, String channel) {
        requirePageLimits(owner, offset, 1);
        if (!Set.of("stdout", "stderr").contains(channel)) {
            throw new IllegalArgumentException("命令输出前缀通道无效");
        }
        List<Chunk> chunks = execute(connection -> {
            Snapshot current = read(connection, owner.operationId(), false).orElseThrow();
            requireOwner(current, owner.workspaceId(), owner.turnId());
            if (owner.outputBytes() > current.outputBytes()) {
                throw new SecurityException("命令输出快照超过持久范围");
            }
            return previousChunks(connection, owner.operationId(), offset, channel);
        });
        byte[] prefix = new byte[0];
        for (Chunk chunk : chunks) {
            byte[] content = attachments
                    .read(AttachmentScope.workspace(owner.workspaceId()), chunk.digest())
                    .content();
            if (content.length != chunk.length()) {
                throw new PersistenceException("命令输出前缀 Blob 长度不一致");
            }
            int end = (int) Math.min(content.length, offset - chunk.offset());
            int count = Math.min(end, 3 - prefix.length);
            byte[] joined = new byte[prefix.length + count];
            System.arraycopy(content, end - count, joined, 0, count);
            System.arraycopy(prefix, 0, joined, count, prefix.length);
            prefix = joined;
            if (prefix.length == 3) {
                break;
            }
        }
        return prefix;
    }

    private static List<Chunk> previousChunks(Connection connection, String id, long offset, String channel)
            throws SQLException {
        List<Chunk> chunks = new ArrayList<>();
        try (var query = connection.prepareStatement("""
            SELECT OFFSET_BYTES,CONTENT_DIGEST,CONTENT_LENGTH FROM CORE.CODING_COMMAND_CHUNK
            WHERE OPERATION_ID=? AND CHANNEL=? AND OFFSET_BYTES<? ORDER BY OFFSET_BYTES DESC FETCH FIRST 3 ROWS ONLY
            """)) {
            query.setString(1, id);
            query.setString(2, channel);
            query.setLong(3, offset);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    chunks.add(new Chunk(rows.getLong(1), channel, rows.getString(2), rows.getInt(3)));
                }
            }
        }
        return chunks;
    }

    private Snapshot writable(Connection connection, Snapshot owner, int bytes, boolean activeTurn)
            throws SQLException {
        requireOperation(connection, owner, "STARTED", activeTurn);
        Snapshot current =
                read(connection, owner.operationId(), true).orElseThrow(() -> new SecurityException("命令输出流不存在"));
        requireOwner(current, owner.workspaceId(), owner.turnId());
        if (!current.state().equals("RUNNING")
                || current.outputBytes() != owner.outputBytes()
                || current.maximumBytes() != owner.maximumBytes()
                || bytes > current.maximumBytes() - current.outputBytes()) {
            throw new PersistenceException("命令输出状态、游标或保留预算冲突");
        }
        return current;
    }

    private static void requireOperation(Connection connection, Snapshot owner, String expected, boolean activeTurn)
            throws SQLException {
        // 与 Turn 终态使用同一行锁，且先锁 Turn 再锁 operation，避免尾部输出越过资源终结边界。
        try (var query = connection.prepareStatement("SELECT STATUS FROM CORE.AGENT_TURN WHERE ID=? FOR UPDATE")) {
            query.setString(1, owner.turnId().toString());
            try (var rows = query.executeQuery()) {
                if (!rows.next() || activeTurn && !Set.of("RUNNING", "WAITING").contains(rows.getString(1))) {
                    throw new SecurityException("Turn 已结束，不能追加命令输出");
                }
            }
        }
        try (var query = connection.prepareStatement("""
            SELECT TURN_ID,WORKSPACE_ID,STATE,OPERATION_NAME FROM CORE.CODING_OPERATION WHERE ID=? FOR UPDATE
            """)) {
            query.setString(1, owner.operationId());
            try (var rows = query.executeQuery()) {
                if (!rows.next()
                        || !rows.getString(1).equals(owner.turnId().toString())
                        || !rows.getString(2).equals(owner.workspaceId().toString())
                        || !rows.getString(3).equals(expected)
                        || !Set.of("command_run", "dependencies_prepare").contains(rows.getString(4))) {
                    throw new SecurityException("命令输出归属、种类或执行阶段不匹配");
                }
            }
        }
    }

    private static void requireOwner(Snapshot owner, WorkspaceId workspace, TurnId turn) {
        if (!owner.workspaceId().equals(workspace) || !owner.turnId().equals(turn)) {
            throw new SecurityException("命令输出不属于当前 Workspace 或 Turn");
        }
    }

    private static Optional<Snapshot> read(Connection connection, String id, boolean lock) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT * FROM CORE.CODING_COMMAND_STREAM WHERE OPERATION_ID=?" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, id);
            try (var rows = query.executeQuery()) {
                return rows.next()
                        ? Optional.of(new Snapshot(
                                id,
                                TurnId.parse(rows.getString("TURN_ID")),
                                WorkspaceId.parse(rows.getString("WORKSPACE_ID")),
                                rows.getLong("OUTPUT_BYTES"),
                                rows.getLong("MAXIMUM_BYTES"),
                                rows.getString("STATE"),
                                Optional.ofNullable(rows.getObject("EXIT_CODE", Integer.class))))
                        : Optional.empty();
            }
        }
    }

    private static List<Chunk> chunks(Connection connection, String id, long offset, int maximum) throws SQLException {
        List<Chunk> chunks = new ArrayList<>();
        try (var query = connection.prepareStatement("""
            SELECT OFFSET_BYTES,CHANNEL,CONTENT_DIGEST,CONTENT_LENGTH FROM CORE.CODING_COMMAND_CHUNK
            WHERE OPERATION_ID=? AND OFFSET_BYTES+CONTENT_LENGTH>? AND OFFSET_BYTES<? ORDER BY OFFSET_BYTES
            """)) {
            query.setString(1, id);
            query.setLong(2, offset);
            query.setLong(3, offset + maximum);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    chunks.add(new Chunk(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getInt(4)));
                }
            }
        }
        return List.copyOf(chunks);
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return transactions.execute(work);
            } catch (SQLException conflict) {
                if (!"40001".equals(conflict.getSQLState()) || attempt == 2) {
                    throw new PersistenceException("命令输出事务失败", conflict);
                }
                // 仅重试已回滚 SQL；正文由确定身份的 CAS 存储一次，不重启进程。
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new PersistenceException("命令输出事务失败", failure);
            }
        }
        throw new IllegalStateException("命令输出事务重试状态无效");
    }

    /**
     * 不含原生进程身份的输出快照。
     *
     * @param operationId 操作标识
     * @param turnId 唯一所属 Turn
     * @param workspaceId 唯一所属 Workspace
     * @param outputBytes 已保留尾部，字节
     * @param maximumBytes 冻结保留上限，字节
     * @param state 输出流观察状态
     * @param exitCode 已观察退出码，运行时为空
     */
    public record Snapshot(
            String operationId,
            TurnId turnId,
            WorkspaceId workspaceId,
            long outputBytes,
            long maximumBytes,
            String state,
            Optional<Integer> exitCode) {}

    /**
     * 同一总字节页的两通道内容。
     *
     * @param stdout 页内标准输出原始字节
     * @param stderr 页内标准错误原始字节
     * @param nextOffsetBytes 下一总字节游标
     * @param truncated 仍有已保留未读内容或已经达到冻结保留上限
     */
    public record Page(byte[] stdout, byte[] stderr, long nextOffsetBytes, boolean truncated) {
        /** 复制两个输出通道，防止调用方修改证据。 */
        public Page {
            stdout = stdout.clone();
            stderr = stderr.clone();
        }

        @Override
        public byte[] stdout() {
            return stdout.clone();
        }

        @Override
        public byte[] stderr() {
            return stderr.clone();
        }
    }

    private record Chunk(long offset, String channel, String digest, int length) {}

    private record ChunkIdentity(String id, long offset, String channel, byte[] content) {}
}
