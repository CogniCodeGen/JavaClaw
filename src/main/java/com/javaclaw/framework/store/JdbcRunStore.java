package com.javaclaw.framework.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Transactional H2 run/event/outbox implementation. */
public final class JdbcRunStore implements RunStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Clock clock;

    public JdbcRunStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper json,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CreateRunResult create(
            RunId id,
            RunRequest request,
            String executionPlanId,
            RunEventDraft createdEvent) {
        try {
            return transactions.execute(status -> {
                if (request.idempotencyKey() != null) {
                    Optional<StoredRun> existing = findByIdempotencyKey(
                            request.scope().workspaceId(), request.idempotencyKey());
                    if (existing.isPresent()) return new CreateRunResult(existing.get(), false);
                }
                long now = clock.millis();
                jdbc.update("""
                        INSERT INTO agent_runs(
                            run_id, workspace_id, user_id, session_id, idempotency_key,
                            request_json, execution_plan_id, state, last_sequence,
                            output_json, error_text, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, NULL, NULL, 1, ?, ?)
                        """, id.value(), request.scope().workspaceId(), request.scope().userId(),
                        request.scope().sessionId(), request.idempotencyKey(), write(request),
                        executionPlanId, RunState.CREATED.name(), now, now);
                RunEventEnvelope envelope = envelope(id, 1, now, createdEvent);
                insertEventAndOutbox(envelope);
                return new CreateRunResult(new StoredRun(
                        new RunSnapshot(id, RunState.CREATED, executionPlanId, 1,
                                Instant.ofEpochMilli(now), Instant.ofEpochMilli(now),
                                null, null, 1), request), true);
            });
        } catch (DuplicateKeyException race) {
            if (request.idempotencyKey() != null) {
                return findByIdempotencyKey(request.scope().workspaceId(), request.idempotencyKey())
                        .map(run -> new CreateRunResult(run, false)).orElseThrow(() -> race);
            }
            throw race;
        }
    }

    @Override
    public Optional<StoredRun> find(RunId id) {
        return jdbc.query("SELECT * FROM agent_runs WHERE run_id = ?",
                this::readStoredRun, id.value()).stream().findFirst();
    }

    @Override
    public Optional<StoredRun> findByIdempotencyKey(String workspaceId, String idempotencyKey) {
        if (idempotencyKey == null) return Optional.empty();
        return jdbc.query("""
                        SELECT * FROM agent_runs
                        WHERE workspace_id = ? AND idempotency_key = ?
                        """, this::readStoredRun, workspaceId, idempotencyKey)
                .stream().findFirst();
    }

    @Override
    public List<StoredRun> nonTerminalRuns() {
        return jdbc.query("""
                SELECT * FROM agent_runs
                WHERE state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                ORDER BY created_at, run_id
                """, this::readStoredRun);
    }

    @Override
    public List<RunEventEnvelope> eventsAfter(RunId id, long afterSequence) {
        return jdbc.query("""
                SELECT * FROM agent_run_events
                WHERE run_id = ? AND event_sequence > ?
                ORDER BY event_sequence
                """, this::readEvent, id.value(), afterSequence);
    }

    @Override
    public Optional<RunEventEnvelope> append(
            RunId id,
            Set<RunState> expectedStates,
            RunState nextState,
            RunEventDraft event,
            JsonNode output,
            String error) {
        return Optional.ofNullable(transactions.execute(status -> {
            List<LockedRun> rows = jdbc.query("""
                    SELECT state, last_sequence, version
                    FROM agent_runs WHERE run_id = ? FOR UPDATE
                    """, (row, index) -> new LockedRun(
                    RunState.valueOf(row.getString("state")),
                    row.getLong("last_sequence"), row.getLong("version")), id.value());
            if (rows.isEmpty()) return null;
            LockedRun current = rows.getFirst();
            if (current.state().terminal() || !expectedStates.contains(current.state())) return null;
            long nextSequence = current.lastSequence() + 1;
            long now = clock.millis();
            int changed = jdbc.update("""
                    UPDATE agent_runs
                    SET state = ?, last_sequence = ?,
                        output_json = COALESCE(?, output_json),
                        error_text = COALESCE(?, error_text),
                        version = version + 1, updated_at = ?
                    WHERE run_id = ? AND version = ?
                    """, nextState.name(), nextSequence,
                    output == null ? null : write(output), error,
                    now, id.value(), current.version());
            if (changed != 1) {
                throw new IllegalStateException("concurrent run mutation: " + id);
            }
            RunEventEnvelope envelope = envelope(id, nextSequence, now, event);
            insertEventAndOutbox(envelope);
            return envelope;
        }));
    }

    private void insertEventAndOutbox(RunEventEnvelope envelope) {
        jdbc.update("""
                INSERT INTO agent_run_events(
                    run_id, event_sequence, timestamp_ms, type, schema_version,
                    producer, correlation_id, causation_id, payload_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, envelope.runId(), envelope.sequence(), envelope.timestamp().toEpochMilli(),
                envelope.type(), envelope.schemaVersion(), envelope.producer(),
                envelope.correlationId(), envelope.causationId(), write(envelope.payload()));
        jdbc.update("""
                INSERT INTO agent_run_outbox(
                    run_id, event_sequence, event_type, envelope_json, created_at, published_at)
                VALUES (?, ?, ?, ?, ?, NULL)
                """, envelope.runId(), envelope.sequence(), envelope.type(),
                write(envelope), envelope.timestamp().toEpochMilli());
    }

    private RunEventEnvelope envelope(RunId id, long sequence, long timestamp, RunEventDraft draft) {
        return new RunEventEnvelope(id.value(), sequence, Instant.ofEpochMilli(timestamp),
                draft.type(), draft.schemaVersion(), draft.producer(), draft.correlationId(),
                draft.causationId(), draft.payload());
    }

    private StoredRun readStoredRun(ResultSet row, int index) throws SQLException {
        RunId id = new RunId(row.getString("run_id"));
        String output = row.getString("output_json");
        return new StoredRun(new RunSnapshot(
                id, RunState.valueOf(row.getString("state")),
                row.getString("execution_plan_id"), row.getLong("last_sequence"),
                Instant.ofEpochMilli(row.getLong("created_at")),
                Instant.ofEpochMilli(row.getLong("updated_at")),
                output == null ? null : readTree(output), row.getString("error_text"),
                row.getLong("version")), read(row.getString("request_json"), RunRequest.class));
    }

    private RunEventEnvelope readEvent(ResultSet row, int index) throws SQLException {
        return new RunEventEnvelope(row.getString("run_id"), row.getLong("event_sequence"),
                Instant.ofEpochMilli(row.getLong("timestamp_ms")), row.getString("type"),
                row.getInt("schema_version"), row.getString("producer"),
                row.getString("correlation_id"), row.getString("causation_id"),
                readTree(row.getString("payload_json")));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot serialize run data", failure);
        }
    }

    private JsonNode readTree(String value) {
        try {
            return json.readTree(value);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot deserialize run JSON", failure);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot deserialize " + type.getSimpleName(), failure);
        }
    }

    private record LockedRun(RunState state, long lastSequence, long version) {}
}
