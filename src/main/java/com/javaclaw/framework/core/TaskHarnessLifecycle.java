package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
        JsonNode validation = request.attributes().get(TaskContractCompiler.VALIDATION_CONTRACT_REFERENCE_ATTRIBUTE);
        if (validation != null) {
            control.throwIfCancelled();
            RunEventEnvelope frozen = validationContract(request, validation);
            var provenance = JsonNodeFactory.instance.objectNode()
                    .put("sourceRunId", frozen.runId()).put("sequence", frozen.sequence())
                    .put("sha256", validation.path("sha256").asText())
                    .put("planningExcluded", true).put("evidenceImported", false)
                    .put("scopePolicy", "local-validation-same-workspace-user");
            control.throwIfCancelled();
            runs.appendBatch(id, Set.of(RunState.RUNNING), RunState.RUNNING, List.of(
                    draft(request, "core.validation.contract_reused", 1, provenance),
                    draft(request, "core.task.contract", 3, frozen.payload())))
                    .ifPresent(batch -> batch.forEach(published));
            return;
        }
        TaskContractV3 contract = compiler.compileV3(id, request, control);
        runs.append(id, Set.of(RunState.RUNNING), RunState.RUNNING,
                draft(request, "core.task.contract", 3, json.valueToTree(contract)), null, null)
                .ifPresent(published);
    }

    private RunEventEnvelope validationContract(RunRequest request, JsonNode reference) {
        String sourceId = reference.path("sourceRunId").asText("");
        String sha256 = reference.path("sha256").asText("");
        long sequence = reference.path("sequence").asLong(-1);
        if (!reference.isObject() || reference.size() != 3
                || !reference.path("sourceRunId").isTextual() || sourceId.isBlank()
                || !reference.path("sequence").isIntegralNumber() || sequence < 1
                || !reference.path("sha256").isTextual() || !sha256.matches("[0-9a-f]{64}")
                || !sourceId.equals(System.getProperty(TaskContractCompiler.VALIDATION_CONTRACT_SOURCE_PROPERTY, "").strip())
                || !Long.toString(sequence).equals(System.getProperty(TaskContractCompiler.VALIDATION_CONTRACT_SEQUENCE_PROPERTY, "").strip())
                || !sha256.equals(System.getProperty(TaskContractCompiler.VALIDATION_CONTRACT_SHA256_PROPERTY, "").strip())
                || !validationChat(request) || !runs.readable(request.scope())
                || request.attributes().containsKey(TaskContractCompiler.ATTRIBUTE))
            throw new SecurityException("local contract reference requires an explicitly configured root chat");
        var source = runs.find(new RunId(sourceId)).orElseThrow(() ->
                new SecurityException("local validation contract source is unavailable"));
        if (!validationChat(source.request()) || !runs.readable(source.request().scope())
                || !source.request().scope().workspaceId().equals(request.scope().workspaceId())
                || !source.request().scope().userId().equals(request.scope().userId())
                || !validationText(source.request()).equals(validationText(request)))
            throw new SecurityException("local validation contract owner or exact input does not match");
        // Cross-session reuse is limited to this explicitly enabled same-user local validation.
        RunEventEnvelope frozen = runs.eventsAfter(source.snapshot().id(), sequence - 1).stream()
                .filter(event -> event.sequence() == sequence && event.runId().equals(sourceId)
                        && event.schemaVersion() == 3 && event.producer().equals("framework.core")
                        && Set.of("core.task.contract", "core.task.contract_revised").contains(event.type()))
                .findFirst().orElseThrow(() -> new SecurityException("trusted validation contract event is unavailable"));
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(frozen.payload().toString().getBytes(StandardCharsets.UTF_8)));
            if (!actual.equals(sha256)) throw new SecurityException("local validation contract SHA256 does not match");
            TaskContractV3 contract = json.treeToValue(frozen.payload(), TaskContractV3.class);
            if (!contract.originalRequest().equals(validationText(request)))
                throw new SecurityException("local validation contract original request does not match exact input");
            compiler.validateFrozenV3(request, contract);
        } catch (SecurityException invalid) { throw invalid; }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        catch (Exception invalid) { throw new SecurityException("invalid local validation contract", invalid); }
        if (!runs.readable(request.scope()) || !runs.readable(source.request().scope()))
            throw new SecurityException("local validation contract scope is no longer readable");
        return frozen;
    }

    private static boolean validationChat(RunRequest request) {
        return request.agent().id().equals("system.default") && request.profile().id().equals("chat")
                && request.source().kind().equals("chat") && request.source().id().equals("desktop")
                && request.linkage().parentRunId() == null && request.linkage().workflowRunId() == null;
    }

    private static String validationText(RunRequest request) {
        if (request.inputs().stream().anyMatch(input -> !input.type().equals("core.text")
                && !input.type().equals("core.message")))
            throw new SecurityException("local contract validation does not accept attachments or other input types");
        var text = request.inputs().stream().filter(input -> input.type().equals("core.text")).toList();
        if (text.size() != 1 || !text.getFirst().data().path("text").isTextual())
            throw new SecurityException("local contract validation requires one exact text input");
        String exact = text.getFirst().data().path("text").textValue();
        for (String key : List.of(TaskContractCompiler.ORIGINAL_REQUEST_ATTRIBUTE,
                TaskContractCompiler.RESOLVED_REQUEST_ATTRIBUTE)) {
            JsonNode override = request.attributes().get(key);
            if (override != null && (!override.isTextual() || !override.textValue().equals(exact)))
                throw new SecurityException("local contract validation cannot override the exact input");
        }
        return exact;
    }

    /** Only a new human clarification can revise an unresolved model plan on a paused Run. */
    void reviseUnreliableContract(RunId id, RunRequest request, ResumeCommand resume,
            RunControl control, Consumer<RunEventEnvelope> published) {
        if (!enabled(id) || resume == null || !humanInputType(resume.type())
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
        if (resumed == null || !humanInputType(resumed.payload().path("commandType").asText())
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

    TaskContractV3 amendInteraction(RunId id, RunRequest request, String goal, long revision,
            RunControl control, Consumer<RunEventEnvelope> published) {
        TaskContractV3 previous = TaskResultEvaluator.latestContractV3(runs.eventsAfter(id, 0), json).orElseThrow();
        InteractionExecutionPolicy.requireContiguousInteractionBlock(previous);
        var attrs = new LinkedHashMap<>(request.attributes());
        attrs.remove(TaskContractCompiler.ATTRIBUTE);
        attrs.remove(TaskContractCompiler.ORIGINAL_REQUEST_ATTRIBUTE);
        attrs.remove(TaskContractCompiler.RESOLVED_REQUEST_ATTRIBUTE);
        var planning = new RunRequest(request.agent(), request.profile(), request.source(), request.scope(),
                List.of(InputBlock.text(goal)), request.linkage(), request.permissionCeiling(), request.budget(),
                null, attrs);
        TaskContractV3 replacement = compiler.compileV3(id, planning, control);
        if (!replacement.reliable() || !replacement.applicable()
                || replacement.criteria().stream().anyMatch(criterion -> !interactionCriterion(criterion)))
            throw new IllegalArgumentException("INTERACTION_AMENDMENT_NEEDS_CLARIFICATION");
        var revisedInteraction = replacement.criteria().stream().map(criterion -> new com.javaclaw.framework.api.TaskCriterionV3(
                "ir" + revision + "." + criterion.id(), criterion.description(), criterion.capabilityId(),
                criterion.targetType(), criterion.target(), criterion.requiredEvidence(), criterion.requiredSubject(),
                criterion.requiredTextFragments(), criterion.browserTargetPhase())).toList();
        List<com.javaclaw.framework.api.TaskCriterionV3> criteria = new ArrayList<>();
        boolean inserted = false;
        for (var criterion : previous.criteria()) {
            if (interactionCriterion(criterion)) {
                if (!inserted) { criteria.addAll(revisedInteraction); inserted = true; }
            } else criteria.add(criterion);
        }
        TaskContractV3 revised = new TaskContractV3(3, previous.originalRequest() + "\n交互任务修订：" + goal,
                criteria, true, true, "model", List.of(), List.of(),
                replacement.desktopObservationPolicy(), replacement.intentStatus());
        runs.append(id, Set.of(RunState.RUNNING), RunState.RUNNING,
                new RunEventDraft("core.task.contract_revised", 3, "framework.core",
                        request.linkage().correlationId(), null, json.valueToTree(revised)), null, null)
                .ifPresent(published);
        return new TaskContractV3(3, goal, revisedInteraction, true, true, "host.interaction",
                List.of(), List.of(), replacement.desktopObservationPolicy(), replacement.intentStatus());
    }

    private static boolean interactionCriterion(com.javaclaw.framework.api.TaskCriterionV3 criterion) {
        return criterion.capabilityId().startsWith("browser.") || criterion.capabilityId().startsWith("desktop.");
    }

    private static boolean canClarify(TaskContractV3 contract) {
        return contract != null && !contract.reliable()
                && Set.of("model", "model-repair", "unknown").contains(contract.source());
    }

    private static boolean humanInputType(String type) {
        return "user.input".equals(type) || "input".equals(type);
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
                    || !humanInputType(event.payload().path("commandType").asText())) continue;
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
        if (InteractionExecutionPolicy.isInteraction(request)) {
            for (var checkpoint : BusinessEffectCheckpointVerifier.candidates(id, ownEvents, json)) {
                var event = runs.verifyBusinessEffectCheckpoint(id, checkpoint);
                if (published != null) event.ifPresent(published);
            }
            for (var proof : InteractionStageVerifier.candidates(id, runs.eventsAfter(id, 0), json)) {
                var event = runs.verifyInteractionStage(id, proof);
                if (published != null) event.ifPresent(published);
            }
        }
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
        boolean acceptanceChangedAfterOutcome = latestOutcomeSequence > 0 && ownEvents.stream()
                .anyMatch(event -> id.value().equals(event.runId())
                        && event.sequence() > latestOutcomeSequence
                        && event.producer().equals("framework.core")
                        && (event.schemaVersion() == 3
                            && (event.type().equals("core.task.contract")
                                || event.type().equals("core.task.contract_revised"))
                            || event.schemaVersion() == 1
                                && event.type().equals("core.interaction.control_accepted")
                                && event.payload().path("type").asText().equals("AMEND")));
        if (existing != null && !resumedAfterOutcome && !acceptanceChangedAfterOutcome) {
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
