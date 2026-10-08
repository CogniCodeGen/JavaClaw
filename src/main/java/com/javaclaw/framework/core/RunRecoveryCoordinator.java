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
                || state == RunState.WAITING_CHILD || state == RunState.WAITING_EVENT
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
                PersistedRunStateRestorer.restoreUnresolvedEffects(runs.eventsAfter(id, 0), control,
                        distinctHumanChatIntent(request, previous.request()));
            }
            // Delegated work is part of its attached root; unrelated sessions are excluded.
            runs.childRuns(id).stream()
                    .filter(child -> id.equals(child.request().linkage().parentRunId()))
                    .filter(child -> !child.request().attributes().getOrDefault("framework.detached",
                            JsonNodeFactory.instance.booleanNode(false)).asBoolean())
                    .forEach(history::addLast);
        }
    }

    /** Run IDs also identify repairs and delegated work; only host chat-turn keys identify new intent. */
    private boolean distinctHumanChatIntent(RunRequest current, RunRequest previous) {
        if (distinctRootHumanChatIntent(current, previous)) return true;
        if (!current.scope().equals(previous.scope())) return false;
        StoredRun currentRoot = interactionHumanRoot(current);
        StoredRun previousRoot = interactionHumanRoot(previous);
        return currentRoot != null && previousRoot != null
                && !currentRoot.snapshot().id().equals(previousRoot.snapshot().id())
                && distinctRootHumanChatIntent(currentRoot.request(), previousRoot.request());
    }

    private static boolean distinctRootHumanChatIntent(RunRequest current, RunRequest previous) {
        return hostHumanChatRequest(current) && hostHumanChatRequest(previous)
                && current.scope().equals(previous.scope())
                && !current.idempotencyKey().equals(previous.idempotencyKey());
    }

    /** Resolve only the host's direct interaction delegation, never a caller-supplied root ID. */
    private StoredRun interactionHumanRoot(RunRequest child) {
        if (!InteractionExecutionPolicy.isInteraction(child)
                || !child.source().id().equals(InteractionExecutionPolicy.PROFILE_ID)
                || child.linkage().parentRunId() == null || child.linkage().workflowRunId() != null
                || child.attributes().getOrDefault("framework.detached",
                    JsonNodeFactory.instance.booleanNode(false)).asBoolean()
                || !runs.readable(child.scope())) return null;
        StoredRun parent = runs.find(child.linkage().parentRunId()).orElse(null);
        if (parent == null || !hostHumanChatRequest(parent.request())
                || !parent.request().agent().id().equals("system.default")
                || !InteractionExecutionPolicy.isMain(parent.request())
                || !runs.readable(parent.request().scope())
                || !child.scope().workspaceId().equals(parent.request().scope().workspaceId())
                || !child.scope().userId().equals(parent.request().scope().userId())
                || !InteractionDelegateCoordinator.childScope(parent.request().scope()).equals(child.scope())
                || !json.valueToTree(parent.request().scope()).equals(child.attributes()
                    .get(InteractionExecutionPolicy.PARENT_SCOPE_ATTRIBUTE))
                || !json.valueToTree(parent.request().source()).equals(child.attributes()
                    .get(InteractionExecutionPolicy.PARENT_SOURCE_ATTRIBUTE))
                || !parent.request().permissionCeiling().containsAll(child.permissionCeiling())) return null;
        JsonNode task = child.attributes().get(InteractionExecutionPolicy.TASK_ATTRIBUTE);
        JsonNode frozen = child.attributes().get("framework.taskContract");
        if (task == null || frozen == null || task.path("version").asInt() != 1
                || !task.path("revision").isIntegralNumber() || !task.path("revision").canConvertToLong()
                || task.path("revision").longValue() < 1
                || !task.path("taskId").isTextual() || task.path("taskId").asText().isBlank()
                || !frozen.path("originalRequest").equals(task.path("goal"))
                || !frozen.path("source").asText().equals("host.interaction")
                || !resolvedContract(frozen)) return null;
        String invocation = task.path("taskId").textValue();
        if (!com.javaclaw.framework.api.StepId.tool(parent.snapshot().id(), invocation).value()
                .equals(child.attributes().getOrDefault("framework.parentStepId",
                    JsonNodeFactory.instance.nullNode()).asText())) return null;
        var history = runs.eventsAfter(parent.snapshot().id(), 0).stream()
                .filter(event -> event.runId().equals(parent.snapshot().id().value())
                    && event.producer().equals("framework.core")).toList();
        if (history.stream().noneMatch(event -> event.schemaVersion() == 1 && event.sequence() == 1
                    && event.type().equals("core.run.created")
                    && event.payload().path("source").asText().equals("chat"))
                || history.stream().filter(event -> event.schemaVersion() == 1
                    && event.type().equals("core.tool.started")
                    && event.payload().path("tool").asText().equals(InteractionExecutionPolicy.DELEGATE_TOOL)
                    && event.payload().path("invocationId").asText().equals(invocation)).count() != 1)
            return null;
        // Historical children retain their own frozen contract after AMEND. Compare it
        // with an actual parent contract, never require the parent's newest revision.
        var ids = JsonNodeFactory.instance.arrayNode();
        for (JsonNode criterion : frozen.path("criteria")) ids.add(criterion.path("id"));
        if (ids.isEmpty() || !ids.equals(task.path("acceptanceCriterionIds"))) return null;
        boolean matched = history.stream().anyMatch(event -> {
            if (event.schemaVersion() != 3 || !java.util.Set.of("core.task.contract", "core.task.contract_revised")
                    .contains(event.type()) || !resolvedContract(event.payload())) return false;
            var expected = JsonNodeFactory.instance.arrayNode();
            boolean seen = false, closed = false;
            for (JsonNode criterion : event.payload().path("criteria")) {
                String capability = criterion.path("capabilityId").asText();
                if (capability.startsWith("browser.") || capability.startsWith("desktop.")) {
                    if (closed) return false;
                    seen = true;
                    expected.add(criterion);
                } else if (seen) closed = true;
            }
            return expected.equals(frozen.path("criteria"))
                    && event.payload().path("desktopObservationPolicy").equals(frozen.path("desktopObservationPolicy"))
                    && event.payload().path("intentStatus").equals(frozen.path("intentStatus"));
        });
        return matched ? parent : null;
    }

    private static boolean resolvedContract(JsonNode value) {
        return value.path("version").asInt() == 3
                && value.path("criteria").isArray()
                && value.path("reliable").isBoolean() && value.path("reliable").booleanValue()
                && value.path("applicable").isBoolean() && value.path("applicable").booleanValue()
                && value.path("intentStatus").asText().equals("RESOLVED");
    }

    private static boolean hostHumanChatRequest(RunRequest request) {
        if (!request.source().kind().equals("chat") || !request.source().id().equals("desktop")
                || !request.profile().id().equals("chat") || request.linkage().parentRunId() != null
                || request.linkage().workflowRunId() != null) return false;
        String key = request.idempotencyKey();
        String prefix = "chat-turn:";
        if (key == null || !key.startsWith(prefix)) return false;
        try {
            String token = key.substring(prefix.length());
            return java.util.UUID.fromString(token).toString().equals(token);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private ExecutionPlanDescriptor descriptor(StoredRun stored) throws Exception {
        JsonNode persisted = plans.find(stored.snapshot().executionPlanId())
                .orElseThrow(() -> new IllegalStateException("persisted execution plan is missing"));
        return json.treeToValue(persisted, ExecutionPlanDescriptor.class);
    }

    /** 恢复与直接工具调用使用相同的冻结执行预算和等待规则。 */
    private Instant persistedDeadline(StoredRun stored, ExecutionPlanDescriptor descriptor) {
        return PersistedRunDeadline.resolve(stored, descriptor.budget(),
                runs.eventsAfter(stored.snapshot().id(), 0), clock.instant());
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
