package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.CancelReason;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.spi.ExecutionPlanStore;
import com.javaclaw.framework.spi.RunEventDraft;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.StoredRun;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Restores locked execution state and durable effect fences without starting business work. */
final class RunRecoveryCoordinator {
    private final AgentCompiler compiler;
    private final RunStore runs;
    private final ExecutionPlanStore plans;
    private final ObjectMapper json;
    private final Clock clock;
    private final RunUsageLedger usage;

    RunRecoveryCoordinator(AgentCompiler compiler, RunStore runs, ExecutionPlanStore plans,
            ObjectMapper json, Clock clock, RunUsageLedger usage) {
        this.compiler = compiler;
        this.runs = runs;
        this.plans = plans;
        this.json = json;
        this.clock = clock;
        this.usage = usage;
    }

    void recover(BiConsumer<RunId, CancelReason> cancel, Consumer<RecoveredRun> attach,
            Consumer<RunId> detach) {
        RunUsageRecovery usageRecovery = new RunUsageRecovery(runs, plans, json, usage);
        for (StoredRun stored : runs.nonTerminalRuns()) {
            if (!runs.readable(stored.request().scope())) continue;
            RunId id = stored.snapshot().id();
            // Expiring a parent also cancels its attached children in this recovery batch.
            if (runs.find(id).orElseThrow().snapshot().state().terminal()) continue;
            ExecutionPlanDescriptor descriptor;
            try {
                descriptor = descriptor(stored);
            } catch (Exception failure) {
                markBlocked(stored, "MISSING_EXECUTION_PLAN", failure.getMessage());
                continue;
            }
            Instant deadline = persistedDeadline(stored, descriptor);
            boolean expired = !clock.instant().isBefore(deadline);
            try { usageRecovery.restore(id); }
            catch (RuntimeException failure) {
                // Terminal expiry never admits more spending; durable usage remains in the journal.
                if (!expired) {
                    markBlocked(stored, "USAGE_LINEAGE_RECOVERY_FAILED", failure.getMessage());
                    continue;
                }
            }
            if (expired) {
                cancel.accept(id, new CancelReason("RUN_TIMEOUT", "run deadline exceeded: " + deadline));
                continue;
            }
            ExecutionPlan plan = compiler.restore(descriptor).orElse(null);
            if (plan == null) {
                String locks = descriptor.extensionLocks().stream()
                        .map(lock -> lock.extensionId() + ":" + lock.version() + "@"
                                + lock.artifactSha256().substring(0, 12))
                        .collect(Collectors.joining(","));
                markBlocked(stored, "MISSING_LOCKED_EXTENSION", locks);
                continue;
            }
            try {
                RunControl control = new RunControl(descriptor.budget(), clock, deadline);
                var approval = PersistedRunStateRestorer.restore(runs.eventsAfter(id, 0), control);
                inheritEffects(id, stored.request(), control);
                attach.accept(new RecoveredRun(stored, plan, control, approval));
            } catch (RuntimeException failure) {
                detach.accept(id);
                usage.close(id);
                plan.close();
                markBlocked(stored, "RECOVERY_ATTACH_FAILED", failure.getMessage());
            }
        }
    }

    Optional<Instant> deadline(StoredRun stored) {
        try {
            JsonNode persisted = plans.find(stored.snapshot().executionPlanId()).orElse(null);
            if (persisted == null) return Optional.empty();
            return Optional.of(persistedDeadline(stored,
                    json.treeToValue(persisted, ExecutionPlanDescriptor.class)));
        } catch (Exception unavailable) {
            return Optional.empty();
        }
    }

    /** Only a fresh explicit resume may run work recovered from a prior process. */
    void pauseRecovered(StoredRun stored, Consumer<RunEventEnvelope> published) {
        RunId id = stored.snapshot().id();
        RunState state = stored.snapshot().state();
        if ((state == RunState.CREATED && runs.claim(id)) || state == RunState.RUNNING
                || state == RunState.RECOVERY_BLOCKED_MISSING_EXTENSION) {
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("reason", "PROCESS_RESTART_REQUIRES_RESUME");
            runs.append(id, Set.of(state), RunState.PAUSED,
                    new RunEventDraft("core.run.recovered_paused", 1, "framework.core",
                            stored.request().linkage().correlationId(), null, payload), null, null)
                    .ifPresent(published);
        }
    }

    /** A new goal cannot erase unresolved effects owned by its authorized conversation. */
    void inheritEffects(RunId currentId, RunRequest request, RunControl control) {
        RunScope scope = request.scope();
        ArrayDeque<StoredRun> history = new ArrayDeque<>();
        runs.scopeRuns(scope).stream().filter(run -> run.request().scope().equals(scope))
                .forEach(history::addLast);
        Set<RunId> visited = new HashSet<>();
        while (!history.isEmpty()) {
            StoredRun previous = history.removeFirst();
            RunId id = previous.snapshot().id();
            if (!visited.add(id)) continue;
            RunScope sourceScope = previous.request().scope();
            if (!scope.workspaceId().equals(sourceScope.workspaceId())
                    || !scope.userId().equals(sourceScope.userId()) || !runs.readable(sourceScope)) continue;
            if (!id.equals(currentId)) {
                // Only the source's already established action-specific proof can repair a
                // missed terminal reconciliation; a new observation is never substituted.
                if (scope.equals(sourceScope) && previous.snapshot().state().terminal())
                    VerifiedEffectReconciler.recoverTerminalEffects(runs, currentId, id, json);
                PersistedRunStateRestorer.restoreUnresolvedEffects(runs.eventsAfter(id, 0), control);
            }
            // Delegated work is part of its attached root; unrelated sessions are excluded.
            runs.childRuns(id).stream()
                    .filter(child -> id.equals(child.request().linkage().parentRunId()))
                    .filter(child -> !child.request().attributes().getOrDefault("framework.detached",
                            JsonNodeFactory.instance.booleanNode(false)).asBoolean())
                    .forEach(history::addLast);
        }
    }

    private ExecutionPlanDescriptor descriptor(StoredRun stored) throws Exception {
        JsonNode persisted = plans.find(stored.snapshot().executionPlanId())
                .orElseThrow(() -> new IllegalStateException("persisted execution plan is missing"));
        return json.treeToValue(persisted, ExecutionPlanDescriptor.class);
    }

    /** 旧日志与直接工具调用使用相同的原始截止时间规则。 */
    private Instant persistedDeadline(StoredRun stored, ExecutionPlanDescriptor descriptor) {
        return PersistedRunDeadline.resolve(stored, descriptor.budget(),
                runs.eventsAfter(stored.snapshot().id(), 0));
    }

    private void markBlocked(StoredRun stored, String code, String detail) {
        RunState current = runs.find(stored.snapshot().id())
                .map(value -> value.snapshot().state()).orElse(stored.snapshot().state());
        if (current.terminal()) return;
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("code", code);
        payload.put("detail", detail == null ? "" : detail);
        runs.append(stored.snapshot().id(), Set.of(current), RunState.RECOVERY_BLOCKED_MISSING_EXTENSION,
                new RunEventDraft("core.run.recovery_blocked", 1, "framework.core",
                        stored.request().linkage().correlationId(), null, payload), null, detail);
    }

    record RecoveredRun(StoredRun stored, ExecutionPlan plan, RunControl control,
                        PersistedRunStateRestorer.ApprovalState approval) { }
}
