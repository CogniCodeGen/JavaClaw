package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.PromptOptimizationId;
import com.javaclaw.api.PromptOptimizationRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;

/** Prompt 优化任务最小关联、启动幂等和人工采纳标记的 H2 Repository。 */
public final class PromptOptimizationRepository {
    private final H2Transactions transactions;
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建 Repository。
     *
     * @param database data-v5 数据库
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public PromptOptimizationRepository(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 在创建 Thread 前检查启动命令是否已完成。
     *
     * @param identity 启动命令身份
     * @return 已持久化关联
     */
    public Optional<PromptOptimizationRecord> recoverStart(CommandIdentity identity) {
        CommandIdentity checked = requireStartIdentity(identity);
        return execute(connection ->
                idempotency.find(connection, checked.idempotencyKey()).map(stored -> recover(checked, stored)));
    }

    /**
     * 同事务保存任务关联和启动命令结果。
     *
     * @param identity 启动命令身份
     * @param record 已创建的 Thread/Turn 关联
     * @return 首次关联或并发重试已保存的关联
     */
    public PromptOptimizationRecord recordStart(CommandIdentity identity, PromptOptimizationRecord record) {
        CommandIdentity checked = requireStartIdentity(identity);
        PromptOptimizationRecord candidate = Objects.requireNonNull(record, "record");
        synchronized (CommandLocks.forKey(checked.idempotencyKey())) {
            return execute(connection -> {
                Optional<IdempotencyRepository.StoredCommand> stored =
                        idempotency.find(connection, checked.idempotencyKey());
                if (stored.isPresent()) {
                    return recover(checked, stored.orElseThrow());
                }
                insert(connection, candidate);
                idempotency.insert(connection, checked, json.encode(candidate), Instant.now(clock));
                return candidate;
            });
        }
    }

    /**
     * 读取任务关联。
     *
     * @param id 优化任务标识
     * @return 持久关联
     */
    public PromptOptimizationRecord require(PromptOptimizationId id) {
        return execute(connection -> find(connection, Objects.requireNonNull(id, "id"), false)
                .orElseThrow(() -> PersistenceException.invalidRequest("Prompt 优化任务不存在")));
    }

    /**
     * 列出 Workspace 的任务关联。
     *
     * @param workspaceId Workspace
     * @return 创建时间倒序的不可变列表
     */
    public List<PromptOptimizationRecord> list(WorkspaceId workspaceId) {
        WorkspaceId checked = Objects.requireNonNull(workspaceId, "workspaceId");
        return execute(connection -> list(connection, checked));
    }

    /**
     * 查找尚未进入 Harness 的 QUEUED 任务，供 App Server 启动恢复。
     *
     * @return 创建时间顺序的关联
     */
    public List<PromptOptimizationRecord> listQueued() {
        return execute(this::listQueued);
    }

    /**
     * 幂等记录人工采纳产生的新 Profile revision。
     *
     * @param id 优化任务标识
     * @param adoptedProfileRevision 新 Profile revision
     * @return 更新后的关联
     */
    public PromptOptimizationRecord markAdopted(PromptOptimizationId id, long adoptedProfileRevision) {
        if (adoptedProfileRevision < 1) {
            throw new IllegalArgumentException("adoptedProfileRevision must be positive");
        }
        synchronized (CommandLocks.forKey("prompt-optimization:" + id)) {
            return execute(connection -> markAdopted(connection, id, adoptedProfileRevision));
        }
    }

    private PromptOptimizationRecord markAdopted(
            Connection connection, PromptOptimizationId id, long adoptedProfileRevision) throws SQLException {
        PromptOptimizationRecord current = find(connection, Objects.requireNonNull(id, "id"), true)
                .orElseThrow(() -> PersistenceException.invalidRequest("Prompt 优化任务不存在"));
        if (current.adoptedProfileRevision().isPresent()) {
            long existing = current.adoptedProfileRevision().orElseThrow();
            if (existing != adoptedProfileRevision) {
                throw PersistenceException.revisionConflict("Prompt 草稿已采纳为另一个 Profile revision");
            }
            return current;
        }
        Instant adoptedAt = Instant.now(clock);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.PROMPT_OPTIMIZATION
                SET ADOPTED_PROFILE_REVISION = ?, ADOPTED_AT = ?
                WHERE ID = ? AND ADOPTED_PROFILE_REVISION IS NULL
                """)) {
            statement.setLong(1, adoptedProfileRevision);
            statement.setObject(2, adoptedAt.atOffset(ZoneOffset.UTC));
            statement.setString(3, id.toString());
            if (statement.executeUpdate() != 1) {
                throw PersistenceException.revisionConflict("Prompt 草稿采纳状态已改变");
            }
        }
        return new PromptOptimizationRecord(
                current.ref(),
                current.instructionRevision(),
                current.instructionDigest(),
                Optional.of(adoptedProfileRevision),
                Optional.of(adoptedAt),
                current.createdAt());
    }

    private void insert(Connection connection, PromptOptimizationRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.PROMPT_OPTIMIZATION (
                    ID, WORKSPACE_ID, SOURCE_PROFILE_ID, SOURCE_PROFILE_REVISION,
                    THREAD_ID, TURN_ID, INSTRUCTION_REVISION, INSTRUCTION_DIGEST, CREATED_AT
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            PromptOptimizationRef ref = record.ref();
            statement.setString(1, ref.id().toString());
            statement.setString(2, ref.workspaceId().toString());
            statement.setString(3, ref.sourceProfile().id());
            statement.setLong(4, ref.sourceProfile().revision());
            statement.setString(5, ref.threadId().toString());
            statement.setString(6, ref.turnId().toString());
            statement.setString(7, record.instructionRevision());
            statement.setString(8, record.instructionDigest());
            statement.setObject(9, record.createdAt().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private Optional<PromptOptimizationRecord> find(Connection connection, PromptOptimizationId id, boolean lock)
            throws SQLException {
        String sql = """
                SELECT ID, WORKSPACE_ID, SOURCE_PROFILE_ID, SOURCE_PROFILE_REVISION,
                       THREAD_ID, TURN_ID, INSTRUCTION_REVISION, INSTRUCTION_DIGEST,
                       ADOPTED_PROFILE_REVISION, ADOPTED_AT, CREATED_AT
                FROM CORE.PROMPT_OPTIMIZATION WHERE ID = ?
                """ + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    private List<PromptOptimizationRecord> list(Connection connection, WorkspaceId workspaceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, WORKSPACE_ID, SOURCE_PROFILE_ID, SOURCE_PROFILE_REVISION,
                       THREAD_ID, TURN_ID, INSTRUCTION_REVISION, INSTRUCTION_DIGEST,
                       ADOPTED_PROFILE_REVISION, ADOPTED_AT, CREATED_AT
                FROM CORE.PROMPT_OPTIMIZATION
                WHERE WORKSPACE_ID = ?
                ORDER BY CREATED_AT DESC, ID
                """)) {
            statement.setString(1, workspaceId.toString());
            try (ResultSet result = statement.executeQuery()) {
                List<PromptOptimizationRecord> records = new ArrayList<>();
                while (result.next()) {
                    records.add(map(result));
                }
                return List.copyOf(records);
            }
        }
    }

    private List<PromptOptimizationRecord> listQueued(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT O.ID, O.WORKSPACE_ID, O.SOURCE_PROFILE_ID, O.SOURCE_PROFILE_REVISION,
                       O.THREAD_ID, O.TURN_ID, O.INSTRUCTION_REVISION, O.INSTRUCTION_DIGEST,
                       O.ADOPTED_PROFILE_REVISION, O.ADOPTED_AT, O.CREATED_AT
                FROM CORE.PROMPT_OPTIMIZATION O
                JOIN CORE.AGENT_TURN T ON T.ID = O.TURN_ID
                WHERE T.STATUS = 'QUEUED'
                ORDER BY O.CREATED_AT, O.ID
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                List<PromptOptimizationRecord> records = new ArrayList<>();
                while (result.next()) {
                    records.add(map(result));
                }
                return List.copyOf(records);
            }
        }
    }

    private static PromptOptimizationRecord map(ResultSet result) throws SQLException {
        AgentProfileRef source =
                new AgentProfileRef(result.getString("SOURCE_PROFILE_ID"), result.getLong("SOURCE_PROFILE_REVISION"));
        PromptOptimizationRef ref = new PromptOptimizationRef(
                PromptOptimizationId.parse(result.getString("ID")),
                WorkspaceId.parse(result.getString("WORKSPACE_ID")),
                source,
                ThreadId.parse(result.getString("THREAD_ID")),
                TurnId.parse(result.getString("TURN_ID")));
        Long adoptedRevision = result.getObject("ADOPTED_PROFILE_REVISION", Long.class);
        OffsetDateTime adoptedAt = result.getObject("ADOPTED_AT", OffsetDateTime.class);
        return new PromptOptimizationRecord(
                ref,
                result.getString("INSTRUCTION_REVISION"),
                result.getString("INSTRUCTION_DIGEST"),
                Optional.ofNullable(adoptedRevision),
                Optional.ofNullable(adoptedAt).map(OffsetDateTime::toInstant),
                result.getObject("CREATED_AT", OffsetDateTime.class).toInstant());
    }

    private PromptOptimizationRecord recover(CommandIdentity identity, IdempotencyRepository.StoredCommand stored) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同命令使用");
        }
        return json.decode(stored.response(), PromptOptimizationRecord.class);
    }

    private static CommandIdentity requireStartIdentity(CommandIdentity identity) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        if (checked.expectedRevision() != 0) {
            throw PersistenceException.invalidRequest("Prompt 优化启动 expected revision 必须为 0");
        }
        return checked;
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Prompt 优化事务失败", failure);
        }
    }
}
