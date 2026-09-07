package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.ToolExecutionFact;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;

/**
 * Coding 外部副作用账本；事务只记录证据，不在持有 JDBC 连接时操作文件或进程。
 *
 * <p>STARTED 之后的结果未知不得重放。FINISHED 结果可被相同调用读回，JOURNALED 与 Turn checkpoint 同事务推进。
 */
public final class CodingOperationRepository {
    private final H2Transactions transactions;
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建平台执行账本。
     *
     * @param database 已初始化的 data-v6
     * @param json 规范 JSON
     * @param clock 平台时钟
     */
    public CodingOperationRepository(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(database);
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 保存不可变调用身份；同身份异参、跨根或跨 Workspace 都拒绝。
     *
     * @param intent 当前真实工具调用
     * @return 新建或已有状态
     */
    public Operation prepare(Intent intent) {
        return execute(connection -> {
            Optional<Operation> existing = find(connection, intent.id());
            if (existing.isPresent()) {
                Operation operation = existing.orElseThrow();
                if (!operation.intent().equals(intent)) {
                    throw new SecurityException("Coding 幂等身份与原调用不一致");
                }
                return operation;
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO CORE.CODING_OPERATION
                    (ID,TURN_ID,WORKSPACE_ID,CALL_ID,OPERATION_NAME,REQUEST_DIGEST,EXECUTION_ROOT,
                     STATE,REQUEST_JSON,CREATED_AT,UPDATED_AT) VALUES (?,?,?,?,?,?,?,'PREPARED',?,?,?)
                    """)) {
                statement.setString(1, intent.id());
                statement.setString(2, intent.turnId().toString());
                statement.setString(3, intent.workspaceId().toString());
                statement.setString(4, intent.callId());
                statement.setString(5, intent.operation());
                statement.setString(6, intent.request().sha256());
                statement.setString(7, intent.executionRoot().toString());
                statement.setString(8, intent.request().json());
                statement.setObject(9, now());
                statement.setObject(10, now());
                statement.executeUpdate();
            }
            return new Operation(intent, "PREPARED", Optional.empty(), Optional.empty(), List.of(), false);
        });
    }

    /**
     * 写入补丁预检及备份引用；仅准备态允许更新。
     *
     * @param operationId 已绑定操作
     * @param preparation 可恢复预检证据
     */
    public void preparation(String operationId, CanonicalPayload preparation) {
        execute(connection -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE CORE.CODING_OPERATION SET PREPARATION_JSON=?, UPDATED_AT=? WHERE ID=? AND STATE='PREPARED'
                    """)) {
                statement.setString(1, preparation.json());
                statement.setObject(2, now());
                statement.setString(3, operationId);
                requireOne(statement.executeUpdate());
            }
            return null;
        });
    }

    /**
     * 在外部操作前提交不可自动重放的意图。
     *
     * @param operationId 当前操作
     */
    public void start(String operationId) {
        changeState(operationId, "PREPARED", "STARTED");
    }

