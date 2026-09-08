package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemSchemaRegistry;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.CompactionOutcome;
import com.javaclaw.runtime.CompactionRequest;
import com.javaclaw.runtime.CompactionTicket;
import com.javaclaw.runtime.ModelUsage;

/** 压缩外部调用的独立账本；统一按 Thread、Turn、checkpoint 顺序持锁，禁止将压缩误提交为普通生成。 */
final class TurnCompactionJournal {
    private final H2Transactions transactions;
    private final CanonicalJson json;
    private final Clock clock;
    private final ItemSchemaRegistry schemas;
    private final TurnContextRepository contexts;

    TurnCompactionJournal(H2Database database, CanonicalJson json, Clock clock, ItemSchemaRegistry schemas) {
        transactions = new H2Transactions(database);
        this.json = json;
        this.clock = clock;
        this.schemas = schemas;
        contexts = new TurnContextRepository(json);
    }

    CompactionTicket intent(CompactionRequest request, boolean nativeCall) {
        return execute(connection -> {
            TurnId turnId = request.command().turn().id();
            lockRunning(connection, turnId);
            lockReady(connection, turnId);
            requireNoActive(connection, turnId);
            contexts.freeze(connection, turnId, request.command().provider());
            int ordinal = nextOrdinal(connection, turnId);
            String digest = json.encode(request).sha256();
            CompactionTicket ticket = new CompactionTicket(ordinal, digest, nativeCall);
            insert(connection, request, ticket);
            if (nativeCall) {
                updatePhase(connection, turnId, "READY_FOR_MODEL", "MODEL_IN_FLIGHT", digest);
            }
            return ticket;
        });
    }

    void commit(CompactionRequest request, CompactionTicket ticket, CompactionOutcome outcome, ModelUsage usage) {
        if (!json.encode(request).sha256().equals(ticket.digest())) {
            throw PersistenceException.revisionConflict("压缩提交请求与冻结意图不一致");
        }
        execute(connection -> {
            TurnId turnId = request.command().turn().id();
            lockRunning(connection, turnId);
            lock(connection, turnId);
            long sequence = requireIntent(connection, turnId, ticket);
            Instant now = clock.instant();
            contexts.save(connection, turnId, outcome.window(), sequence);
            var encoded = schemas.encode(outcome.item());
            new ItemRepository(new TurnRepository())
                    .append(
                            connection,
                            new ItemRepository.ItemWrite(
                                    turnId,
                                    "compaction",
                                    CoreSchemas.COMPACTION,
                                    "core",
                                    ItemStatus.COMPLETED,
                                    encoded.payload(),
                                    now));
            if (outcome.window().providerState().isPresent()) {
                new ProviderStateRepository()
                        .save(
                                connection,
                                turnId,
                                request.command().modelRoute(),
                                outcome.window().providerState().orElseThrow(),
                                outcome.window().estimatedInputTokens(),
                                now);
            }
            commitLedger(connection, turnId, ticket, outcome);
            commitBudget(connection, turnId, ticket, usage);
            return null;
        });
    }

