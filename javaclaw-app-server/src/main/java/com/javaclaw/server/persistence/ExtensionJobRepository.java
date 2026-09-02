package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobStepResult;
import com.javaclaw.extension.spi.ExtensionJobSubmission;
import com.javaclaw.extension.spi.ExtensionJobUnit;
import com.javaclaw.extension.spi.ExtensionJobUnitState;
import com.javaclaw.extension.spi.ExtensionJobWorkUnit;

/** Extension Job 与工作单元的 SQL、条件更新和行映射。 */
final class ExtensionJobRepository {
    Map<ExecutionState, Integer> countByState(Connection connection) throws SQLException {
        EnumMap<ExecutionState, Integer> counts = new EnumMap<>(ExecutionState.class);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT STATE, COUNT(*) AS STATE_COUNT FROM CORE.EXTENSION_JOB GROUP BY STATE
                """);
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                counts.put(ExecutionState.valueOf(result.getString("STATE")), result.getInt("STATE_COUNT"));
            }
        }
        return Map.copyOf(counts);
    }

    ExtensionJob insert(Connection connection, String id, ExtensionJobSubmission submission, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.EXTENSION_JOB (
                    ID, EXTENSION_ID, WORKSPACE_ID, JOB_TYPE, DEFINITION_ID, DEFINITION_REVISION,
                    FROZEN_INPUT, STATE, REVISION, CHECKPOINT, NEXT_UNIT_SEQUENCE, ACTIVE_UNIT_SEQUENCE,
                    ERROR_CODE, CREATED_AT, UPDATED_AT
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'QUEUED', 1, ?, 1, NULL, NULL, ?, ?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, submission.extensionId().value());
            statement.setString(3, submission.workspaceId().toString());
            statement.setString(4, submission.jobType());
            statement.setString(5, submission.definitionId());
            statement.setLong(6, submission.definitionRevision());
            statement.setString(7, submission.frozenInput().json());
            statement.setString(8, submission.initialCheckpoint().json());
            statement.setObject(9, at(now));
            statement.setObject(10, at(now));
            statement.executeUpdate();
        }
        return find(connection, id).orElseThrow();
    }

    Optional<ExtensionJob> find(Connection connection, String id) throws SQLException {
        return find(connection, id, false);
    }

    Optional<ExtensionJob> lock(Connection connection, String id) throws SQLException {
        return find(connection, id, true);
    }

    List<ExtensionJob> list(
            Connection connection,
            Optional<WorkspaceId> workspaceId,
            Optional<ExtensionId> extensionId,
            Set<ExecutionState> states,
            int limit)
            throws SQLException {
        return page(connection, workspaceId, extensionId, states, Optional.empty(), limit);
    }

    List<ExtensionJob> page(
            Connection connection,
            Optional<WorkspaceId> workspaceId,
            Optional<ExtensionId> extensionId,
            Set<ExecutionState> states,
            Optional<com.javaclaw.extension.spi.ExtensionJobCursor> after,
            int limit)
            throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM CORE.EXTENSION_JOB WHERE 1 = 1");
        workspaceId.ifPresent(ignored -> sql.append(" AND WORKSPACE_ID = ?"));
        extensionId.ifPresent(ignored -> sql.append(" AND EXTENSION_ID = ?"));
        List<ExecutionState> stateFilter = states.stream().sorted().toList();
        if (!stateFilter.isEmpty()) {
            sql.append(" AND STATE IN (")
                    .append(String.join(",", java.util.Collections.nCopies(stateFilter.size(), "?")))
                    .append(')');
        }
        after.ifPresent(ignored -> sql.append(" AND (UPDATED_AT < ? OR (UPDATED_AT = ? AND ID > ?))"));
        sql.append(" ORDER BY UPDATED_AT DESC, ID LIMIT ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            if (workspaceId.isPresent()) {
                statement.setString(parameter++, workspaceId.orElseThrow().toString());
            }
            if (extensionId.isPresent()) {
                statement.setString(parameter++, extensionId.orElseThrow().value());
            }
            for (ExecutionState state : stateFilter) {
                statement.setString(parameter++, state.name());
            }
            if (after.isPresent()) {
                var cursor = after.orElseThrow();
                statement.setObject(parameter++, cursor.updatedAt().atOffset(java.time.ZoneOffset.UTC));
                statement.setObject(parameter++, cursor.updatedAt().atOffset(java.time.ZoneOffset.UTC));
                statement.setString(parameter++, cursor.id());
            }
            statement.setInt(parameter, limit);
            return readJobs(statement);
        }
    }

    List<ExtensionJobUnit> listUnits(Connection connection, String jobId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM CORE.EXTENSION_JOB_UNIT WHERE JOB_ID = ? ORDER BY UNIT_SEQUENCE
                """)) {
            statement.setString(1, jobId);
            try (ResultSet result = statement.executeQuery()) {
                List<ExtensionJobUnit> units = new ArrayList<>();
                while (result.next()) {
                    units.add(mapUnit(result));
                }
                return List.copyOf(units);
            }
        }
    }

    Optional<ExtensionJobUnit> activeUnit(Connection connection, ExtensionJob job) throws SQLException {
        if (job.activeUnitSequence().isEmpty()) {
            return Optional.empty();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM CORE.EXTENSION_JOB_UNIT WHERE JOB_ID = ? AND UNIT_SEQUENCE = ?
                """)) {
            statement.setString(1, job.id());
            statement.setLong(2, job.activeUnitSequence().orElseThrow());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapUnit(result)) : Optional.empty();
            }
        }
    }

    ExtensionJob markRunning(Connection connection, ExtensionJob current, Instant now) throws SQLException {
        requireUpdate(connection, """
                UPDATE CORE.EXTENSION_JOB SET STATE = 'RUNNING', REVISION = REVISION + 1, UPDATED_AT = ?
                WHERE ID = ? AND REVISION = ? AND STATE = 'QUEUED' AND ACTIVE_UNIT_SEQUENCE IS NULL
                """, statement -> {
            statement.setObject(1, at(now));
            statement.setString(2, current.id());
            statement.setLong(3, current.revision());
        });
        return find(connection, current.id()).orElseThrow();
    }

    ExtensionJob transition(
            Connection connection, ExtensionJob current, Set<ExecutionState> allowed, ExecutionState next, Instant now)
            throws SQLException {
        if (!allowed.contains(current.state())) {
            throw PersistenceException.invalidRequest("Job 当前状态不允许该操作");
        }
        requireNoActiveUnit(current);
        requireUpdate(connection, """
                UPDATE CORE.EXTENSION_JOB
                SET STATE = ?, REVISION = REVISION + 1, ERROR_CODE = NULL, UPDATED_AT = ?
                WHERE ID = ? AND REVISION = ? AND ACTIVE_UNIT_SEQUENCE IS NULL
                """, statement -> {
            statement.setString(1, next.name());
            statement.setObject(2, at(now));
            statement.setString(3, current.id());
            statement.setLong(4, current.revision());
        });
        return find(connection, current.id()).orElseThrow();
    }

    ExtensionJob continueWaiting(
            Connection connection,
            ExtensionJob current,
            ExecutionState waitingState,
            CanonicalPayload checkpoint,
            Instant now)
            throws SQLException {
        if (current.state() != waitingState
                || (waitingState != ExecutionState.WAITING_INPUT && waitingState != ExecutionState.WAITING_APPROVAL)) {
            throw PersistenceException.invalidRequest("Job 当前状态不接受该等待决议");
        }
        requireNoActiveUnit(current);
        requireUpdate(connection, """
                UPDATE CORE.EXTENSION_JOB
                SET STATE = 'QUEUED', REVISION = REVISION + 1, CHECKPOINT = ?, ERROR_CODE = NULL, UPDATED_AT = ?
                WHERE ID = ? AND REVISION = ? AND STATE = ? AND ACTIVE_UNIT_SEQUENCE IS NULL
                """, statement -> {
            statement.setString(1, checkpoint.json());
            statement.setObject(2, at(now));
            statement.setString(3, current.id());
            statement.setLong(4, current.revision());
            statement.setString(5, waitingState.name());
        });
        return find(connection, current.id()).orElseThrow();
    }

    ExtensionJob recordIntent(Connection connection, ExtensionJob current, ExtensionJobWorkUnit workUnit, Instant now)
            throws SQLException {
        if (current.state() != ExecutionState.RUNNING
                || current.activeUnitSequence().isPresent()) {
            throw PersistenceException.revisionConflict("Job 不能记录新的工作单元");
        }
        long sequence = current.nextUnitSequence();
        insertUnit(connection, current.id(), sequence, workUnit, now);
        requireUpdate(connection, """
                UPDATE CORE.EXTENSION_JOB
                SET REVISION = REVISION + 1, NEXT_UNIT_SEQUENCE = NEXT_UNIT_SEQUENCE + 1,
                    ACTIVE_UNIT_SEQUENCE = ?, UPDATED_AT = ?
                WHERE ID = ? AND REVISION = ? AND STATE = 'RUNNING' AND ACTIVE_UNIT_SEQUENCE IS NULL
                """, statement -> {
            statement.setLong(1, sequence);
            statement.setObject(2, at(now));
            statement.setString(3, current.id());
            statement.setLong(4, current.revision());
        });
        return find(connection, current.id()).orElseThrow();
    }

    ExtensionJob completeUnit(
            Connection connection,
            ExtensionJob current,
            ExtensionJobUnit unit,
            ExtensionJobStepResult step,
            Instant now)
            throws SQLException {
        requireActive(current, unit);
        updateUnitCompleted(connection, unit, step, now);
        requireUpdate(connection, """
                UPDATE CORE.EXTENSION_JOB
                SET STATE = ?, REVISION = REVISION + 1, CHECKPOINT = ?, ACTIVE_UNIT_SEQUENCE = NULL,
                    ERROR_CODE = NULL, UPDATED_AT = ?
                WHERE ID = ? AND REVISION = ? AND STATE = 'RUNNING' AND ACTIVE_UNIT_SEQUENCE = ?
                """, statement -> {
            statement.setString(1, step.nextState().name());
            statement.setString(2, step.checkpoint().json());
            statement.setObject(3, at(now));
            statement.setString(4, current.id());
            statement.setLong(5, current.revision());
            statement.setLong(6, unit.sequence());
        });
        return find(connection, current.id()).orElseThrow();
    }

    ExtensionJob completeWithoutUnit(Connection connection, ExtensionJob current, Instant now) throws SQLException {
        return transition(connection, current, Set.of(ExecutionState.RUNNING), ExecutionState.COMPLETED, now);
    }

    ExtensionJob failUnit(
            Connection connection,
            ExtensionJob current,
            ExtensionJobUnit unit,
            ExtensionJobFailureEvidence evidence,
            CanonicalPayload result,
            Instant now)
            throws SQLException {
        requireActive(current, unit);
        updateUnitFailed(connection, unit, evidence, result, now);
        requireUpdate(connection, """
                UPDATE CORE.EXTENSION_JOB
                SET STATE = 'FAILED', REVISION = REVISION + 1, ACTIVE_UNIT_SEQUENCE = NULL,
                    ERROR_CODE = ?, UPDATED_AT = ?
                WHERE ID = ? AND REVISION = ? AND STATE = 'RUNNING' AND ACTIVE_UNIT_SEQUENCE = ?
                """, statement -> {
            statement.setString(1, evidence.errorCode());
            statement.setObject(2, at(now));
            statement.setString(3, current.id());
            statement.setLong(4, current.revision());
            statement.setLong(5, unit.sequence());
        });
        return find(connection, current.id()).orElseThrow();
    }

    ExtensionJob failWithoutUnit(Connection connection, ExtensionJob current, String errorCode, Instant now)
            throws SQLException {
        if (current.state() != ExecutionState.RUNNING) {
            throw PersistenceException.revisionConflict("Job 已不再运行");
        }
        requireNoActiveUnit(current);
        requireUpdate(connection, """
                UPDATE CORE.EXTENSION_JOB
                SET STATE = 'FAILED', REVISION = REVISION + 1, ERROR_CODE = ?, UPDATED_AT = ?
                WHERE ID = ? AND REVISION = ? AND STATE = 'RUNNING' AND ACTIVE_UNIT_SEQUENCE IS NULL
                """, statement -> {
            statement.setString(1, errorCode);
            statement.setObject(2, at(now));
            statement.setString(3, current.id());
            statement.setLong(4, current.revision());
        });
        return find(connection, current.id()).orElseThrow();
    }

    ExtensionJob finishInputWait(
            Connection connection,
            ExtensionJob current,
            ExecutionState terminalState,
            Optional<String> errorCode,
            Instant now)
            throws SQLException {
        Set<ExecutionState> inputWaitingStates = Set.of(
                ExecutionState.WAITING_INPUT, ExecutionState.PAUSED, ExecutionState.QUEUED, ExecutionState.RUNNING);
        if (!inputWaitingStates.contains(current.state())
                || (terminalState != ExecutionState.FAILED && terminalState != ExecutionState.CANCELLED)) {
            throw PersistenceException.revisionConflict("Job 已不再等待 InputRequest");
        }
        if ((terminalState == ExecutionState.FAILED) != errorCode.isPresent()) {
            throw new IllegalArgumentException("FAILED Job requires an error code");
        }
        requireNoActiveUnit(current);
        requireUpdate(connection, """
                UPDATE CORE.EXTENSION_JOB
                SET STATE = ?, REVISION = REVISION + 1, ERROR_CODE = ?, UPDATED_AT = ?
                WHERE ID = ? AND REVISION = ? AND STATE = ? AND ACTIVE_UNIT_SEQUENCE IS NULL
                """, statement -> {
            statement.setString(1, terminalState.name());
            statement.setString(2, errorCode.orElse(null));
            statement.setObject(3, at(now));
            statement.setString(4, current.id());
            statement.setLong(5, current.revision());
            statement.setString(6, current.state().name());
        });
        return find(connection, current.id()).orElseThrow();
    }

    private Optional<ExtensionJob> find(Connection connection, String id, boolean lock) throws SQLException {
        String suffix = lock ? " FOR UPDATE" : "";
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT * FROM CORE.EXTENSION_JOB WHERE ID = ?" + suffix)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapJob(result)) : Optional.empty();
            }
        }
    }

    private List<ExtensionJob> readJobs(PreparedStatement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery()) {
            List<ExtensionJob> jobs = new ArrayList<>();
            while (result.next()) {
                jobs.add(mapJob(result));
            }
            jobs.sort(Comparator.comparing(ExtensionJob::updatedAt).reversed().thenComparing(ExtensionJob::id));
            return List.copyOf(jobs);
        }
    }

    private void insertUnit(
            Connection connection, String jobId, long sequence, ExtensionJobWorkUnit workUnit, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.EXTENSION_JOB_UNIT (
                    JOB_ID, UNIT_SEQUENCE, UNIT_ID, INTENT, STATE, RESULT_PAYLOAD, CHECKPOINT,
                    TURN_ID, EFFECT_RECEIPT_KEY, ERROR_CODE, CREATED_AT, COMPLETED_AT
                ) VALUES (?, ?, ?, ?, 'INTENT_RECORDED', NULL, NULL, NULL, NULL, NULL, ?, NULL)
                """)) {
            statement.setString(1, jobId);
            statement.setLong(2, sequence);
            statement.setString(3, workUnit.unitId());
            statement.setString(4, workUnit.intent().json());
            statement.setObject(5, at(now));
            statement.executeUpdate();
        }
    }

    private void updateUnitCompleted(
            Connection connection, ExtensionJobUnit unit, ExtensionJobStepResult step, Instant now)
            throws SQLException {
        requireUpdate(connection, """
                UPDATE CORE.EXTENSION_JOB_UNIT
                SET STATE = 'COMPLETED', RESULT_PAYLOAD = ?, CHECKPOINT = ?, TURN_ID = ?,
                    EFFECT_RECEIPT_KEY = ?, ERROR_CODE = NULL, COMPLETED_AT = ?
                WHERE JOB_ID = ? AND UNIT_SEQUENCE = ? AND STATE = 'INTENT_RECORDED'
                """, statement -> {
            statement.setString(1, step.result().json());
            statement.setString(2, step.checkpoint().json());
            statement.setString(3, step.turnId().map(TurnId::toString).orElse(null));
            statement.setString(4, step.effectReceiptKey().orElse(null));
            statement.setObject(5, at(now));
            statement.setString(6, unit.jobId());
            statement.setLong(7, unit.sequence());
        });
    }

    private void updateUnitFailed(
            Connection connection,
            ExtensionJobUnit unit,
            ExtensionJobFailureEvidence evidence,
            CanonicalPayload result,
            Instant now)
            throws SQLException {
        requireUpdate(connection, """
                UPDATE CORE.EXTENSION_JOB_UNIT
                SET STATE = 'FAILED', RESULT_PAYLOAD = ?, CHECKPOINT = NULL, TURN_ID = ?,
                    EFFECT_RECEIPT_KEY = ?, ERROR_CODE = ?, COMPLETED_AT = ?
                WHERE JOB_ID = ? AND UNIT_SEQUENCE = ? AND STATE = 'INTENT_RECORDED'
                """, statement -> {
            statement.setString(1, result.json());
            statement.setString(2, evidence.turnId().map(TurnId::toString).orElse(null));
            statement.setString(3, evidence.effectReceiptKey().orElse(null));
            statement.setString(4, evidence.errorCode());
            statement.setObject(5, at(now));
            statement.setString(6, unit.jobId());
            statement.setLong(7, unit.sequence());
        });
    }

    private ExtensionJob mapJob(ResultSet result) throws SQLException {
        Long activeSequence = result.getObject("ACTIVE_UNIT_SEQUENCE", Long.class);
        String errorCode = result.getString("ERROR_CODE");
        return new ExtensionJob(
                result.getString("ID"),
                new ExtensionId(result.getString("EXTENSION_ID")),
                WorkspaceId.parse(result.getString("WORKSPACE_ID")),
                result.getString("JOB_TYPE"),
                result.getString("DEFINITION_ID"),
                result.getLong("DEFINITION_REVISION"),
                new CanonicalPayload(result.getString("FROZEN_INPUT")),
                ExecutionState.valueOf(result.getString("STATE")),
                result.getLong("REVISION"),
                new CanonicalPayload(result.getString("CHECKPOINT")),
                result.getLong("NEXT_UNIT_SEQUENCE"),
                Optional.ofNullable(activeSequence),
                Optional.ofNullable(errorCode),
                instant(result, "CREATED_AT"),
                instant(result, "UPDATED_AT"));
    }

    private ExtensionJobUnit mapUnit(ResultSet result) throws SQLException {
        String payload = result.getString("RESULT_PAYLOAD");
        String checkpoint = result.getString("CHECKPOINT");
        String turnId = result.getString("TURN_ID");
        String receipt = result.getString("EFFECT_RECEIPT_KEY");
        String error = result.getString("ERROR_CODE");
        OffsetDateTime completedAt = result.getObject("COMPLETED_AT", OffsetDateTime.class);
        return new ExtensionJobUnit(
                result.getString("JOB_ID"),
                result.getLong("UNIT_SEQUENCE"),
                result.getString("UNIT_ID"),
                new CanonicalPayload(result.getString("INTENT")),
                ExtensionJobUnitState.valueOf(result.getString("STATE")),
                payload == null ? Optional.empty() : Optional.of(new CanonicalPayload(payload)),
                checkpoint == null ? Optional.empty() : Optional.of(new CanonicalPayload(checkpoint)),
                turnId == null ? Optional.empty() : Optional.of(TurnId.parse(turnId)),
                Optional.ofNullable(receipt),
                Optional.ofNullable(error),
                instant(result, "CREATED_AT"),
                completedAt == null ? Optional.empty() : Optional.of(completedAt.toInstant()));
    }

    private static void requireActive(ExtensionJob job, ExtensionJobUnit unit) {
        if (!job.id().equals(unit.jobId())
                || job.activeUnitSequence().orElse(-1L) != unit.sequence()
                || unit.state() != ExtensionJobUnitState.INTENT_RECORDED) {
            throw PersistenceException.revisionConflict("工作单元已不再活动");
        }
    }

    private static void requireNoActiveUnit(ExtensionJob job) {
        if (job.activeUnitSequence().isPresent()) {
            throw PersistenceException.invalidRequest("活动工作单元结束前不能修改 Job 状态");
        }
    }

    private static void requireUpdate(Connection connection, String sql, StatementBinder binder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            if (statement.executeUpdate() != 1) {
                throw PersistenceException.revisionConflict("Job revision 或状态已经改变");
            }
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toInstant();
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