    /**
     * 保存已经发生的结果；此处不伪造 Turn 的 EffectReceipt。
     *
     * @param operationId 当前操作
     * @param result Schema 已验证的结果
     * @param facts 平台收集的命令和文件事实
     * @param success 实际工具成功状态
     */
    public void finish(String operationId, CanonicalPayload result, List<ToolExecutionFact> facts, boolean success) {
        CanonicalPayload encoded = encodeFacts(facts);
        execute(connection -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE CORE.CODING_OPERATION SET STATE='FINISHED',RESULT_JSON=?,FACTS_JSON=?,UPDATED_AT=?,SUCCESS=?
                    WHERE ID=? AND STATE IN ('PREPARED','STARTED')
                    """)) {
                statement.setString(1, result.json());
                statement.setString(2, encoded.json());
                statement.setObject(3, now());
                statement.setBoolean(4, success);
                statement.setString(5, operationId);
                requireOne(statement.executeUpdate());
            }
            return null;
        });
    }

    /**
     * 将已经开始但无法确认结果的操作标记为未知。
     *
     * @param operationId 当前操作
     */
    public void unknown(String operationId) {
        changeState(operationId, "STARTED", "UNKNOWN_OUTCOME");
    }

    /**
     * 查询受 Workspace 所有权限制的持久证据。
     *
     * @param workspaceId 调用者已验证的 Workspace
     * @param operationId 资源标识，仅用于定位
     * @return 操作；不存在时为空，跨 Workspace 拒绝
     */
    public Optional<Operation> find(WorkspaceId workspaceId, String operationId) {
        return execute(connection -> find(connection, operationId).map(operation -> {
            if (!operation.intent().workspaceId().equals(workspaceId)) {
                throw new SecurityException("Coding 记录不属于当前 Workspace");
            }
            return operation;
        }));
    }

    /**
     * 与 ToolResult、EffectReceipt 和 checkpoint 共享事务的最后记账步骤。
     *
     * @param connection 当前日志事务
     * @param turnId Turn 身份
     * @param callId 工具调用身份
     * @param result 实际写入的工具结果
     * @throws SQLException 结果冲突或持久化失败
     */
    public static void markJournaled(Connection connection, TurnId turnId, String callId, CanonicalPayload result)
            throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT ID,STATE,RESULT_JSON FROM CORE.CODING_OPERATION WHERE TURN_ID=? AND CALL_ID=? FOR UPDATE
                """)) {
            query.setString(1, turnId.toString());
            query.setString(2, callId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    return;
                }
                if (!"FINISHED".equals(rows.getString(2)) || !result.json().equals(rows.getString(3))) {
                    throw new SQLException("Coding 执行结果与日志提交不一致");
                }
                try (var update = connection.prepareStatement(
                        "UPDATE CORE.CODING_OPERATION SET STATE='JOURNALED',UPDATED_AT=CURRENT_TIMESTAMP WHERE ID=?")) {
                    update.setString(1, rows.getString(1));
                    requireOne(update.executeUpdate());
                }
            }
        }
    }

    private void changeState(String id, String expected, String next) {
        execute(connection -> {
            try (var statement = connection.prepareStatement(
                    "UPDATE CORE.CODING_OPERATION SET STATE=?,UPDATED_AT=? WHERE ID=? AND STATE=?")) {
                statement.setString(1, next);
                statement.setObject(2, now());
                statement.setString(3, id);
                statement.setString(4, expected);
                requireOne(statement.executeUpdate());
            }
            return null;
        });
    }

    private Optional<Operation> find(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM CORE.CODING_OPERATION WHERE ID=? FOR UPDATE")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    private Operation read(ResultSet rows) throws SQLException {
        Intent intent = new Intent(
                rows.getString("ID"),
                TurnId.parse(rows.getString("TURN_ID")),
                WorkspaceId.parse(rows.getString("WORKSPACE_ID")),
                rows.getString("CALL_ID"),
                rows.getString("OPERATION_NAME"),
                Path.of(rows.getString("EXECUTION_ROOT")),
                new CanonicalPayload(rows.getString("REQUEST_JSON")));
        if (!intent.request().sha256().equals(rows.getString("REQUEST_DIGEST"))) {
            throw new PersistenceException("Coding 操作身份摘要校验失败");
        }
        Optional<CanonicalPayload> preparation = optionalPayload(rows.getString("PREPARATION_JSON"));
        Optional<CanonicalPayload> result = optionalPayload(rows.getString("RESULT_JSON"));
        List<ToolExecutionFact> facts = optionalPayload(rows.getString("FACTS_JSON"))
                .map(this::decodeFacts)
                .orElse(List.of());
        return new Operation(intent, rows.getString("STATE"), preparation, result, facts, rows.getBoolean("SUCCESS"));
    }

    private CanonicalPayload encodeFacts(List<ToolExecutionFact> facts) {
        return json.encode(new StoredFacts(facts.stream()
                .map(fact -> new StoredFact(fact.kind(), json.encode(fact.payload())))
                .toList()));
    }

    private List<ToolExecutionFact> decodeFacts(CanonicalPayload payload) {
        return json.decode(payload, StoredFacts.class).facts().stream()
                .map(fact -> switch (fact.kind()) {
                    case "command" -> new ToolExecutionFact(json.decode(fact.payload(), CorePayloads.Command.class));
                    case "file-change" ->
                        new ToolExecutionFact(json.decode(fact.payload(), CorePayloads.FileChange.class));
                    default -> throw new PersistenceException("未知 Coding 平台事实");
                })
                .toList();
    }

    private Optional<CanonicalPayload> optionalPayload(String value) {
        return Optional.ofNullable(value).map(CanonicalPayload::new);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static void requireOne(int count) {
        if (count != 1) {
            throw new PersistenceException("Coding 状态转换冲突");
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Coding 账本事务失败", failure);
        }
    }

    /**
     * 服务端产生的操作身份；所有字段非空。
     *
     * @param id 稳定操作 ID
     * @param turnId 权威 Turn
     * @param workspaceId 权威 Workspace
     * @param callId 原始工具调用 ID
     * @param operation 固定操作名
     * @param executionRoot 已冻结绝对根
     * @param request 含原参数和冻结环境摘要的身份正文
     */
    public record Intent(
            String id,
            TurnId turnId,
            WorkspaceId workspaceId,
            String callId,
            String operation,
            Path executionRoot,
            CanonicalPayload request) {
        /** 校验身份非空且根规范。 */
        public Intent {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(operation, "operation");
            executionRoot = executionRoot.toAbsolutePath().normalize();
            Objects.requireNonNull(request, "request");
        }
    }

    /**
     * 持久化执行快照。
     *
     * @param intent 原操作身份
     * @param state 持久状态
     * @param preparation 可选预检和备份证据
     * @param result 可选已完成结果
     * @param facts 已收集的平台事实
     * @param success 已完成调用的真实成功状态；未完成时为 false
     */
    public record Operation(
            Intent intent,
            String state,
            Optional<CanonicalPayload> preparation,
            Optional<CanonicalPayload> result,
            List<ToolExecutionFact> facts,
            boolean success) {
        /** 防止调用方修改事实列表。 */
        public Operation {
            facts = List.copyOf(facts);
        }
    }

    private record StoredFact(String kind, CanonicalPayload payload) {}

    private record StoredFacts(List<StoredFact> facts) {}
}