    static void requireNoActive(Connection connection, TurnId turnId) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT 1 FROM CORE.TURN_COMPACTION_CALL WHERE TURN_ID = ? AND STATE = 'IN_FLIGHT'")) {
            query.setString(1, turnId.toString());
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    throw PersistenceException.revisionConflict("Turn 有尚未提交的压缩调用，不能按普通生成提交");
                }
            }
        }
    }

    /** 纯本地摘要没有外部副作用；主 checkpoint 锁内保留 abandoned 意图并允许恢复后重算。 */
    static void recoverLocal(Connection connection, TurnId turnId) throws SQLException {
        try (var lock = connection.prepareStatement(
                "SELECT PHASE FROM CORE.TURN_EXECUTION_CHECKPOINT WHERE TURN_ID = ? FOR UPDATE")) {
            lock.setString(1, turnId.toString());
            try (var rows = lock.executeQuery()) {
                if (!rows.next() || !"READY_FOR_MODEL".equals(rows.getString(1))) {
                    return;
                }
            }
        }
        try (var statement = connection.prepareStatement("""
                UPDATE CORE.TURN_COMPACTION_CALL SET STATE = 'ABANDONED', UPDATED_AT = CURRENT_TIMESTAMP
                WHERE TURN_ID = ? AND STATE = 'IN_FLIGHT' AND NATIVE_CALL = FALSE
                """)) {
            statement.setString(1, turnId.toString());
            statement.executeUpdate();
        }
    }

    private void lockRunning(Connection connection, TurnId turnId) throws SQLException {
        if (new TurnRepository().lockForJournal(connection, turnId).status() != TurnStatus.RUNNING) {
            throw PersistenceException.revisionConflict("终态 Turn 不能开始或提交压缩");
        }
    }

    private void lockReady(Connection connection, TurnId turnId) throws SQLException {
        if (!"READY_FOR_MODEL".equals(lock(connection, turnId))) {
            throw PersistenceException.revisionConflict("压缩只能在完整工具批次后的模型安全点开始");
        }
    }

    private String lock(Connection connection, TurnId turnId) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT PHASE FROM CORE.TURN_EXECUTION_CHECKPOINT WHERE TURN_ID = ? FOR UPDATE")) {
            query.setString(1, turnId.toString());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new PersistenceException("Turn 缺少 checkpoint");
                }
                return rows.getString(1);
            }
        }
    }

    private int nextOrdinal(Connection connection, TurnId turnId) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT COALESCE(MAX(ORDINAL), 0) + 1 FROM CORE.TURN_COMPACTION_CALL WHERE TURN_ID = ?")) {
            query.setString(1, turnId.toString());
            try (var rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private void insert(Connection connection, CompactionRequest request, CompactionTicket ticket) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO CORE.TURN_COMPACTION_CALL
                (TURN_ID, ORDINAL, STATE, INTENT_DIGEST, NATIVE_CALL, BEFORE_TOKENS, SOURCE_SEQUENCE, CREATED_AT, UPDATED_AT)
                VALUES (?, ?, 'IN_FLIGHT', ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, request.command().turn().id().toString());
            statement.setInt(2, ticket.ordinal());
            statement.setString(3, ticket.digest());
            statement.setBoolean(4, ticket.nativeCall());
            statement.setLong(5, request.window().estimatedInputTokens());
            statement.setLong(
                    6,
                    TurnContextRepository.sequence(
                            connection, request.command().turn().id()));
            statement.setObject(7, clock.instant().atOffset(ZoneOffset.UTC));
            statement.setObject(8, clock.instant().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private long requireIntent(Connection connection, TurnId turnId, CompactionTicket ticket) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT SOURCE_SEQUENCE FROM CORE.TURN_COMPACTION_CALL
                WHERE TURN_ID = ? AND ORDINAL = ? AND STATE = 'IN_FLIGHT' AND INTENT_DIGEST = ? AND NATIVE_CALL = ?
                """)) {
            query.setString(1, turnId.toString());
            query.setInt(2, ticket.ordinal());
            query.setString(3, ticket.digest());
            query.setBoolean(4, ticket.nativeCall());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw PersistenceException.revisionConflict("压缩结果与活动意图不一致");
                }
                return rows.getLong(1);
            }
        }
    }

    private void commitLedger(Connection connection, TurnId turnId, CompactionTicket ticket, CompactionOutcome outcome)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE CORE.TURN_COMPACTION_CALL SET STATE = 'COMMITTED', AFTER_TOKENS = ?, USAGE_JSON = ?,
                    RESULT_DIGEST = ?, UPDATED_AT = ? WHERE TURN_ID = ? AND ORDINAL = ? AND STATE = 'IN_FLIGHT'
                """)) {
            statement.setLong(1, outcome.window().estimatedInputTokens());
            statement.setString(2, json.encode(outcome.usage()).json());
            statement.setString(3, json.encode(outcome).sha256());
            statement.setObject(4, clock.instant().atOffset(ZoneOffset.UTC));
            statement.setString(5, turnId.toString());
            statement.setInt(6, ticket.ordinal());
            requireUpdated(statement.executeUpdate());
        }
    }

    private void commitBudget(Connection connection, TurnId turnId, CompactionTicket ticket, ModelUsage usage)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE CORE.TURN_EXECUTION_CHECKPOINT SET PHASE = 'READY_FOR_MODEL', ACTIVE_INTENT_DIGEST = NULL,
                    INPUT_TOKENS = ?, OUTPUT_TOKENS = ?, REASONING_TOKENS = ?, CACHED_INPUT_TOKENS = ?,
                    REVISION = REVISION + 1, UPDATED_AT = ? WHERE TURN_ID = ? AND PHASE = ?
                    AND (ACTIVE_INTENT_DIGEST = ? OR ACTIVE_INTENT_DIGEST IS NULL AND ? = FALSE)
                """)) {
            statement.setLong(1, usage.inputTokens());
            statement.setLong(2, usage.outputTokens());
            statement.setLong(3, usage.reasoningTokens());
            statement.setLong(4, usage.cachedInputTokens());
            statement.setObject(5, clock.instant().atOffset(ZoneOffset.UTC));
            statement.setString(6, turnId.toString());
            statement.setString(7, ticket.nativeCall() ? "MODEL_IN_FLIGHT" : "READY_FOR_MODEL");
            statement.setString(8, ticket.digest());
            statement.setBoolean(9, ticket.nativeCall());
            requireUpdated(statement.executeUpdate());
        }
    }

    private void updatePhase(Connection connection, TurnId turnId, String expected, String next, String digest)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE CORE.TURN_EXECUTION_CHECKPOINT SET PHASE = ?, ACTIVE_INTENT_DIGEST = ?, REVISION = REVISION + 1,
                    UPDATED_AT = ? WHERE TURN_ID = ? AND PHASE = ?
                """)) {
            statement.setString(1, next);
            statement.setString(2, digest);
            statement.setObject(3, clock.instant().atOffset(ZoneOffset.UTC));
            statement.setString(4, turnId.toString());
            statement.setString(5, expected);
            requireUpdated(statement.executeUpdate());
        }
    }

    private static void requireUpdated(int count) {
        if (count != 1) {
            throw PersistenceException.revisionConflict("压缩 checkpoint 已改变");
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("压缩账本事务失败", failure);
        }
    }
}
