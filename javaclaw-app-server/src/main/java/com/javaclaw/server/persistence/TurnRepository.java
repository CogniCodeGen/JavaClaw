package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ResolvedTurnConfig;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.protocol.CanonicalJson;

/** Turn 行映射、创建与状态条件更新。 */
final class TurnRepository {
    private final CanonicalJson json = new CanonicalJson();

    List<AgentTurn> listRecoverable(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM CORE.AGENT_TURN
                WHERE STATUS IN ('QUEUED', 'RUNNING')
                ORDER BY CREATED_AT, ID
                """);
                ResultSet result = statement.executeQuery()) {
            List<AgentTurn> recoverable = new ArrayList<>();
            while (result.next()) {
                recoverable.add(map(result));
            }
            return List.copyOf(recoverable);
        }
    }

    Optional<AgentTurn> findByThread(Connection connection, ThreadId threadId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM CORE.AGENT_TURN WHERE THREAD_ID = ? ORDER BY CREATED_AT, ID FETCH FIRST ROW ONLY")) {
            statement.setString(1, threadId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    void lockThread(Connection connection, ThreadId threadId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT ID FROM CORE.AGENT_THREAD WHERE ID = ? FOR UPDATE")) {
            statement.setString(1, threadId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new PersistenceException("Thread 不存在");
                }
            }
        }
    }

    /** 写入 Item 或 checkpoint 的事务统一先 Thread 后 Turn，锁保持到外层事务结束。 */
    AgentTurn lockForJournal(Connection connection, TurnId turnId) throws SQLException {
        lockThread(connection, threadId(connection, turnId));
        return lock(connection, turnId);
    }

    boolean hasActiveTurn(Connection connection, ThreadId threadId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM CORE.AGENT_TURN
                WHERE THREAD_ID = ? AND STATUS IN ('QUEUED', 'RUNNING', 'WAITING')
                """)) {
            statement.setString(1, threadId.toString());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1) > 0;
            }
        }
    }

    AgentTurn insert(Connection connection, TurnStartRequest request, Instant now) throws SQLException {
        AgentTurn turn = new AgentTurn(
                TurnId.random(),
                request.threadId(),
                TurnStatus.QUEUED,
                1,
                request.configuration().budget(),
                request.configuration().role(),
                request.configuration().provider(),
                request.configuration().permissionProfile(),
                request.executionRoot(),
                request.configuration().promptManifestDigest(),
                request.configuration().toolCatalogDigest(),
                Optional.empty(),
                now,
                now,
                request.configuration().summary());
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.AGENT_TURN (
                    ID, THREAD_ID, STATUS, REVISION, INPUT_TOKENS, OUTPUT_TOKENS, TOOL_CALLS, CHILD_THREADS,
                    WALL_TIME_MILLIS, ROLE_ID, ROLE_REVISION, PROVIDER_ID, PROVIDER_REVISION, MODEL,
                    PERMISSION_PROFILE_ID, PERMISSION_PROFILE_VERSION, EXECUTION_ROOT, PROMPT_MANIFEST_DIGEST,
                    TOOL_CATALOG_DIGEST, ERROR_CODE, CREATED_AT, UPDATED_AT, RESOLVED_CONFIG_JSON
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindInsert(statement, turn);
            statement.setString(23, json.encode(request.configuration()).json());
            statement.executeUpdate();
        }
        new TurnContextRepository(json).freeze(connection, turn.id(), turn.provider());
        CodingEnvironmentRepository.freeze(
                connection, turn, request.codingEnvironment().orElseThrow(), now);
        return turn;
    }

    void transition(
            Connection connection,
            TurnId turnId,
            TurnStatus expected,
            TurnStatus next,
            Optional<String> errorCode,
            Instant now)
            throws SQLException {
        new TurnStreamRepository(json).lockJournal(connection, turnId);
        if (next == TurnStatus.COMPLETED && hasCancellation(connection, turnId)) {
            throw new TurnCancelledException("Turn 已收到持久化取消请求");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.AGENT_TURN
                SET STATUS = ?, REVISION = REVISION + 1, ERROR_CODE = ?, UPDATED_AT = ?
                WHERE ID = ? AND STATUS = ?
                """)) {
            statement.setString(1, next.name());
            statement.setString(2, errorCode.orElse(null));
            statement.setObject(3, at(now));
            statement.setString(4, turnId.toString());
            statement.setString(5, expected.name());
            if (statement.executeUpdate() != 1) {
                throw new PersistenceException("Turn revision 或状态已经改变");
            }
        }
        new TurnStreamJournal(json).finished(connection, turnId, next, now);
        if (next == TurnStatus.COMPLETED) {
            ConversationCompletionIndex.record(connection, turnId, now);
        }
    }

    boolean hasCancellation(Connection connection, TurnId turnId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT COUNT(*) FROM CORE.TURN_CANCELLATION WHERE TURN_ID = ?")) {
            statement.setString(1, turnId.toString());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1) > 0;
            }
        }
    }

    AgentTurn requestCancellation(
            Connection connection, TurnId turnId, long expectedRevision, String reason, Instant now)
            throws SQLException {
        AgentTurn current = lock(connection, turnId);
        if (current.revision() != expectedRevision) {
            throw PersistenceException.revisionConflict("Turn revision 已改变");
        }
        if (isTerminal(current.status())) {
            throw new PersistenceException("终态 Turn 不能取消");
        }
        insertCancellation(connection, turnId, reason, now);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.AGENT_TURN SET REVISION = REVISION + 1, UPDATED_AT = ?
                WHERE ID = ? AND REVISION = ?
                """)) {
            statement.setObject(1, at(now));
            statement.setString(2, turnId.toString());
            statement.setLong(3, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw PersistenceException.revisionConflict("Turn revision 已改变");
            }
        }
        return find(connection, turnId).orElseThrow();
    }

    Optional<AgentTurn> find(Connection connection, TurnId id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM CORE.AGENT_TURN WHERE ID = ?
                """)) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    Optional<AgentTurn> findActiveByThread(Connection connection, ThreadId threadId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM CORE.AGENT_TURN
                WHERE THREAD_ID = ? AND STATUS IN ('QUEUED', 'RUNNING', 'WAITING')
                ORDER BY CREATED_AT DESC FETCH FIRST 1 ROW ONLY
                """)) {
            statement.setString(1, threadId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    AgentTurn lock(Connection connection, TurnId turnId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT * FROM CORE.AGENT_TURN WHERE ID = ? FOR UPDATE")) {
            statement.setString(1, turnId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new PersistenceException("Turn 不存在");
                }
                return map(result);
            }
        }
    }

    private void insertCancellation(Connection connection, TurnId turnId, String reason, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.TURN_CANCELLATION (TURN_ID, REASON, REQUESTED_AT) VALUES (?, ?, ?)
                """)) {
            statement.setString(1, turnId.toString());
            statement.setString(2, reason);
            statement.setObject(3, at(now));
            statement.executeUpdate();
        }
    }

    private static boolean isTerminal(TurnStatus status) {
        return status == TurnStatus.COMPLETED || status == TurnStatus.CANCELLED || status == TurnStatus.FAILED;
    }

    ThreadId threadId(Connection connection, TurnId turnId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT THREAD_ID FROM CORE.AGENT_TURN WHERE ID = ?")) {
            statement.setString(1, turnId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new PersistenceException("Turn 不存在");
                }
                return ThreadId.parse(result.getString(1));
            }
        }
    }

    private void bindInsert(PreparedStatement statement, AgentTurn turn) throws SQLException {
        statement.setString(1, turn.id().toString());
        statement.setString(2, turn.threadId().toString());
        statement.setString(3, turn.status().name());
        statement.setLong(4, turn.revision());
        statement.setLong(5, turn.budget().inputTokens());
        statement.setLong(6, turn.budget().outputTokens());
        statement.setInt(7, turn.budget().toolCalls());
        statement.setInt(8, turn.budget().childThreads());
        statement.setLong(9, turn.budget().wallTime().toMillis());
        statement.setString(10, turn.role().id());
        statement.setLong(11, turn.role().revision());
        statement.setString(12, turn.provider().endpointId());
        statement.setLong(13, turn.provider().endpointRevision());
        statement.setString(14, turn.provider().model());
        statement.setString(15, turn.permissionProfile().id());
        statement.setLong(16, turn.permissionProfile().version());
        statement.setString(17, turn.executionRoot().toString());
        statement.setString(18, turn.promptManifestDigest());
        statement.setString(19, turn.toolCatalogDigest());
        statement.setString(20, null);
        statement.setObject(21, at(turn.createdAt()));
        statement.setObject(22, at(turn.updatedAt()));
    }

    private AgentTurn map(ResultSet result) throws SQLException {
        String errorCode = result.getString("ERROR_CODE");
        TurnBudget budget = new TurnBudget(
                result.getLong("INPUT_TOKENS"),
                result.getLong("OUTPUT_TOKENS"),
                result.getInt("TOOL_CALLS"),
                result.getInt("CHILD_THREADS"),
                Duration.ofMillis(result.getLong("WALL_TIME_MILLIS")));
        return new AgentTurn(
                TurnId.parse(result.getString("ID")),
                ThreadId.parse(result.getString("THREAD_ID")),
                TurnStatus.valueOf(result.getString("STATUS")),
                result.getLong("REVISION"),
                budget,
                new AgentRoleRef(result.getString("ROLE_ID"), result.getLong("ROLE_REVISION")),
                new ProviderRef(
                        result.getString("PROVIDER_ID"),
                        result.getLong("PROVIDER_REVISION"),
                        result.getString("MODEL")),
                new PermissionProfileRef(
                        result.getString("PERMISSION_PROFILE_ID"), result.getLong("PERMISSION_PROFILE_VERSION")),
                java.nio.file.Path.of(result.getString("EXECUTION_ROOT")),
                result.getString("PROMPT_MANIFEST_DIGEST"),
                result.getString("TOOL_CATALOG_DIGEST"),
                Optional.ofNullable(errorCode),
                instant(result, "CREATED_AT"),
                instant(result, "UPDATED_AT"),
                decodeConfiguration(result).summary());
    }

    Optional<ResolvedTurnConfig> configuration(Connection connection, TurnId turnId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT RESOLVED_CONFIG_JSON FROM CORE.AGENT_TURN WHERE ID = ?")) {
            statement.setString(1, turnId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(decodeConfiguration(result)) : Optional.empty();
            }
        }
    }

    private ResolvedTurnConfig decodeConfiguration(ResultSet result) throws SQLException {
        return json.decode(new CanonicalPayload(result.getString("RESOLVED_CONFIG_JSON")), ResolvedTurnConfig.class);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toInstant();
    }
}
