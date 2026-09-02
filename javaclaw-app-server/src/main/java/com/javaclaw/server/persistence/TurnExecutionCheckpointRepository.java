package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.javaclaw.api.ApprovalRequest;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.runtime.TurnExecutionPhase;
import com.javaclaw.runtime.TurnToolBatch;
import com.javaclaw.runtime.TurnVisibleTools;

/** Turn 外部调用边界与预算账本的条件更新。 */
final class TurnExecutionCheckpointRepository {
    private final CanonicalJson json;

    TurnExecutionCheckpointRepository(CanonicalJson json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    void insert(Connection connection, TurnId turnId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.TURN_EXECUTION_CHECKPOINT (
                    TURN_ID, PHASE, REVISION, INPUT_TOKENS, OUTPUT_TOKENS, REASONING_TOKENS,
                    CACHED_INPUT_TOKENS, CONSUMED_TOOL_CALLS, MODEL_INVOCATIONS, TOOL_BATCH,
                    NEXT_TOOL_INDEX, VISIBLE_TOOLS, ACTIVE_INTENT_DIGEST, UPDATED_AT
                ) VALUES (?, 'READY_FOR_MODEL', 1, 0, 0, 0, 0, 0, 0, ?, 0, ?, NULL, ?)
                """)) {
            statement.setString(1, turnId.toString());
            statement.setString(2, json.encode(TurnToolBatch.empty()).json());
            statement.setString(3, json.encode(TurnVisibleTools.empty()).json());
            statement.setObject(4, at(now));
            statement.executeUpdate();
        }
    }

    Optional<StoredCheckpoint> find(Connection connection, TurnId turnId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM CORE.TURN_EXECUTION_CHECKPOINT WHERE TURN_ID = ?
                """)) {
            statement.setString(1, turnId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    void recordModelIntent(Connection connection, TurnId turnId, int invocationNumber, String intentDigest, Instant now)
            throws SQLException {
        requireUpdate(connection, """
                UPDATE CORE.TURN_EXECUTION_CHECKPOINT
                SET PHASE = 'MODEL_IN_FLIGHT', REVISION = REVISION + 1, MODEL_INVOCATIONS = ?,
                    ACTIVE_INTENT_DIGEST = ?, UPDATED_AT = ?
                WHERE TURN_ID = ? AND PHASE = 'READY_FOR_MODEL' AND MODEL_INVOCATIONS = ?
                """, statement -> {
            statement.setInt(1, invocationNumber);
            statement.setString(2, digest(intentDigest));
            statement.setObject(3, at(now));
            statement.setString(4, turnId.toString());
            statement.setInt(5, invocationNumber - 1);
        });
    }

    void commitModelResult(
            Connection connection,
            TurnId turnId,
            int invocationNumber,
            ModelUsage usage,
            TurnToolBatch batch,
            Instant now)
            throws SQLException {
        TurnExecutionPhase next =
                batch.calls().isEmpty() ? TurnExecutionPhase.MODEL_COMMITTED : TurnExecutionPhase.TOOLS_READY;
        requireUpdate(connection, """
                UPDATE CORE.TURN_EXECUTION_CHECKPOINT
                SET PHASE = ?, REVISION = REVISION + 1, INPUT_TOKENS = ?, OUTPUT_TOKENS = ?,
                    REASONING_TOKENS = ?, CACHED_INPUT_TOKENS = ?, TOOL_BATCH = ?, NEXT_TOOL_INDEX = 0,
                    ACTIVE_INTENT_DIGEST = NULL, UPDATED_AT = ?
                WHERE TURN_ID = ? AND PHASE = 'MODEL_IN_FLIGHT' AND MODEL_INVOCATIONS = ?
                """, statement -> {
            statement.setString(1, next.name());
            statement.setLong(2, usage.inputTokens());
            statement.setLong(3, usage.outputTokens());
            statement.setLong(4, usage.reasoningTokens());
            statement.setLong(5, usage.cachedInputTokens());
            statement.setString(6, json.encode(batch).json());
            statement.setObject(7, at(now));
            statement.setString(8, turnId.toString());
            statement.setInt(9, invocationNumber);
        });
    }

    void recordToolIntent(
            Connection connection,
            TurnId turnId,
            int toolIndex,
            int consumedToolCalls,
            String intentDigest,
            Instant now)
            throws SQLException {
        StoredCheckpoint current =
                find(connection, turnId).orElseThrow(() -> new PersistenceException("Turn 缺少执行 checkpoint"));
        int expectedConsumed =
                switch (current.phase()) {
                    case TOOLS_READY -> Math.addExact(current.toolCalls(), 1);
                    case TOOL_APPROVAL_RESOLVED -> current.toolCalls();
                    default -> throw PersistenceException.revisionConflict("工具调用恢复位置已改变");
                };
        if (consumedToolCalls != expectedConsumed || current.nextToolIndex() != toolIndex) {
            throw PersistenceException.revisionConflict("工具调用预算或恢复位置已改变");
        }
        requireUpdate(connection, """
                UPDATE CORE.TURN_EXECUTION_CHECKPOINT
                SET PHASE = 'TOOL_IN_FLIGHT', REVISION = REVISION + 1, CONSUMED_TOOL_CALLS = ?,
                    ACTIVE_INTENT_DIGEST = ?, UPDATED_AT = ?
                WHERE TURN_ID = ? AND PHASE = ? AND NEXT_TOOL_INDEX = ?
                """, statement -> {
            statement.setInt(1, consumedToolCalls);
            statement.setString(2, digest(intentDigest));
            statement.setObject(3, at(now));
            statement.setString(4, turnId.toString());
            statement.setString(5, current.phase().name());
            statement.setInt(6, toolIndex);
        });
    }

    void markApprovalWaiting(Connection connection, ApprovalRequest request, Instant now) throws SQLException {
        StoredCheckpoint current = requireApprovalCall(connection, request);
        if (current.phase() != TurnExecutionPhase.TOOL_IN_FLIGHT) {
            throw PersistenceException.revisionConflict("工具调用已不在可申请审批的阶段");
        }
        updatePhase(
                connection,
                request.turnId(),
                TurnExecutionPhase.TOOL_IN_FLIGHT,
                TurnExecutionPhase.TOOL_WAITING_APPROVAL,
                now);
    }

    void markApprovalResolved(Connection connection, ApprovalRequest request, Instant now) throws SQLException {
        StoredCheckpoint current = requireApprovalCall(connection, request);
        if (current.phase() != TurnExecutionPhase.TOOL_WAITING_APPROVAL) {
            throw PersistenceException.revisionConflict("工具调用已不在审批等待阶段");
        }
        updatePhase(
                connection,
                request.turnId(),
                TurnExecutionPhase.TOOL_WAITING_APPROVAL,
                TurnExecutionPhase.TOOL_APPROVAL_RESOLVED,
                now);
    }

    void markApprovedExternalCall(Connection connection, ApprovalRequest request, Instant now) throws SQLException {
        StoredCheckpoint current = requireApprovalCall(connection, request);
        if (current.phase() == TurnExecutionPhase.TOOL_IN_FLIGHT) {
            return;
        }
        if (current.phase() != TurnExecutionPhase.TOOL_APPROVAL_RESOLVED) {
            throw PersistenceException.revisionConflict("工具调用已不在审批决议阶段");
        }
        updatePhase(
                connection,
                request.turnId(),
                TurnExecutionPhase.TOOL_APPROVAL_RESOLVED,
                TurnExecutionPhase.TOOL_IN_FLIGHT,
                now);
    }

    void commitToolResult(
            Connection connection,
            TurnId turnId,
            int toolIndex,
            int batchSize,
            TurnVisibleTools visibleTools,
            Instant now)
            throws SQLException {
        int nextIndex = Math.addExact(toolIndex, 1);
        boolean batchCompleted = nextIndex == batchSize;
        TurnExecutionPhase next = batchCompleted ? TurnExecutionPhase.READY_FOR_MODEL : TurnExecutionPhase.TOOLS_READY;
        TurnToolBatch nextBatch = batchCompleted ? TurnToolBatch.empty() : currentBatch(connection, turnId);
        int persistedIndex = batchCompleted ? 0 : nextIndex;
        requireUpdate(connection, """
                UPDATE CORE.TURN_EXECUTION_CHECKPOINT
                SET PHASE = ?, REVISION = REVISION + 1, TOOL_BATCH = ?, NEXT_TOOL_INDEX = ?, VISIBLE_TOOLS = ?,
                    ACTIVE_INTENT_DIGEST = NULL, UPDATED_AT = ?
                WHERE TURN_ID = ? AND PHASE IN ('TOOL_IN_FLIGHT', 'TOOL_APPROVAL_RESOLVED')
                    AND NEXT_TOOL_INDEX = ?
                """, statement -> {
            statement.setString(1, next.name());
            statement.setString(2, json.encode(nextBatch).json());
            statement.setInt(3, persistedIndex);
            statement.setString(4, json.encode(visibleTools).json());
            statement.setObject(5, at(now));
            statement.setString(6, turnId.toString());
            statement.setInt(7, toolIndex);
        });
    }

    private TurnToolBatch currentBatch(Connection connection, TurnId turnId) throws SQLException {
        return find(connection, turnId)
                .orElseThrow(() -> new PersistenceException("Turn 缺少执行 checkpoint"))
                .toolBatch();
    }

    private StoredCheckpoint requireApprovalCall(Connection connection, ApprovalRequest request) throws SQLException {
        StoredCheckpoint current =
                find(connection, request.turnId()).orElseThrow(() -> new PersistenceException("Turn 缺少执行 checkpoint"));
        if (!current.phase().toolBatchPhase()
                || current.nextToolIndex() >= current.toolBatch().calls().size()) {
            throw new PersistenceException("审批没有对应的工具恢复位置");
        }
        com.javaclaw.runtime.ModelToolCall call = current.toolBatch().calls().get(current.nextToolIndex());
        boolean matches = call.callId().equals(request.callId())
                && call.tool().equals(request.tool())
                && call.arguments().sha256().equals(request.requestDigest());
        if (!matches) {
            throw PersistenceException.idempotencyConflict("审批与已提交工具意图不一致");
        }
        return current;
    }

    private static void updatePhase(
            Connection connection, TurnId turnId, TurnExecutionPhase expected, TurnExecutionPhase next, Instant now)
            throws SQLException {
        requireUpdate(connection, """
                UPDATE CORE.TURN_EXECUTION_CHECKPOINT
                SET PHASE = ?, REVISION = REVISION + 1, UPDATED_AT = ?
                WHERE TURN_ID = ? AND PHASE = ?
                """, statement -> {
            statement.setString(1, next.name());
            statement.setObject(2, at(now));
            statement.setString(3, turnId.toString());
            statement.setString(4, expected.name());
        });
    }

    private StoredCheckpoint map(ResultSet result) throws SQLException {
        String activeDigest = result.getString("ACTIVE_INTENT_DIGEST");
        return new StoredCheckpoint(
                TurnExecutionPhase.valueOf(result.getString("PHASE")),
                result.getLong("REVISION"),
                new ModelUsage(
                        result.getLong("INPUT_TOKENS"),
                        result.getLong("OUTPUT_TOKENS"),
                        result.getLong("REASONING_TOKENS"),
                        result.getLong("CACHED_INPUT_TOKENS")),
                result.getInt("CONSUMED_TOOL_CALLS"),
                result.getInt("MODEL_INVOCATIONS"),
                json.decode(json.parse(result.getString("TOOL_BATCH")), TurnToolBatch.class),
                result.getInt("NEXT_TOOL_INDEX"),
                json.decode(json.parse(result.getString("VISIBLE_TOOLS")), TurnVisibleTools.class),
                Optional.ofNullable(activeDigest),
                result.getObject("UPDATED_AT", OffsetDateTime.class).toInstant());
    }

    private static String digest(String value) {
        String normalized =
                java.util.Objects.requireNonNull(value, "intentDigest").strip().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("intentDigest must be a SHA-256 digest");
        }
        return normalized;
    }

    private static void requireUpdate(Connection connection, String sql, StatementBinder binder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            if (statement.executeUpdate() != 1) {
                throw PersistenceException.revisionConflict("Turn 执行 checkpoint 已改变");
            }
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    record StoredCheckpoint(
            TurnExecutionPhase phase,
            long revision,
            ModelUsage usage,
            int toolCalls,
            int modelInvocations,
            TurnToolBatch toolBatch,
            int nextToolIndex,
            TurnVisibleTools visibleTools,
            Optional<String> activeIntentDigest,
            Instant updatedAt) {}

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
