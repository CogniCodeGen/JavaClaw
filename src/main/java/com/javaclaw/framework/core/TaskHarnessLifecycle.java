package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.api.TaskContractV2;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.api.TaskResult;
import com.javaclaw.framework.api.CapabilityMetadata;
import com.javaclaw.framework.api.TaskStopReason;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.ResumeCommand;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.RunEventDraft;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Persists task contracts and acceptance results beside the technical Run lifecycle. */
final class TaskHarnessLifecycle {
    private final RunStore runs;
    private final ObjectMapper json;
    private final TaskContractCompiler compiler;
    private final TrustedCapabilityRegistry capabilities;
    private final BiConsumer<RunRequest, TaskResultEvaluator.VerifiedActionEvidence> verifiedDesktopEffect;

    TaskHarnessLifecycle(RunStore runs, ObjectMapper json, TaskContractCompiler compiler) {
        this(runs, json, compiler, (request, evidence) -> { });
    }

    TaskHarnessLifecycle(RunStore runs, ObjectMapper json, TaskContractCompiler compiler,
            BiConsumer<RunRequest, TaskResultEvaluator.VerifiedActionEvidence> verifiedDesktopEffect) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.json = Objects.requireNonNull(json, "json");
        this.compiler = compiler;
        this.capabilities = compiler == null ? TrustedCapabilityRegistry.builtins()
                : compiler.capabilities();
        this.verifiedDesktopEffect = Objects.requireNonNull(verifiedDesktopEffect, "verifiedDesktopEffect");
    }

    boolean active() { return compiler != null; }

    java.util.Optional<CapabilityMetadata> capabilityForReceipt(
            String tool, String operation) {
        if (!active()) return java.util.Optional.empty();
        return capabilities.forReceipt(tool, operation).map(descriptor ->
                new CapabilityMetadata(descriptor.id(), descriptor.evidenceCeiling()));
    }

    java.util.Optional<CapabilityMetadata> capabilityForTool(String tool) {
        if (!active()) return java.util.Optional.empty();
        return capabilities.metadataForTool(tool);
    }

    void ensure(RunId id, RunRequest request, RunControl control,
                Consumer<RunEventEnvelope> published) {
        if (!enabled(id)) return;
        List<RunEventEnvelope> events = runs.eventsAfter(id, 0);
        if (TaskResultEvaluator.latestContractV3(events, json).isPresent()) return;
        TaskContractV3 contract = compiler.compileV3(id, request, control);
        runs.append(id, Set.of(RunState.RUNNING), RunState.RUNNING,
                draft(request, "core.task.contract", 3, json.valueToTree(contract)), null, null)
                .ifPresent(published);
    }

    /** Only a new human clarification can revise an unresolved model plan on a paused Run. */
    void reviseUnreliableContract(RunId id, RunRequest request, ResumeCommand resume,
            RunControl control, Consumer<RunEventEnvelope> published) {
        if (!enabled(id) || resume == null || !"user.input".equals(resume.type())
                || !resume.payload().path("text").isTextual()
                || resume.payload().path("text").asText().isBlank()
                || request.attributes().containsKey(TaskContractCompiler.ATTRIBUTE)
                || request.attributes().getOrDefault("framework.managed",
                        com.fasterxml.jackson.databind.node.BooleanNode.FALSE).asBoolean()
                || request.attributes().getOrDefault("framework.maintenance",
                        com.fasterxml.jackson.databind.node.BooleanNode.FALSE).asBoolean()) return;
        List<RunEventEnvelope> events = runs.eventsAfter(id, 0);
        TaskContractV3 contract = TaskResultEvaluator.latestContractV3(events, json).orElse(null);
        if (!canClarify(contract)) return;
        RunEventEnvelope resumed = events.stream()
                .filter(event -> event.type().equals("core.run.resumed")
                        && event.producer().equals("framework.core"))
                .max(java.util.Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
        if (resumed == null || !"user.input".equals(resumed.payload().path("commandType").asText())
                || !resume.payload().equals(resumed.payload().path("command"))
                || revisedAfter(events, resumed.sequence())) return;
        control.throwIfCancelled();
        RunRequest clarified = clarificationRequest(request, contract,
                resume.payload().path("text").asText(), events, resumed.sequence());
        TaskContractV3 revised = compiler.compileV3(id, clarified, control);
        control.throwIfCancelled();
        // Planning does not reset effect fences or authorize repeating any old input.
        var revision = runs.withRunAcceptanceLock(id, () -> {
            List<RunEventEnvelope> committed = runs.eventsAfter(id, 0);
            if (!canClarify(TaskResultEvaluator.latestContractV3(committed, json).orElse(null))
                    || revisedAfter(committed, resumed.sequence()))
                return java.util.Optional.<RunEventEnvelope>empty();
            return runs.append(id, Set.of(RunState.RUNNING), RunState.RUNNING,
                    new RunEventDraft("core.task.contract_revised", 3, "framework.core",
                            request.linkage().correlationId(),
                            "core.run.resumed:" + resumed.sequence(), json.valueToTree(revised)), null, null);
        });
        revision.ifPresent(value -> { if (published != null) published.accept(value); });
    }

    private static boolean canClarify(TaskContractV3 contract) {
        return contract != null && !contract.reliable()
                && Set.of("model", "model-repair", "unknown").contains(contract.source());
    }

    private static boolean revisedAfter(List<RunEventEnvelope> events, long sequence) {
        return events.stream().anyMatch(event -> event.sequence() > sequence
                && event.producer().equals("framework.core")
                && (event.type().equals("core.task.contract")
                        || event.type().equals("core.task.contract_revised")));
    }

    private static RunRequest clarificationRequest(RunRequest request, TaskContractV3 contract,
            String clarification, List<RunEventEnvelope> events, long currentResume) {
        List<InputBlock> inputs = new ArrayList<>();
        for (InputBlock input : request.inputs()) {
            if (input.type().equals("core.message")
                    && "user".equals(input.data().path("role").asText())
                    && !input.data().path("text").asText("").isBlank()) inputs.add(input);
        }
        String priorOriginal = contract.originalRequest();
        if (!priorOriginal.isBlank() && inputs.stream().noneMatch(input ->
                priorOriginal.equals(input.data().path("text").asText())))
            inputs.add(InputBlock.message("user", priorOriginal));
        String priorCurrent = TaskContractCompiler.currentUserInput(request);
        if (!priorCurrent.isBlank() && !priorCurrent.equals(priorOriginal))
            inputs.add(InputBlock.message("user", priorCurrent));
        for (RunEventEnvelope event : events) {
            if (event.sequence() >= currentResume || !event.type().equals("core.run.resumed")
                    || !event.producer().equals("framework.core")
                    || !"user.input".equals(event.payload().path("commandType").asText())) continue;
            var text = event.payload().path("command").path("text");
            if (text.isTextual() && !text.asText().isBlank()) inputs.add(InputBlock.message("user", text.asText()));
        }
        inputs.add(InputBlock.text(clarification));
        var attributes = new LinkedHashMap<>(request.attributes());
        attributes.remove(TaskContractCompiler.ATTRIBUTE);
        attributes.remove(TaskContractCompiler.ORIGINAL_REQUEST_ATTRIBUTE);
        attributes.remove(TaskContractCompiler.RESOLVED_REQUEST_ATTRIBUTE);
        attributes.remove(TaskAcceptanceContext.ATTRIBUTE);
        return new RunRequest(request.agent(), request.profile(), request.source(), request.scope(),
                inputs, request.linkage(), request.permissionCeiling(), request.budget(),
                request.idempotencyKey(), attributes);
    }

    void reviseQuestionContract(RunId id, RunRequest request,
                                Consumer<RunEventEnvelope> published) {
        if (!enabled(id)) return;
        List<RunEventEnvelope> events = runs.eventsAfter(id, 0);
        TaskContractV3 contract = TaskResultEvaluator.latestContractV3(events, json).orElse(null);
        if (contract == null || contract.applicable()) return;
        TaskContractV3 revised = new TaskContractV3(3, contract.originalRequest(),
                List.of(), true, false, "tool-use-revision",
                List.of("TOOL_USED_AFTER_NOT_APPLICABLE_CLASSIFICATION"), contract.unresolvedInputs(),
                contract.desktopObservationPolicy());
        runs.append(id, Set.of(RunState.RUNNING), RunState.RUNNING,
                draft(request, "core.task.contract_revised", 3, json.valueToTree(revised)), null, null)
                .ifPresent(published);
    }

    String stopReason(RunId id, String fallback) {
        List<RunEventEnvelope> events = runs.eventsAfter(id, 0);
        for (int index = events.size() - 1; index >= 0; index--) {
            RunEventEnvelope event = events.get(index);
            if (event.type().equals("core.run.resumed") && event.schemaVersion() == 1
                    && event.producer().equals("framework.core")) return fallback;
            if (event.type().equals("core.task.stop")) {
                if (event.schemaVersion() != 3 || !(event.producer().equals("framework.springai")
                        || event.producer().equals("framework.core"))) {
                    throw new IllegalStateException("untrusted task stop event");
                }
                String code = event.payload().path("reasonCode").asText("");
                try {
                    return TaskStopReason.valueOf(code).name();
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalStateException("invalid task stop reason code", invalid);
                }
            }
        }
        return fallback;
    }

    /** A trusted post-action view can satisfy a predeclared intermediate checkpoint. */
    void reconcileCheckpoints(RunId id, RunRequest request, RunControl control,
                              Consumer<RunEventEnvelope> published) {
        if (!enabled(id)) return;
        List<RunEventEnvelope> ownEvents = runs.eventsAfter(id, 0);
        TaskContractV3 contractV3 = TaskResultEvaluator.latestContractV3(ownEvents, json).orElse(null);
        TaskContractV2 contract = contractV3 == null ? null
                : TaskResultEvaluator.desktopContract(contractV3, capabilities);
        if (contract == null || !contract.applicable() || !contract.reliable()) return;
        List<RunEventEnvelope> evidence = TaskEvidenceCollector.collect(runs, id);
        for (VerifiedEffectReconciler.ReconciledCheckpoint checkpoint
                : VerifiedEffectReconciler.reconcileCheckpoints(
                        runs, id, control, contractV3, contract, capabilities, evidence)) {
            if (published != null) {
                published.accept(checkpoint.checkpointEvent());
                published.accept(checkpoint.reconciliationEvent());
            }
            verifiedDesktopEffect.accept(request, checkpoint.proof());
        }
    }

    /** Repair the in-memory desktop gate if a Run resumes after its durable checkpoint. */
    void replayCheckpointEffects(RunId id, RunRequest request) {
        List<RunEventEnvelope> events = runs.eventsAfter(id, 0);
        Set<String> checkpointed = events.stream()
                .filter(event -> event.type().equals("core.task.checkpoint_verified")
                        && event.producer().equals("framework.core"))
                .map(event -> event.payload().path("actionInvocationId").asText(""))
                .collect(java.util.stream.Collectors.toSet());
        for (RunEventEnvelope event : events) {
            if (!event.type().equals("core.effect.reconciled")
                    || !event.producer().equals("framework.core")
                    || !checkpointed.contains(event.payload()
                            .path("actionInvocationId").asText(""))) continue;
            var proof = event.payload();
            verifiedDesktopEffect.accept(request,
                    new TaskResultEvaluator.VerifiedActionEvidence(
                            proof.path("actionInvocationId").asText(""),
                            proof.path("sessionId").asText(""),
                            proof.path("targetId").asText(""),
                            proof.path("actionObservationId").asText(""),
                            proof.path("evidenceObservationId").asText("")));
        }
    }

    TaskResult persist(RunId id, RunRequest request, Set<RunState> expected,
                       String stopReason, Consumer<RunEventEnvelope> published) {
        if (!enabled(id)) return null;
        List<RunEventEnvelope> committed = new java.util.ArrayList<>();
        TaskResult result = runs.withRunAcceptanceLock(id,
                () -> persistLocked(id, request, expected, stopReason, committed::add));
        if (published != null) committed.forEach(published);
        return result;
    }

    private TaskResult persistLocked(RunId id, RunRequest request, Set<RunState> expected,
                       String stopReason, Consumer<RunEventEnvelope> published) {
        List<RunEventEnvelope> ownEvents = runs.eventsAfter(id, 0);
        TaskResult existing = TaskResultEvaluator.latestOutcome(ownEvents, json).orElse(null);
        long latestOutcomeSequence = ownEvents.stream()
                .filter(event -> event.type().equals("core.task.outcome")
                        && event.schemaVersion() == 3
                        && event.producer().equals("framework.core"))
                .mapToLong(RunEventEnvelope::sequence).max().orElse(0);
        boolean resumedAfterOutcome = latestOutcomeSequence > 0 && ownEvents.stream()
                .anyMatch(event -> event.sequence() > latestOutcomeSequence
                        && event.type().equals("core.run.resumed")
                        && event.producer().equals("framework.core"));
        if (existing != null && !resumedAfterOutcome) {
            reconcileVerified(id, request, ownEvents, existing, published);
            return existing;
        }
        List<RunEventEnvelope> evidence = TaskEvidenceCollector.collect(runs, id);
        TaskResult result = TaskResultEvaluator.evaluateV3(
                TaskResultEvaluator.latestContractV3(ownEvents, json).orElse(null),
                evidence, stopReason, capabilities);
        if (result.outcome() == TaskOutcome.NOT_APPLICABLE
                || !request.attributes().getOrDefault("framework.managed",
                        com.fasterxml.jackson.databind.node.BooleanNode.FALSE).asBoolean()) {
            result = TaskResultEvaluator.gateWithModelDecision(result, ownEvents);
        }
        if (TaskEvidenceCollector.hasNonTerminalDescendant(runs, id)) {
            result = new TaskResult(TaskOutcome.UNVERIFIED, result.unmetCriteria(),
                    "DESCENDANT_RUN_IN_PROGRESS", result.evidenceRefs(),
                    result.satisfiedCriteria());
        }
        RunState current = runs.find(id).map(stored -> stored.snapshot().state()).orElse(null);
        if (current != null && expected.contains(current)) {
            var outcome = runs.append(id, expected, current,
                    draft(request, "core.task.outcome", 3, json.valueToTree(result)), null, null);
            outcome.ifPresent(value -> { if (published != null) published.accept(value); });
            if (outcome.isPresent()) {
                reconcileVerified(id, request, runs.eventsAfter(id, 0), result, published);
            }
        }
        return result;
    }

    private void reconcileVerified(RunId id, RunRequest request,
            List<RunEventEnvelope> ownEvents, TaskResult result,
            Consumer<RunEventEnvelope> published) {
        if (result.outcome() != TaskOutcome.VERIFIED_COMPLETE) return;
        TaskContractV3 contractV3 = TaskResultEvaluator.latestContractV3(ownEvents, json).orElse(null);
        TaskContractV2 contract = contractV3 == null ? null
                : TaskResultEvaluator.desktopContract(contractV3, capabilities);
        if (contract == null) return;
        List<RunEventEnvelope> evidence = TaskEvidenceCollector.collect(runs, id);
        List<RunEventEnvelope> reconciled = VerifiedEffectReconciler.reconcile(
                runs, id, contract, evidence);
        for (RunEventEnvelope event : reconciled) {
            if (published != null) published.accept(event);
        }
        // Replaying an already persisted reconciliation also repairs a process-local desktop
        // session that missed the callback after the database commit.
        List<RunEventEnvelope> persisted = runs.eventsAfter(id, 0);
        for (TaskResultEvaluator.VerifiedActionEvidence proof
                : TaskResultEvaluator.verifiedActionEvidence(contract, evidence)) {
            boolean checkpointed = persisted.stream().anyMatch(event ->
                    event.type().equals("core.task.checkpoint_verified")
                            && event.payload().path("actionInvocationId").asText("")
                                    .equals(proof.invocationId()));
            boolean newlyReconciled = reconciled.stream().anyMatch(event ->
                    event.payload().path("actionInvocationId").asText("")
                            .equals(proof.invocationId()));
            if (checkpointed && !newlyReconciled) continue;
            if (persisted.stream().anyMatch(event -> event.type().equals("core.effect.reconciled")
                    && event.producer().equals("framework.core")
                    && event.payload().path("actionInvocationId").asText("")
                            .equals(proof.invocationId())
                    && event.payload().path("evidenceObservationId").asText("")
                            .equals(proof.evidenceObservationId()))) {
                verifiedDesktopEffect.accept(request, proof);
            }
        }
    }

    private boolean enabled(RunId id) {
        if (compiler == null) return false;
        List<RunEventEnvelope> events = runs.eventsAfter(id, 0);
        return !events.isEmpty() && events.getFirst().type().equals("core.run.created")
                && events.getFirst().payload().path("taskHarnessV3").asBoolean(false);
    }

    private static RunEventDraft draft(RunRequest request, String type, int version,
                                       com.fasterxml.jackson.databind.JsonNode payload) {
        return new RunEventDraft(type, version, "framework.core",
                request.linkage().correlationId(), null, payload);
    }
}
