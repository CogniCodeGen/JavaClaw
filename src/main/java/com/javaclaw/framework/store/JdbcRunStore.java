package com.javaclaw.framework.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.core.BusinessEffectCheckpointVerifier;
import com.javaclaw.framework.core.InteractionStageVerifier;
import com.javaclaw.framework.core.TaskResultEvaluator;
import com.javaclaw.framework.core.TrustedCapabilityRegistry;
import com.javaclaw.framework.core.DesktopObservationBaseline;
import com.javaclaw.framework.core.PendingEffectObservationRequiredException;
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
import java.util.function.Supplier;

/** Transactional H2 run/event/outbox implementation. */
public final class JdbcRunStore implements RunStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Clock clock;
    private final JdbcThreadStore threads;
    private final RunAcceptanceFence acceptance;
    private final JdbcRunHistory history;

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
        this.threads = new JdbcThreadStore(jdbc, transactionManager, json, clock);
        this.acceptance = new RunAcceptanceFence(jdbc, transactions, threads);
        this.history = new JdbcRunHistory(jdbc, this::readStoredRun);
    }

    public JdbcThreadStore threads() { return threads; }

    @Override public boolean readable(RunScope scope) {
        return threads.find(scope).map(thread -> thread.status() == ThreadStatus.ACTIVE
                || thread.status() == ThreadStatus.ARCHIVED).orElse(false);
    }

    @Override public RunRequest prepare(RunRequest request) {
        return new ThreadRequestPolicy(threads, this).prepare(request);
    }

    @Override public <T> T withRunAcceptanceLock(RunId id, Supplier<T> work) {
        return acceptance.withLock(id, work);
    }

    @Override
    public CreateRunResult create(
            RunId id,
            RunRequest request,
            String executionPlanId,
            RunEventDraft createdEvent) {
        rejectVerifierOnlyEvent(createdEvent.type());
        try {
            return transactions.execute(status -> {
                if (request.idempotencyKey() != null) {
                    Optional<StoredRun> existing = findByIdempotencyKey(request.scope(), request.idempotencyKey());
                    if (existing.isPresent()) return new CreateRunResult(existing.get(), false);
                }
                long now = clock.millis();
                if (threads.find(request.scope()).isEmpty()) prepare(request);
                if (threads.lock(request.scope()).status() != ThreadStatus.ACTIVE)
                    throw new IllegalStateException("thread is not active");
                acceptance.requireParent(request);
                ThreadConfiguration config = threads.require(request.scope()).configuration();
                String model = request.attributes().getOrDefault("framework.modelPolicyRef",
                        com.fasterxml.jackson.databind.node.TextNode.valueOf("")).asText();
                if (config.modelPolicyRef().isBlank() && !model.isBlank()) threads.configure(request.scope(),
                        new ThreadConfiguration(model, config.workingDirectory(), config.sandboxPolicy(), config.permissions(),
                                config.budget(), config.projectInstructions()));
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
                threads.appendRun(request.scope(), envelope, request);
                return new CreateRunResult(new StoredRun(
                        new RunSnapshot(id, RunState.CREATED, executionPlanId, 1,
                                Instant.ofEpochMilli(now), Instant.ofEpochMilli(now),
                                null, null, 1), request), true);
            });
        } catch (DuplicateKeyException race) {
            if (request.idempotencyKey() != null) {
                return findByIdempotencyKey(request.scope(), request.idempotencyKey())
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

    @Override public List<StoredRun> childRuns(RunId parentId) {
        return history.childRuns(parentId);
    }

    @Override public List<StoredRun> scopeRuns(RunScope scope) {
        return history.scopeRuns(scope);
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

    @Override public Optional<StoredRun> findByIdempotencyKey(RunScope scope, String key) {
        if (key == null) return Optional.empty();
        return jdbc.query("SELECT * FROM agent_runs WHERE workspace_id=? AND user_id=? AND session_id=? AND idempotency_key=?",
                this::readStoredRun, scope.workspaceId(), scope.userId(), scope.sessionId(), key).stream().findFirst();
    }

    @Override public boolean claim(RunId id) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            StoredRun run = find(id).orElseThrow();
            RunScope scope = run.request().scope();
            if (threads.lock(scope).status() != ThreadStatus.ACTIVE) return false;
            if (find(id).orElseThrow().snapshot().state().terminal()) return false;
            String current = jdbc.queryForObject("SELECT active_turn_id FROM agent_threads WHERE workspace_id=? AND user_id=? AND thread_id=?",
                    String.class, scope.workspaceId(), scope.userId(), scope.sessionId());
            if (current != null && !current.equals(id.value())) return false;
            if (current == null && !nextWaiting(scope).map(next -> next.snapshot().id().equals(id)).orElse(false)) return false;
            jdbc.update("UPDATE agent_threads SET active_turn_id=? WHERE workspace_id=? AND user_id=? AND thread_id=?",
                    id.value(), scope.workspaceId(), scope.userId(), scope.sessionId());
            return true;
        }));
    }

    @Override public Optional<RunId> release(RunId id) {
        return transactions.execute(status -> {
            StoredRun run = find(id).orElse(null);
            if (run == null) return Optional.empty();
            RunScope scope = run.request().scope();
            ThreadSnapshot thread = threads.lock(scope);
            int released = jdbc.update("UPDATE agent_threads SET active_turn_id=NULL WHERE workspace_id=? AND user_id=? AND thread_id=? AND active_turn_id=?",
                    scope.workspaceId(), scope.userId(), scope.sessionId(), id.value());
            if (released == 0) return Optional.empty();
            if (thread.status() != ThreadStatus.ACTIVE) return Optional.empty();
            Optional<StoredRun> next = nextWaiting(scope);
            next.ifPresent(value -> jdbc.update("UPDATE agent_threads SET active_turn_id=? WHERE workspace_id=? AND user_id=? AND thread_id=?",
                    value.snapshot().id().value(), scope.workspaceId(), scope.userId(), scope.sessionId()));
            return next.filter(value -> value.snapshot().state() == RunState.CREATED).map(value -> value.snapshot().id());
        });
    }

    /** Thread journal sequence breaks millisecond timestamp ties in actual enqueue order. */
    private Optional<StoredRun> nextWaiting(RunScope scope) {
        return jdbc.query("SELECT r.* FROM agent_runs r WHERE r.workspace_id=? AND r.user_id=? AND r.session_id=? "
                        + "AND r.state NOT IN ('COMPLETED','FAILED','CANCELLED') ORDER BY "
                        + "(SELECT MIN(e.event_sequence) FROM agent_thread_events e WHERE e.workspace_id=r.workspace_id "
                        + "AND e.user_id=r.user_id AND e.thread_id=r.session_id AND e.turn_id=r.run_id AND e.type='turn/created') "
                        + "NULLS LAST,r.created_at,r.run_id FETCH FIRST 1 ROW ONLY",
                this::readStoredRun, scope.workspaceId(), scope.userId(), scope.sessionId()).stream().findFirst();
    }

    @Override public void recoverClaims() {
        var scopes = jdbc.query("SELECT workspace_id,user_id,thread_id FROM agent_threads WHERE status='ACTIVE'",
                (row, index) -> new RunScope(row.getString(1), row.getString(2), row.getString(3)));
        for (RunScope scope : scopes) transactions.executeWithoutResult(status -> {
            threads.lock(scope);
            String current = jdbc.queryForObject("SELECT active_turn_id FROM agent_threads WHERE workspace_id=? AND user_id=? AND thread_id=?",
                    String.class, scope.workspaceId(), scope.userId(), scope.sessionId());
            boolean valid = current != null && find(new RunId(current)).filter(run -> run.request().scope().equals(scope)
                    && !run.snapshot().state().terminal()).isPresent();
            if (!valid) jdbc.update("UPDATE agent_threads SET active_turn_id=? WHERE workspace_id=? AND user_id=? AND thread_id=?",
                    nextWaiting(scope).map(run -> run.snapshot().id().value()).orElse(null),
                    scope.workspaceId(), scope.userId(), scope.sessionId());
        });
    }

    @Override
    public List<StoredRun> nonTerminalRuns() {
        return history.nonTerminalRuns();
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
        rejectVerifierOnlyEvent(event.type());
        return Optional.ofNullable(transactions.execute(status -> {
            StoredRun stored = find(id).orElse(null);
            if (stored == null) return null;
            ThreadSnapshot thread = threads.lock(stored.request().scope());
            boolean deleting = thread.status() == ThreadStatus.DELETING || thread.status() == ThreadStatus.DELETED;
            if (deleting && nextState != RunState.CANCELLED) return null;
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
            if (!deleting) threads.appendRun(stored.request().scope(), envelope, null);
            return envelope;
        }));
    }

    @Override
    public Optional<List<RunEventEnvelope>> appendBatch(
            RunId id, Set<RunState> expectedStates, RunState nextState,
            List<RunEventDraft> events) {
        Objects.requireNonNull(events, "events");
        if (events.isEmpty()) throw new IllegalArgumentException("batch must not be empty");
        events.forEach(event -> rejectVerifierOnlyEvent(event.type()));
        return Optional.ofNullable(transactions.execute(status -> {
            StoredRun stored = find(id).orElse(null);
            if (stored == null) return null;
            ThreadSnapshot thread = threads.lock(stored.request().scope());
            if (thread.status() != ThreadStatus.ACTIVE) return null;
            List<LockedRun> rows = jdbc.query("SELECT state,last_sequence,version FROM agent_runs WHERE run_id=? FOR UPDATE",
                    (row, index) -> new LockedRun(RunState.valueOf(row.getString("state")),
                            row.getLong("last_sequence"), row.getLong("version")), id.value());
            if (rows.isEmpty()) return null;
            LockedRun current = rows.getFirst();
            if (current.state().terminal() || !expectedStates.contains(current.state())) return null;
            for (RunEventDraft draft : events) {
                if (draft.type().equals("core.tool.started"))
                    requireFreshToolReservation(id, draft.payload());
            }
            long now = clock.millis();
            long finalSequence = Math.addExact(current.lastSequence(), events.size());
            if (jdbc.update("UPDATE agent_runs SET state=?,last_sequence=?,version=version+1,updated_at=? WHERE run_id=? AND version=?",
                    nextState.name(), finalSequence, now, id.value(), current.version()) != 1)
                throw new IllegalStateException("concurrent run mutation: " + id);
            List<RunEventEnvelope> appended = new java.util.ArrayList<>(events.size());
            long sequence = current.lastSequence();
            for (RunEventDraft draft : events) {
                RunEventEnvelope envelope = envelope(id, ++sequence, now, draft);
                insertEventAndOutbox(envelope);
                threads.appendRun(stored.request().scope(), envelope, null);
                appended.add(envelope);
            }
            return List.copyOf(appended);
        }));
    }

    @Override
    public Optional<RunEventEnvelope> reconcileEffect(RunId id, EffectReconciliationV1 proof) {
        return reconcileEffectInternal(id, proof, null);
    }

    @Override
    public Optional<RunEventEnvelope> reconcileTerminalEffect(
            RunId recoveryRunId, RunId sourceRunId, EffectReconciliationV1 proof) {
        Objects.requireNonNull(recoveryRunId, "recoveryRunId");
        if (recoveryRunId.equals(sourceRunId)) return Optional.empty();
        return reconcileEffectInternal(sourceRunId, proof, recoveryRunId);
    }

    private Optional<RunEventEnvelope> reconcileEffectInternal(
            RunId id, EffectReconciliationV1 proof, RunId recoveryRunId) {
        Objects.requireNonNull(proof, "proof");
        return Optional.ofNullable(transactions.execute(status -> {
            StoredRun stored = find(id).orElse(null);
            if (stored == null) return null;
            ThreadSnapshot thread = threads.lock(stored.request().scope());
            if (thread.status() != ThreadStatus.ACTIVE) return null;
            StoredRun recovery = recoveryRunId == null ? null : find(recoveryRunId).orElse(null);
            if (recoveryRunId != null && (recovery == null || recovery.snapshot().state().terminal()
                    || !recovery.request().scope().equals(stored.request().scope())
                    || !readable(recovery.request().scope()))) return null;
            List<LockedRun> rows = jdbc.query(
                    "SELECT state,last_sequence,version FROM agent_runs WHERE run_id=? FOR UPDATE",
                    (row, index) -> new LockedRun(RunState.valueOf(row.getString("state")),
                            row.getLong("last_sequence"), row.getLong("version")), id.value());
            if (rows.isEmpty()) return null;
            List<RunEventEnvelope> events = eventsAfter(id, 0);
            if (recoveryRunId == null) {
                if (rows.getFirst().state() != RunState.RUNNING) return null;
            } else if (!rows.getFirst().state().terminal()
                    || !sourceTerminatedBeforeRecovery(events, recovery.snapshot().createdAt())
                    || !strictSourceActionProof(events, proof)) return null;
            if (!validReconciliationEvidence(events, proof)) return null;
            LockedRun current = rows.getFirst();
            long now = clock.millis();
            long next = current.lastSequence() + 1;
            if (jdbc.update("UPDATE agent_runs SET last_sequence=?,version=version+1,updated_at=? WHERE run_id=? AND version=?",
                    next, now, id.value(), current.version()) != 1)
                throw new IllegalStateException("concurrent effect reconciliation: " + id);
            var payload = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            payload.put("actionInvocationId", proof.actionInvocationId());
            payload.put("sessionId", proof.sessionId());
            payload.put("targetId", proof.targetId());
            payload.put("actionObservationId", proof.actionObservationId());
            payload.put("evidenceObservationId", proof.evidenceObservationId());
            payload.put("outcome", "SATISFIED");
            if (recoveryRunId != null) payload.put("recoveryRunId", recoveryRunId.value());
            RunEventEnvelope value = envelope(id, next, now, new RunEventDraft(
                    "core.effect.reconciled", 1, "framework.core",
                    stored.request().linkage().correlationId(), proof.actionInvocationId(), payload));
            insertEventAndOutbox(value);
            threads.appendRun(stored.request().scope(), value, null);
            return value;
        }));
    }

    private static boolean sourceTerminatedBeforeRecovery(List<RunEventEnvelope> events,
            Instant recoveryCreatedAt) {
        return events.stream().filter(event -> event.schemaVersion() == 1
                        && event.producer().equals("framework.core")
                        && Set.of("core.run.completed", "core.run.failed", "core.run.cancelled")
                                .contains(event.type()))
                .anyMatch(event -> !event.timestamp().isAfter(recoveryCreatedAt));
    }

    private boolean strictSourceActionProof(List<RunEventEnvelope> events, EffectReconciliationV1 proof) {
        try {
            TaskContractV3 source = TaskResultEvaluator.latestContractV3(events, json).orElse(null);
            TaskContractV2 contract = source == null
                    ? TaskResultEvaluator.latestContractV2(events, json).orElse(null)
                    : TaskResultEvaluator.desktopContract(source, TrustedCapabilityRegistry.builtins());
            if (contract == null || !contract.applicable() || !contract.reliable()) return false;
            if (TaskResultEvaluator.verifiedActionEvidence(contract, events).stream()
                    .anyMatch(candidate -> matchesEffect(candidate, proof))) return true;
            return TaskResultEvaluator.verifiedCheckpointEvidence(contract, events).stream()
                    .filter(candidate -> matchesEffect(candidate.proof(), proof))
                    .anyMatch(candidate -> events.stream().anyMatch(event ->
                            event.type().equals("core.task.checkpoint_verified") && event.schemaVersion() == 1
                                    && event.producer().equals("framework.core")
                                    && checkpointMatches(event.payload(), new EffectCheckpointV1(proof,
                                            event.payload().path("contractSequence").asLong(),
                                            candidate.clickCriterionId(), candidate.viewCriterionId(),
                                            candidate.requiredSubject(), candidate.observationEvidenceRef()))));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean matchesEffect(TaskResultEvaluator.VerifiedActionEvidence candidate,
            EffectReconciliationV1 proof) {
        return candidate.invocationId().equals(proof.actionInvocationId())
                && candidate.sessionId().equals(proof.sessionId())
                && candidate.targetId().equals(proof.targetId())
                && candidate.actionObservationId().equals(proof.actionObservationId())
                && candidate.evidenceObservationId().equals(proof.evidenceObservationId());
    }

    @Override
    public Optional<RunEventEnvelope> verifyEffectCheckpoint(RunId id, EffectCheckpointV1 checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        return Optional.ofNullable(transactions.execute(status -> {
            StoredRun stored = find(id).orElse(null);
            if (stored == null) return null;
            ThreadSnapshot thread = threads.lock(stored.request().scope());
            if (thread.status() != ThreadStatus.ACTIVE) return null;
            List<LockedRun> rows = jdbc.query(
                    "SELECT state,last_sequence,version FROM agent_runs WHERE run_id=? FOR UPDATE",
                    (row, index) -> new LockedRun(RunState.valueOf(row.getString("state")),
                            row.getLong("last_sequence"), row.getLong("version")), id.value());
            if (rows.isEmpty() || rows.getFirst().state() != RunState.RUNNING) return null;
            List<RunEventEnvelope> events = eventsAfter(id, 0);
            for (RunEventEnvelope event : events) {
                if (!event.type().equals("core.task.checkpoint_verified")) continue;
                if (event.payload().path("actionInvocationId").asText("")
                        .equals(checkpoint.effect().actionInvocationId())) {
                    return checkpointMatches(event.payload(), checkpoint) ? event : null;
                }
            }
            if (!validCheckpointEvidence(events, checkpoint)) return null;
            LockedRun current = rows.getFirst();
            long now = clock.millis();
            long next = current.lastSequence() + 1;
            if (jdbc.update("UPDATE agent_runs SET last_sequence=?,version=version+1,updated_at=? WHERE run_id=? AND version=?",
                    next, now, id.value(), current.version()) != 1)
                throw new IllegalStateException("concurrent effect checkpoint: " + id);
            EffectReconciliationV1 effect = checkpoint.effect();
            var payload = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            payload.put("actionInvocationId", effect.actionInvocationId());
            payload.put("sessionId", effect.sessionId());
            payload.put("targetId", effect.targetId());
            payload.put("actionObservationId", effect.actionObservationId());
            payload.put("evidenceObservationId", effect.evidenceObservationId());
            payload.put("contractSequence", checkpoint.contractSequence());
            payload.put("clickCriterionId", checkpoint.clickCriterionId());
            payload.put("viewCriterionId", checkpoint.viewCriterionId());
            payload.put("requiredSubject", checkpoint.requiredSubject());
            payload.put("observationEvidenceRef", checkpoint.observationEvidenceRef());
            payload.put("outcome", "SATISFIED");
            RunEventEnvelope value = envelope(id, next, now, new RunEventDraft(
                    "core.task.checkpoint_verified", 1, "framework.core",
                    stored.request().linkage().correlationId(), effect.actionInvocationId(), payload));
            insertEventAndOutbox(value);
            threads.appendRun(stored.request().scope(), value, null);
            return value;
        }));
    }

    @Override
    public Optional<RunEventEnvelope> verifyBusinessEffectCheckpoint(RunId id, EffectCheckpointV1 checkpoint) {
        Objects.requireNonNull(id, "sourceRunId");
        Objects.requireNonNull(checkpoint, "checkpoint");
        return Optional.ofNullable(transactions.execute(status -> {
            StoredRun stored = find(id).orElse(null);
            if (stored == null) return null;
            if (threads.lock(stored.request().scope()).status() != ThreadStatus.ACTIVE) return null;
            List<LockedRun> rows = jdbc.query(
                    "SELECT state,last_sequence,version FROM agent_runs WHERE run_id=? FOR UPDATE",
                    (row, index) -> new LockedRun(RunState.valueOf(row.getString("state")),
                            row.getLong("last_sequence"), row.getLong("version")), id.value());
            if (rows.isEmpty()) return null;
            // Re-read only the source Run after acquiring its sequence fence. A late original
            // receipt may settle a terminal Run, but another Run cannot supply this proof.
            List<RunEventEnvelope> events = eventsAfter(id, 0);
            for (RunEventEnvelope event : events) {
                if (!event.type().equals(BusinessEffectCheckpointVerifier.EVENT_TYPE)
                        || event.schemaVersion() != 1 || !event.producer().equals("framework.core")
                        || !event.payload().path("actionInvocationId").asText("")
                                .equals(checkpoint.effect().actionInvocationId())) continue;
                return event.payload().path("sourceRunId").asText("").equals(id.value())
                        && checkpointMatches(event.payload(), checkpoint) ? event : null;
            }
            if (!BusinessEffectCheckpointVerifier.verifies(id, events, checkpoint, json)) return null;
            LockedRun current = rows.getFirst();
            long now = clock.millis(), next = current.lastSequence() + 1;
            // Preserve state, output and error, including an already terminal source Run.
            if (jdbc.update("UPDATE agent_runs SET last_sequence=?,version=version+1,updated_at=? WHERE run_id=? AND version=?",
                    next, now, id.value(), current.version()) != 1)
                throw new IllegalStateException("concurrent business effect checkpoint: " + id);
            EffectReconciliationV1 effect = checkpoint.effect();
            var payload = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            payload.put("sourceRunId", id.value());
            payload.put("actionInvocationId", effect.actionInvocationId());
            payload.put("sessionId", effect.sessionId());
            payload.put("targetId", effect.targetId());
            payload.put("actionObservationId", effect.actionObservationId());
            payload.put("evidenceObservationId", effect.evidenceObservationId());
            payload.put("contractSequence", checkpoint.contractSequence());
            payload.put("clickCriterionId", checkpoint.clickCriterionId());
            payload.put("viewCriterionId", checkpoint.viewCriterionId());
            payload.put("requiredSubject", checkpoint.requiredSubject());
            payload.put("observationEvidenceRef", checkpoint.observationEvidenceRef());
            payload.put("outcome", "SATISFIED");
            RunEventEnvelope value = envelope(id, next, now, new RunEventDraft(
                    BusinessEffectCheckpointVerifier.EVENT_TYPE, 1, "framework.core",
                    stored.request().linkage().correlationId(), effect.actionInvocationId(), payload));
            insertEventAndOutbox(value);
            threads.appendRun(stored.request().scope(), value, null);
            return value;
        }));
    }

    @Override
    public Optional<RunEventEnvelope> verifyInteractionStage(RunId id, InteractionStageProofV1 proof) {
        Objects.requireNonNull(id, "sourceRunId");
        Objects.requireNonNull(proof, "proof");
        return Optional.ofNullable(transactions.execute(status -> {
            StoredRun stored = find(id).orElse(null);
            if (stored == null || !com.javaclaw.framework.core.InteractionExecutionPolicy.isInteraction(stored.request())
                    || threads.lock(stored.request().scope()).status() != ThreadStatus.ACTIVE) return null;
            List<LockedRun> rows = jdbc.query("SELECT state,last_sequence,version FROM agent_runs WHERE run_id=? FOR UPDATE",
                    (row, index) -> new LockedRun(RunState.valueOf(row.getString("state")), row.getLong("last_sequence"),
                            row.getLong("version")), id.value());
            if (rows.isEmpty()) return null;
            List<RunEventEnvelope> events = eventsAfter(id, 0);
            JsonNode encoded = json.valueToTree(proof);
            for (var event : events) if (event.type().equals(InteractionStageVerifier.EVENT_TYPE)
                    && event.schemaVersion() == 1 && event.producer().equals("framework.core")
                    && event.payload().path("sourceRunId").asText().equals(id.value())
                    && event.payload().path("proof").equals(encoded)
                    && event.payload().path("outcome").asText().equals("SATISFIED")) return event;
            if (!InteractionStageVerifier.verifies(id, events, proof, json)) return null;
            LockedRun current = rows.getFirst();
            long now = clock.millis(), next = current.lastSequence() + 1;
            if (jdbc.update("UPDATE agent_runs SET last_sequence=?,version=version+1,updated_at=? WHERE run_id=? AND version=?",
                    next, now, id.value(), current.version()) != 1) throw new IllegalStateException("concurrent interaction stage: " + id);
            var payload = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("sourceRunId", id.value()).put("outcome", "SATISFIED");
            payload.set("proof", encoded);
            RunEventEnvelope value = envelope(id, next, now, new RunEventDraft(InteractionStageVerifier.EVENT_TYPE, 1,
                    "framework.core", stored.request().linkage().correlationId(), proof.afterInvocationId(), payload));
            insertEventAndOutbox(value);
            threads.appendRun(stored.request().scope(), value, null);
            return value;
        }));
    }

    private boolean validCheckpointEvidence(List<RunEventEnvelope> events,
                                            EffectCheckpointV1 checkpoint) {
        EffectReconciliationV1 effect = checkpoint.effect();
        RunEventEnvelope started = null;
        RunEventEnvelope action = null;
        RunEventEnvelope observed = null;
        RunEventEnvelope frozen = null;
        for (RunEventEnvelope event : events) {
            if (event.type().equals("core.tool.started")
                    && event.producer().equals("framework.core")
                    && event.payload().path("invocationId").asText("")
                            .equals(effect.actionInvocationId())) started = event;
            if (event.type().equals("core.tool.receipt")
                    && event.producer().equals("framework.core")) {
                if (event.payload().path("invocationId").asText("")
                        .equals(effect.actionInvocationId())) action = event;
                if (event.payload().path("evidenceRef").asText("")
                        .equals(checkpoint.observationEvidenceRef())
                        && event.payload().path("metadata").path("observationId")
                                .asText("").equals(effect.evidenceObservationId())) observed = event;
            }
        }
        if (started == null || action == null || observed == null
                || started.sequence() >= action.sequence()
                || action.sequence() >= observed.sequence()
                || !isObservationGated(started.payload())
                || !started.payload().path("tool").asText("").equals("desktop_session_click")
                || !started.payload().path("resourceKey").asText("")
                        .equals("desktop:" + effect.targetId())) return false;
        for (RunEventEnvelope event : events) {
            if (event.sequence() >= started.sequence()) break;
            if ((event.type().equals("core.task.contract")
                    || event.type().equals("core.task.contract_revised"))
                    && (event.schemaVersion() == 2 || event.schemaVersion() == 3)
                    && event.producer().equals("framework.core")) frozen = event;
        }
        if (frozen == null || frozen.sequence() != checkpoint.contractSequence()) return false;
        long startedSequence = started.sequence();
        if (events.stream().anyMatch(event -> event.sequence() > startedSequence
                && (event.type().equals("core.task.contract")
                        || event.type().equals("core.task.contract_revised"))
                && (event.schemaVersion() == 2 || event.schemaVersion() == 3)
                && event.producer().equals("framework.core"))) return false;
        try {
            TaskContractV2 contract;
            if (frozen.schemaVersion() == 3) {
                TaskContractV3 source = json.treeToValue(frozen.payload(), TaskContractV3.class);
                TrustedCapabilityRegistry capabilities = TrustedCapabilityRegistry.builtins();
                if (!source.applicable() || !source.reliable()
                        || source.criteria().stream().anyMatch(c -> !capabilities.supports(c))) return false;
                int clickIndex = -1;
                for (int index = 0; index < source.criteria().size(); index++) {
                    if (source.criteria().get(index).id().equals(checkpoint.clickCriterionId())) {
                        clickIndex = index;
                        break;
                    }
                }
                if (clickIndex < 0) return false;
                if (clickIndex > 0) {
                    TaskContractV3 prefix = new TaskContractV3(3, source.originalRequest(),
                            source.criteria().subList(0, clickIndex), true, true, source.source(),
                            source.reasonCodes(), source.unresolvedInputs(), source.desktopObservationPolicy());
                    long actionSequence = action.sequence();
                    List<RunEventEnvelope> beforeAction = events.stream()
                            .filter(event -> event.sequence() < actionSequence).toList();
                    if (TaskResultEvaluator.evaluateV3(prefix, beforeAction, "", capabilities)
                            .outcome() != TaskOutcome.VERIFIED_COMPLETE) return false;
                }
                contract = TaskResultEvaluator.desktopContract(source, capabilities);
            } else {
                contract = json.treeToValue(frozen.payload(), TaskContractV2.class);
            }
            if (!contract.applicable() || !contract.reliable()) return false;
            long observedSequence = observed.sequence();
            List<RunEventEnvelope> prefix = events.stream()
                    .filter(event -> event.sequence() <= observedSequence).toList();
            return TaskResultEvaluator.verifiedCheckpointEvidence(contract, prefix).stream()
                    .anyMatch(candidate -> candidate.proof().invocationId()
                                    .equals(effect.actionInvocationId())
                            && candidate.proof().sessionId().equals(effect.sessionId())
                            && candidate.proof().targetId().equals(effect.targetId())
                            && candidate.proof().actionObservationId()
                                    .equals(effect.actionObservationId())
                            && candidate.proof().evidenceObservationId()
                                    .equals(effect.evidenceObservationId())
                            && candidate.clickCriterionId().equals(checkpoint.clickCriterionId())
                            && candidate.viewCriterionId().equals(checkpoint.viewCriterionId())
                            && candidate.requiredSubject().equals(checkpoint.requiredSubject())
                            && candidate.observationEvidenceRef()
                                    .equals(checkpoint.observationEvidenceRef()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return false;
        }
    }

    private static boolean checkpointMatches(JsonNode payload, EffectCheckpointV1 checkpoint) {
        EffectReconciliationV1 proof = checkpoint.effect();
        return payload.path("outcome").asText("").equals("SATISFIED")
                && payload.path("sessionId").asText("").equals(proof.sessionId())
                && payload.path("targetId").asText("").equals(proof.targetId())
                && payload.path("actionObservationId").asText("")
                        .equals(proof.actionObservationId())
                && payload.path("evidenceObservationId").asText("")
                        .equals(proof.evidenceObservationId())
                && payload.path("contractSequence").asLong(-1) == checkpoint.contractSequence()
                && payload.path("clickCriterionId").asText("")
                        .equals(checkpoint.clickCriterionId())
                && payload.path("viewCriterionId").asText("")
                        .equals(checkpoint.viewCriterionId())
                && payload.path("requiredSubject").asText("")
                        .equals(checkpoint.requiredSubject())
                && payload.path("observationEvidenceRef").asText("")
                        .equals(checkpoint.observationEvidenceRef());
    }

    private static void rejectVerifierOnlyEvent(String type) {
        if (type.equals("core.effect.reconciled")
                || type.equals("core.task.checkpoint_verified")
                || type.equals(BusinessEffectCheckpointVerifier.EVENT_TYPE)
                || type.equals(InteractionStageVerifier.EVENT_TYPE))
            throw new IllegalArgumentException("effect verification requires verifier proof");
    }

    private boolean validReconciliationEvidence(List<RunEventEnvelope> events,
                                                        EffectReconciliationV1 proof) {
        RunEventEnvelope started = null;
        RunEventEnvelope action = null;
        RunEventEnvelope observed = null;
        RunEventEnvelope checkpoint = null;
        RunEventEnvelope verifiedOutcome = null;
        for (RunEventEnvelope event : events) {
            JsonNode payload = event.payload();
            if (event.type().equals("core.task.outcome")
                    && (event.schemaVersion() == 2 || event.schemaVersion() == 3)
                    && event.producer().equals("framework.core")
                    && payload.path("outcome").asText("").equals("VERIFIED_COMPLETE"))
                verifiedOutcome = event;
            if (event.type().equals("core.task.checkpoint_verified")
                    && event.schemaVersion() == 1
                    && event.producer().equals("framework.core")
                    && payload.path("actionInvocationId").asText("")
                            .equals(proof.actionInvocationId())) checkpoint = event;
            if (event.type().equals("core.effect.reconciled")
                    && event.producer().equals("framework.core")
                    && payload.path("actionInvocationId").asText("").equals(proof.actionInvocationId()))
                return false;
            if (!event.producer().equals("framework.core") || event.schemaVersion() != 1) continue;
            if (event.type().equals("core.tool.started")
                    && payload.path("invocationId").asText("").equals(proof.actionInvocationId()))
                started = event;
            if (!event.type().equals("core.tool.receipt")) continue;
            if (payload.path("invocationId").asText("").equals(proof.actionInvocationId()))
                action = event;
            if (payload.path("operation").asText("").equals("observe")
                    && payload.path("status").asText("").equals("OBSERVED")
                    && payload.path("metadata").path("observationId").asText("")
                            .equals(proof.evidenceObservationId())) observed = event;
        }
        if (started == null || action == null || observed == null
                || started.sequence() >= action.sequence()
                || action.sequence() >= observed.sequence()
                || !isObservationGated(started.payload())
                || !started.payload().path("tool").asText("")
                        .equals("desktop_session_click")
                || !started.payload().path("resourceKey").asText("")
                        .equals("desktop:" + proof.targetId())) return false;
        RunEventEnvelope actionFrame = null;
        for (RunEventEnvelope event : events) {
            if (event.sequence() >= started.sequence()
                    || !event.type().equals("core.tool.receipt")
                    || event.schemaVersion() != 1
                    || !event.producer().equals("framework.core")) continue;
            JsonNode payload = event.payload();
            String operation = payload.path("operation").asText("");
            if ((operation.equals("observe") || operation.equals("open")
                    || java.util.Set.of("click", "type", "key", "scroll").contains(operation))
                    && payload.path("metadata").path("targetId").asText("")
                            .equals(proof.targetId())
                    && payload.path("metadata").path("sessionId").asText("")
                            .equals(proof.sessionId())) actionFrame = event;
        }
        if (actionFrame == null
                || !actionFrame.payload().path("operation").asText("").equals("observe")
                || !actionFrame.payload().path("status").asText("").equals("OBSERVED")
                || !actionFrame.payload().path("tool").asText("")
                        .equals("desktop_session_observe")
                || !actionFrame.payload().path("target").asText("")
                        .equalsIgnoreCase(action.payload().path("target").asText(""))
                || !actionFrame.payload().path("metadata").path("observationId")
                        .asText("").equals(proof.actionObservationId()))
            return false;
        JsonNode actionPayload = action.payload();
        JsonNode actionMeta = actionPayload.path("metadata");
        JsonNode observedMeta = observed.payload().path("metadata");
        JsonNode actionFrameMeta = actionFrame.payload().path("metadata");
        String observedRef = observed.payload().path("evidenceRef").asText("");
        boolean terminalProof = verifiedOutcome != null
                && verifiedOutcome.payload().path("evidenceRefs").isArray()
                && java.util.stream.StreamSupport.stream(
                        verifiedOutcome.payload().path("evidenceRefs").spliterator(), false)
                        .anyMatch(ref -> ref.isTextual() && ref.asText().equals(observedRef));
        if (terminalProof && verifiedOutcome.schemaVersion() == 3) {
            RunEventEnvelope outcome = verifiedOutcome;
            List<RunEventEnvelope> prior = events.stream()
                    .filter(event -> event.sequence() < outcome.sequence()).toList();
            TaskContractV3 contract = TaskResultEvaluator.latestContractV3(prior, json).orElse(null);
            terminalProof = contract != null
                    && TaskResultEvaluator.evaluateV3(contract, prior, "",
                            TrustedCapabilityRegistry.builtins()).outcome()
                            == TaskOutcome.VERIFIED_COMPLETE;
        }
        JsonNode checkpointPayload = checkpoint == null ? null : checkpoint.payload();
        boolean localProof = checkpointPayload != null
                && checkpoint.sequence() > observed.sequence()
                && checkpointPayload.path("outcome").asText("").equals("SATISFIED")
                && checkpointPayload.path("sessionId").asText("").equals(proof.sessionId())
                && checkpointPayload.path("targetId").asText("").equals(proof.targetId())
                && checkpointPayload.path("actionObservationId").asText("")
                        .equals(proof.actionObservationId())
                && checkpointPayload.path("evidenceObservationId").asText("")
                        .equals(proof.evidenceObservationId())
                && checkpointPayload.path("observationEvidenceRef").asText("")
                        .equals(observedRef)
                && checkpointPayload.path("contractSequence").asLong(-1) > 0
                && !checkpointPayload.path("clickCriterionId").asText("").isBlank()
                && !checkpointPayload.path("viewCriterionId").asText("").isBlank()
                && !checkpointPayload.path("requiredSubject").asText("").isBlank();
        if (observedRef.isBlank() || !(terminalProof || localProof))
            return false;
        if (!actionPayload.path("status").asText("").equals("UNKNOWN")
                || !actionPayload.path("operation").asText("").equals("click")
                || !actionMeta.path("delivery").asText("").equals("MAYBE_SENT")
                || !actionMeta.path("targetId").asText("").equals(proof.targetId())
                || !actionMeta.path("sessionId").asText("").equals(proof.sessionId())
                || !actionMeta.path("observationId").asText("").equals(proof.actionObservationId())
                || !actionFrameMeta.path("targetId").asText("").equals(proof.targetId())
                || !actionFrameMeta.path("sessionId").asText("").equals(proof.sessionId())
                || !actionFrameMeta.path("windowGeneration").asText("")
                        .equals(actionMeta.path("windowGeneration").asText(""))
                || actionFrameMeta.path("contentRevision").asLong(-1) < 0
                || actionFrameMeta.path("capturedAtMillis").asLong(0) <= 0
                || !observedMeta.path("targetId").asText("").equals(proof.targetId())
                || !observedMeta.path("sessionId").asText("").equals(proof.sessionId())
                || !observed.payload().path("tool").asText("")
                        .equals("desktop_session_observe")
                || !observed.payload().path("target").asText("")
                        .equalsIgnoreCase(actionPayload.path("target").asText(""))
                || proof.actionObservationId().equals(proof.evidenceObservationId())
                || observedMeta.path("viewEvidence").asText("").isBlank()
                || observedMeta.path("capturedAtMillis").asLong(0) <= 0
                || !observedMeta.path("windowGeneration").asText("")
                        .equals(actionMeta.path("windowGeneration").asText(""))) return false;
        try {
            java.util.UUID.fromString(proof.actionObservationId());
            java.util.UUID.fromString(proof.evidenceObservationId());
            if (actionFrameMeta.path("capturedAtMillis").asLong(0)
                    > java.time.Instant.parse(actionPayload.path("observedAt")
                            .asText("")).toEpochMilli()) return false;
            if (java.time.Instant.parse(observed.payload().path("observedAt").asText(""))
                    .toEpochMilli() <= java.time.Instant.parse(actionPayload.path("observedAt")
                            .asText("")).toEpochMilli()) return false;
        } catch (RuntimeException invalidTime) { return false; }
        for (RunEventEnvelope between : events) {
            if (between.sequence() <= action.sequence() || between.sequence() >= observed.sequence()
                    || !between.type().equals("core.tool.receipt")) continue;
            JsonNode payload = between.payload();
            if (java.util.Set.of("click", "type", "key", "scroll")
                    .contains(payload.path("operation").asText(""))
                    && payload.path("metadata").path("targetId").asText("")
                            .equals(proof.targetId())) return false;
        }
        return true;
    }

    /** Checked while the Run row is locked, so two carriers cannot reserve the same input. */
    private void requireFreshToolReservation(RunId id, JsonNode candidate) {
        String invocationId = candidate.path("invocationId").asText("");
        if (invocationId.isBlank()) throw new IllegalArgumentException("tool start lacks invocationId");
        String policy = candidate.path("effectPolicy").asText("LEGACY");
        String resource = candidate.path("resourceKey").asText("");
        String fingerprint = candidate.path("fingerprint").asText("");
        String effectKey = candidate.path("effectKey").asText("");
        List<RunEventEnvelope> events = eventsAfter(id, 0);
        List<RunEventEnvelope> starts = events.stream()
                .filter(event -> event.type().equals("core.tool.started")).toList();
        java.util.Map<String, List<RunEventEnvelope>> receipts = new java.util.HashMap<>();
        for (RunEventEnvelope event : events) {
            if (!hostToolEvent(event, "core.tool.receipt")) continue;
            String priorId = event.payload().path("invocationId").asText("");
            if (!priorId.isBlank()) receipts.computeIfAbsent(priorId,
                    ignored -> new java.util.ArrayList<>()).add(event);
        }
        DesktopObservationBaseline.Frame baseline = policy.equals("OBSERVATION_GATED")
                ? matchingDesktopBaseline(events, candidate, resource) : null;
        if (baseline != null) {
            for (RunEventEnvelope consumed : starts) {
                JsonNode input = consumed.payload();
                if (consumed.sequence() <= baseline.sequence()
                        || !hostToolEvent(consumed, "core.tool.started") || !isObservationGated(input)
                        || !sameEffectResource(resource, input.path("resourceKey").asText("desktop:unknown"))) continue;
                String consumedId = input.path("invocationId").asText("");
                List<RunEventEnvelope> inputReceipts = receipts.getOrDefault(consumedId, List.of());
                JsonNode receipt = matchingInputReceipt(inputReceipts, consumed);
                if (definitelyNotSentDesktopInput(consumed, inputReceipts, events)) continue;
                throw pendingInput(id, consumedId, input.path("resourceKey").asText("desktop:unknown"),
                        receipt, PendingEffectObservationRequiredException.Reason.OBSERVATION_ALREADY_CONSUMED);
            }
        }
        Set<String> reconciled = new java.util.HashSet<>();
        for (RunEventEnvelope event : events) {
            if (!event.type().equals("core.effect.reconciled")
                    || event.schemaVersion() != 1
                    || !event.producer().equals("framework.core")) continue;
            JsonNode receipt = event.payload();
            if (receipt.path("outcome").asText("").equals("SATISFIED"))
                reconciled.add(receipt.path("actionInvocationId").asText("") + "\u0000"
                        + receipt.path("targetId").asText(""));
        }
        for (RunEventEnvelope priorStart : starts) {
            JsonNode prior = priorStart.payload();
            String priorId = prior.path("invocationId").asText("");
            if (priorId.equals(invocationId))
                throw new IllegalStateException("tool invocation already reserved: " + invocationId);
            if (!policy.equals("OBSERVATION_GATED") || !isObservationGated(prior)) continue;
            String priorResource = prior.path("resourceKey").asText("desktop:unknown");
            if (!sameEffectResource(resource, priorResource)) continue;
            List<RunEventEnvelope> priorReceipts = receipts.getOrDefault(priorId, List.of());
            JsonNode receipt = matchingInputReceipt(priorReceipts, priorStart);
            boolean notSent = definitelyNotSentDesktopInput(priorStart, priorReceipts, events);
            boolean sameObservationAction = (!fingerprint.isBlank()
                    && fingerprint.equals(prior.path("fingerprint").asText("")))
                    || (!effectKey.isBlank()
                    && effectKey.equals(prior.path("effectKey").asText("")));
            if (sameObservationAction && !notSent)
                throw pendingInput(id, priorId, priorResource, receipt,
                        PendingEffectObservationRequiredException.Reason.OBSERVATION_ALREADY_CONSUMED);
            if (!reconciled.contains(priorId + "\u0000"
                    + priorResource.replaceFirst("^desktop:", ""))
                    && !notSent && (receipt == null || uncertainDelivery(receipt)
                        || receipt.path("status").asText("").equals("FAILED"))
                    && !freshDesktopBaselineAfterInput(baseline, candidate, priorStart, priorReceipts, resource))
                throw pendingInput(id, priorId, priorResource, receipt,
                        PendingEffectObservationRequiredException.Reason.DELIVERY_UNCERTAIN);
        }
    }

    /** A fresh host frame grants a new decision, while the old UNKNOWN receipt remains intact. */
    private static DesktopObservationBaseline.Frame matchingDesktopBaseline(
            List<RunEventEnvelope> events, JsonNode candidate, String resource) {
        if (!exactDesktopInput(candidate) || resource.equals("desktop:unknown")
                || !resource.startsWith("desktop:") || !trustedDesktopMarker(candidate)) return null;
        DesktopObservationBaseline.Frame latest = DesktopObservationBaseline.fromEvents(events).stream()
                .filter(frame -> resource.equals("desktop:" + frame.targetId()))
                .max(java.util.Comparator.comparingLong(DesktopObservationBaseline.Frame::sequence)).orElse(null);
        if (latest == null) return null;
        JsonNode args = candidate.path("arguments");
        JsonNode generation = args.path("generation");
        return strictText(args, "sessionId").equals(latest.sessionId())
                && strictText(args, "observationId").equals(latest.observationId())
                && generation.isIntegralNumber() && generation.canConvertToLong()
                && generation.longValue() == latest.windowGeneration() ? latest : null;
    }

    private static boolean freshDesktopBaselineAfterInput(DesktopObservationBaseline.Frame baseline,
            JsonNode candidate, RunEventEnvelope priorStart, List<RunEventEnvelope> receipts, String resource) {
        JsonNode prior = priorStart.payload();
        if (baseline == null || !hostToolEvent(priorStart, "core.tool.started")
                || !trustedDesktopMarker(prior) || !trustedDesktopMarker(candidate)
                || !exactDesktopInput(prior) || !resource.equals(prior.path("resourceKey").asText(""))
                || resource.equals("desktop:unknown") || baseline.sequence() <= priorStart.sequence()) return false;
        JsonNode args = prior.path("arguments");
        String session = strictText(args, "sessionId");
        String observation = strictText(args, "observationId");
        if (session.isBlank() || observation.isBlank() || observation.equals(baseline.observationId())) return false;
        long attemptedAt;
        try { attemptedAt = priorStart.timestamp().toEpochMilli(); }
        catch (RuntimeException invalidTime) { return false; }
        if (attemptedAt < 1) return false;
        for (RunEventEnvelope event : receipts) {
            JsonNode receipt = event.payload();
            if (!receipt.path("tool").asText("").equals(prior.path("tool").asText(""))) continue;
            if (event.sequence() >= baseline.sequence()) return false;
            JsonNode metadata = receipt.path("metadata");
            // Timeout receipts can lack platform metadata. Conflicting concrete bindings cannot.
            for (String field : List.of("sessionId", "observationId", "targetId")) {
                if (!metadata.has(field)) continue;
                String expected = switch (field) {
                    case "sessionId" -> session;
                    case "observationId" -> observation;
                    default -> baseline.targetId();
                };
                if (!strictText(metadata, field).equals(expected)) return false;
            }
            try { attemptedAt = Math.max(attemptedAt, event.timestamp().toEpochMilli()); }
            catch (RuntimeException invalidTime) { return false; }
            try { attemptedAt = Math.max(attemptedAt,
                    Instant.parse(strictText(receipt, "observedAt")).toEpochMilli()); }
            catch (RuntimeException invalidTime) { /* The durable host receipt boundary remains authoritative. */ }
        }
        return baseline.capturedAtMillis() > attemptedAt;
    }

    private static JsonNode matchingInputReceipt(List<RunEventEnvelope> receipts, RunEventEnvelope started) {
        // Taking the last receipt can turn an earlier uncertain dispatch into a false NOT_SENT.
        if (receipts.size() != 1) return null;
        RunEventEnvelope event = receipts.getFirst();
        JsonNode receipt = event.payload();
        JsonNode start = started.payload();
        return hostToolEvent(event, "core.tool.receipt") && event.runId().equals(started.runId())
                && event.sequence() > started.sequence() && !event.timestamp().isBefore(started.timestamp())
                && strictText(receipt, "tool").equals(strictText(start, "tool"))
                && strictText(receipt, "invocationId").equals(strictText(start, "invocationId"))
                ? receipt : null;
    }

    /** A platform rejection before input does not spend the observation used to request it. */
    private static boolean definitelyNotSentDesktopInput(RunEventEnvelope started,
            List<RunEventEnvelope> receipts, List<RunEventEnvelope> events) {
        JsonNode start = started.payload();
        JsonNode receipt = matchingInputReceipt(receipts, started);
        if (receipt == null || !hostToolEvent(started, "core.tool.started")
                || !trustedDesktopMarker(start) || !trustedDesktopMarker(receipt)
                || !exactDesktopInput(start) || !strictText(receipt, "status").equals("FAILED")
                || !strictText(receipt.path("metadata"), "delivery").equals("NOT_SENT")) return false;
        JsonNode metadata = receipt.path("metadata");
        if (metadata.has("dispatchAttempted") && !strictText(metadata, "dispatchAttempted").equals("false"))
            return false;
        try {
            Instant observedAt = Instant.parse(strictText(receipt, "observedAt"));
            if (observedAt.isBefore(started.timestamp()) || observedAt.isAfter(receipts.getFirst().timestamp()))
                return false;
        } catch (RuntimeException invalidTime) { return false; }
        String invocation = strictText(start, "invocationId");
        String operation = strictText(start, "tool").replaceFirst("^desktop_session_", "");
        if (strictText(receipt, "operation").equals("execute")) {
            // An executor that rejected submission emits a host failed event and a NOT_SENT receipt.
            if (!strictText(receipt, "evidenceRef").equals("core.tool.failed:" + started.runId() + ":" + invocation))
                return false;
            List<RunEventEnvelope> failures = events.stream().filter(event ->
                    event.type().equals("core.tool.failed") && event.schemaVersion() == 2
                            && event.producer().equals("framework.core") && event.runId().equals(started.runId())
                            && event.sequence() > started.sequence()
                            && event.sequence() < receipts.getFirst().sequence()
                            && strictText(event.payload(), "invocationId").equals(invocation)
                            && strictText(event.payload(), "tool").equals(strictText(start, "tool"))
                            && strictText(event.payload(), "status").equals("FAILED")).toList();
            return failures.size() == 1;
        }
        if (!strictText(receipt, "operation").equals(operation)
                || !strictText(receipt, "evidenceRef").equals("core.tool.completed:" + started.runId() + ":" + invocation))
            return false;
        JsonNode args = start.path("arguments");
        String session = strictText(args, "sessionId");
        String observation = strictText(args, "observationId");
        String target = strictText(metadata, "targetId");
        if (metadata.has("windowGeneration")) {
            JsonNode generation = args.path("generation");
            if (!generation.isIntegralNumber() || !generation.canConvertToLong()
                    || !strictText(metadata, "windowGeneration").equals(Long.toString(generation.longValue())))
                return false;
        }
        return !session.isBlank() && !observation.isBlank() && !target.isBlank()
                && session.equals(strictText(metadata, "sessionId"))
                && observation.equals(strictText(metadata, "observationId"))
                && strictText(start, "resourceKey").equals("desktop:" + target);
    }

    private static boolean hostToolEvent(RunEventEnvelope event, String type) {
        return event.type().equals(type) && event.schemaVersion() == 1
                && event.producer().equals("framework.core");
    }

    private static boolean exactDesktopInput(JsonNode value) {
        return java.util.Set.of("desktop_session_click", "desktop_session_type",
                "desktop_session_key", "desktop_session_scroll").contains(strictText(value, "tool"));
    }

    private static boolean trustedDesktopMarker(JsonNode value) {
        return !value.has("trustedDesktopTool") || value.path("trustedDesktopTool").isBoolean()
                && value.path("trustedDesktopTool").booleanValue();
    }

    private static String strictText(JsonNode value, String field) {
        return value.path(field).isTextual() ? value.path(field).textValue() : "";
    }

    private static PendingEffectObservationRequiredException pendingInput(RunId id, String invocationId,
            String resource, JsonNode receipt, PendingEffectObservationRequiredException.Reason reason) {
        EffectReceiptV1.Status status = EffectReceiptV1.Status.UNKNOWN;
        try { if (receipt != null) status = EffectReceiptV1.Status.valueOf(receipt.path("status").asText("")); }
        catch (IllegalArgumentException invalidStatus) { /* Unknown transport remains fenced. */ }
        String delivery = receipt == null ? "MAYBE_SENT" : receipt.path("metadata").path("delivery").asText("MAYBE_SENT");
        return new PendingEffectObservationRequiredException(id.value(), invocationId, resource,
                status, delivery, reason);
    }

    private static boolean isObservationGated(JsonNode started) {
        String policy = started.path("effectPolicy").asText("");
        if (!policy.isBlank()) return policy.equals("OBSERVATION_GATED");
        return java.util.Set.of("desktop_session_click", "desktop_session_type",
                "desktop_session_key", "desktop_session_scroll")
                .contains(started.path("tool").asText(""));
    }

    private static boolean sameEffectResource(String a, String b) {
        if (a.startsWith("desktop:") && b.startsWith("desktop:"))
            return a.equals("desktop:unknown") || b.equals("desktop:unknown") || a.equals(b);
        return !a.isBlank() && a.equals(b);
    }

    private static boolean uncertainDelivery(JsonNode receipt) {
        String status = receipt.path("status").asText("");
        String delivery = receipt.path("metadata").path("delivery").asText("");
        if (delivery.isBlank()) {
            JsonNode attempted = receipt.path("metadata").path("dispatchAttempted");
            if (attempted.isTextual()) delivery = attempted.asText().equals("false")
                    ? "NOT_SENT" : "MAYBE_SENT";
        }
        return !java.util.Set.of("FAILED", "ACCEPTED", "OBSERVED", "VERIFIED").contains(status)
                || delivery.equals("MAYBE_SENT")
                || status.equals("FAILED") && !delivery.equals("NOT_SENT");
    }

    @Override public Optional<RunEventEnvelope> settleStep(RunId id, RunEventDraft event) {
        if (!event.type().equals("core.step.completed") && !event.type().equals("core.step.failed")) return Optional.empty();
        String stepId = event.payload().path("stepId").asText("");
        if (stepId.isBlank()) return Optional.empty();
        return Optional.ofNullable(transactions.execute(status -> {
            StoredRun stored = find(id).orElse(null);
            if (stored == null) return null;
            ThreadSnapshot thread = threads.lock(stored.request().scope());
            if (thread.status() != ThreadStatus.ACTIVE && thread.status() != ThreadStatus.ARCHIVED) return null;
            List<LockedRun> locked = jdbc.query("SELECT state,last_sequence,version FROM agent_runs WHERE run_id=? FOR UPDATE",
                    (row, index) -> new LockedRun(RunState.valueOf(row.getString("state")),
                            row.getLong("last_sequence"), row.getLong("version")), id.value());
            if (locked.isEmpty()) return null;
            boolean started = false;
            for (RunEventEnvelope previous : eventsAfter(id, 0)) {
                if (!stepId.equals(previous.payload().path("stepId").asText())) continue;
                if (previous.type().equals("core.step.started")) started = true;
                if (previous.type().equals("core.step.completed") || previous.type().equals("core.step.failed")) return null;
            }
            if (!started) return null;
            LockedRun current = locked.getFirst();
            long next = current.lastSequence() + 1, now = clock.millis();
            // No state/output/error update: this is the outcome of work authorized before termination.
            if (jdbc.update("UPDATE agent_runs SET last_sequence=?,version=version+1,updated_at=? WHERE run_id=? AND version=?",
                    next, now, id.value(), current.version()) != 1) throw new IllegalStateException("concurrent step settlement: " + id);
            RunEventEnvelope settled = envelope(id, next, now, event);
            insertEventAndOutbox(settled);
            threads.appendRun(stored.request().scope(), settled, null);
            return settled;
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
