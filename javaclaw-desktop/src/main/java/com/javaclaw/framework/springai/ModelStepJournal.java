package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.ModelDecisionV1;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.api.ToolExecutionStatus;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.InteractionHumanAmendment;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.core.StepEvents;
import com.javaclaw.framework.core.ToolArgumentValidationException;
import com.javaclaw.framework.core.ToolInvocationFingerprint;
import com.javaclaw.framework.core.ToolInvocationGateway;
import com.javaclaw.framework.core.ToolInvocationResult;
import com.javaclaw.framework.core.ToolRecoveryRequiredException;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.RunStore;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.javaclaw.framework.springai.PersistedToolCallCodec.callFingerprint;
import static com.javaclaw.framework.springai.PersistedToolCallCodec.recoveryRequired;
import static com.javaclaw.framework.springai.PersistedToolCallCodec.replay;

/** Provider-call journal and conservative tool continuation reconstruction. */
final class ModelStepJournal {
    private static final String REJECT_UNAVAILABLE_BATCH = PersistedToolCallCodec.UNAVAILABLE_BATCH;
    static final String LEGACY_DESKTOP_REOBSERVE = "legacy_desktop_reobserve";
    private static final int MAX_REJECTED_BATCHES = 2;
    private static final int MAX_INVALID_ARGUMENT_FEEDBACKS = 2;
    private final ReasoningRequest request;
    private final RunStepQuery steps;
    private final RunStore runs;
    private final ObjectMapper json;
    private final StepContextProjector projector;
    private final PersistedProviderTools providerTools;
    private final HarnessDecisionToolCallback decisionCallback;
    private final HarnessDecisionJournal decisionJournal;
    private final LegacyDesktopBatchRecovery legacyDesktopRecovery;
    private final BatchObservationReuse observationReuse;
    private final BrowserNavigationFeedback navigationFeedback;
    private DesktopPostClickObservation postClickObservation;
    private StepId currentModel;
    private final List<AssistantMessage.ToolCall> pendingCalls = new ArrayList<>();

    ModelStepJournal(ReasoningRequest request, RunStore runs, ObjectMapper json) {
        this.request = request;
        this.steps = new RunStepQuery(runs);
        this.runs = runs;
        this.json = json;
        this.projector = new StepContextProjector(
                request.plan().descriptor().stepContextPolicy(), json);
        this.providerTools = new PersistedProviderTools(request, steps, runs);
        this.decisionJournal = new HarnessDecisionJournal(request, steps, runs, json);
        this.decisionCallback = new HarnessDecisionToolCallback(this, json);
        this.legacyDesktopRecovery = new LegacyDesktopBatchRecovery(
                request, steps, providerTools, json);
        this.observationReuse = new BatchObservationReuse(request, steps, json);
        this.navigationFeedback = new BrowserNavigationFeedback(request, steps, json);
    }
    HarnessDecisionToolCallback decisionCallback() { return decisionCallback; }
    ToolExecutionResult afterToolBatch(ToolExecutionResult result) {
        if (postClickObservation == null || result.returnDirect()) return result;
        return ToolExecutionResult.builder().returnDirect(result.returnDirect())
                .conversationHistory(postClickObservation.afterBatch(result.conversationHistory(), currentModel)).build();
    }
    void observeCompletedClicks() {
        if (postClickObservation != null) postClickObservation.afterBatch(List.of(), currentModel);
    }
    ObjectMapper json() { return json; }
    StepId started(Prompt prompt, int attempt, String toolCandidateStepId) {
        StepId id = StepId.random();
        var input = JsonNodeFactory.instance.objectNode();
        input.set("messages", StepMessageCodec.messages(prompt.getInstructions()));
        var toolNames = input.putArray("toolNames");
        var toolFingerprints = request.plan().descriptor().onDemandContextPolicy() != null
                ? input.putObject("toolFingerprints") : null;
        int schemaCharacters = 0;
        if (prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolCallbacks() != null) {
            for (var callback : options.getToolCallbacks()) {
                String name = callback.getToolDefinition().name();
                toolNames.add(name);
                if (toolFingerprints != null) {
                    toolFingerprints.put(name, ToolCatalogSession.fingerprint(callback));
                }
                schemaCharacters = Math.addExact(schemaCharacters,
                        SpringAiToolCatalog.schemaCharacters(callback));
            }
        }
        input.put("toolSchemaCharacters", schemaCharacters);
        input.put("modelPolicy", request.plan().descriptor().modelPolicyRef());
        input.put("attempt", attempt);
        if (toolCandidateStepId != null) {
            input.put("toolCandidateStepId", toolCandidateStepId);
        }
        String causation = steps.steps(request.runId()).stream()
                .filter(step -> step.state() == AgentStep.State.COMPLETED
                        && (step.kind() == AgentStep.Kind.MODEL || step.kind() == AgentStep.Kind.TOOL))
                .max(Comparator.comparingLong(AgentStep::lastSequence)).map(step -> step.id().value()).orElse(null);
        StepEvents.started(request.events(), id, AgentStep.Kind.MODEL, input, causation);
        return id;
    }
    synchronized void completed(StepId id, ChatResponse response) {
        request.plan().observeModel(StepMessageCodec.response(response), request.runRequest(), request.runId());
        StepEvents.completed(request.events(), id,
                StepMessageCodec.response(response), StepMessageCodec.usage(response));
        currentModel = id;
        pendingCalls.clear();
        if (response.getResult() != null) pendingCalls.addAll(response.getResult().getOutput().getToolCalls());
    }
    void failed(StepId id, Throwable failure) {
        StepEvents.failed(request.events(), id, failure,
                failure instanceof ManagedInferenceChatModel.ManagedInferenceModelException managed
                        ? StepMessageCodec.failureUsage(managed) : null);
    }

    synchronized String invocationId(String name, JsonNode arguments) {
        for (int index = 0; index < pendingCalls.size(); index++) {
            var call = pendingCalls.get(index);
            if (!call.name().equals(name)) continue;
            JsonNode candidate;
            try { candidate = SpringAiToolCallback.parseArguments(json, call.arguments()); }
            catch (ToolArgumentValidationException malformed) { continue; }
            if (candidate.equals(arguments)) {
                pendingCalls.remove(index);
                return invocationId(currentModel, call);
            }
        }
        throw new IllegalStateException("tool callback is not associated with a durable model response: " + name);
    }

    /** Persist the model's control proposal separately from its display prose. */
    synchronized JsonNode submitDecision(ModelDecisionV1 decision, JsonNode arguments) {
        if (currentModel == null || pendingCalls.size() != 1
                || !HarnessDecisionToolCallback.NAME.equals(pendingCalls.getFirst().name())) {
            throw new ToolRecoveryRequiredException(request.runId().value(),
                    "a harness decision must be the sole call in its provider batch");
        }
        decisionJournal.validateUnmetCriterionIds(decision);
        decisionJournal.validateEvidenceRefs(decision);
        String invocation = invocationId(HarnessDecisionToolCallback.NAME, arguments);
        return decisionJournal.persistDecision(currentModel, invocation, decision, arguments);
    }

    /** Invalid control arguments are durable feedback, never a completion proposal. */
    synchronized JsonNode rejectInvalidDecision(String rawArguments, Exception invalid) {
        if (currentModel == null || pendingCalls.size() != 1
                || !HarnessDecisionToolCallback.NAME.equals(pendingCalls.getFirst().name())
                || !java.util.Objects.equals(
                        pendingCalls.getFirst().arguments(), rawArguments)) {
            throw new ToolRecoveryRequiredException(request.runId().value(),
                    "invalid harness decision has no sole durable provider call");
        }
        AssistantMessage.ToolCall call = pendingCalls.removeFirst();
        return decisionJournal.persistInvalidDecision(currentModel, invocationId(currentModel, call),
                decisionJournal.invalidDecisionFeedback(invalid, rawArguments));
    }

    synchronized ToolExecutionResult rejectUnavailableToolBatch(Prompt prompt, AssistantMessage assistant) {
        if (currentModel == null || !pendingCalls.equals(assistant.getToolCalls())) {
            throw new IllegalStateException("unavailable tool batch has no durable model response");
        }
        AgentStep modelStep = steps.step(request.runId(), currentModel).orElseThrow(() ->
                new ToolRecoveryRequiredException(currentModel.value(),
                        "Persisted provider prompt is unavailable for the rejected tool batch"));
        List<String> offeredTools = providerTools.offeredNames(modelStep);
        List<String> requestedTools = assistant.getToolCalls().stream()
                .map(AssistantMessage.ToolCall::name).distinct().toList();
        if (consecutiveRejectedBatches() >= MAX_REJECTED_BATCHES) {
            throw new ToolRecoveryRequiredException(currentModel.value(),
                    "Model repeatedly requested tools absent from its provider prompt; "
                            + "requested=" + requestedTools + "; offered=" + offeredTools
                            + "; no calls in this batch were executed; review step "
                            + currentModel.value(), requestedTools, offeredTools);
        }
        return persistUnavailableToolBatch(prompt.getInstructions(), assistant,
                modelStep, offeredTools);
    }

    /** Count only rejected batches since the last completed business tool with a receipt. */
    long consecutiveRejectedBatches() {
        return ModelStepRejectionHistory.count(request.runId(), steps, runs,
                REJECT_UNAVAILABLE_BATCH);
    }

    private ToolExecutionResult persistUnavailableToolBatch(
            List<Message> promptMessages, AssistantMessage assistant, AgentStep modelStep,
            List<String> offeredTools) {
        Set<String> callIds = new HashSet<>();
        for (var call : assistant.getToolCalls()) {
            if (!callIds.add(call.id())) {
                throw recoveryRequired(modelStep, "model returned duplicate tool call IDs");
            }
            StepId id = StepId.tool(request.runId(), invocationId(modelStep.id(), call));
            if (steps.step(request.runId(), id).isPresent()) {
                throw new ToolRecoveryRequiredException(id.value());
            }
        }
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (var call : assistant.getToolCalls()) {
            StepId id = StepId.tool(request.runId(), invocationId(modelStep.id(), call));
            var input = JsonNodeFactory.instance.objectNode();
            input.put("phase", REJECT_UNAVAILABLE_BATCH);
            input.put("modelStepId", modelStep.id().value());
            input.put("callId", call.id());
            input.put("toolName", call.name());
            input.put("callFingerprint", callFingerprint(call));
            StepEvents.started(request.events(), id, AgentStep.Kind.ORCHESTRATION,
                    input, modelStep.id().value());
            var feedback = UnavailableToolFeedback.current(call, offeredTools);
            var output = JsonNodeFactory.instance.objectNode();
            output.put("rejectedUnavailableToolBatch", true);
            output.set("modelOutput", feedback);
            output.put("durationMillis", 0);
            StepEvents.completed(request.events(), id, output, null);
            responses.add(new ToolResponseMessage.ToolResponse(
                    call.id(), call.name(), feedback.toString()));
        }
        List<Message> messages = new ArrayList<>(promptMessages);
        messages.add(assistant);
        messages.add(ToolResponseMessage.builder().responses(responses).build());
        return ToolExecutionResult.builder().conversationHistory(messages).returnDirect(false).build();
    }

    /** A durable hint for selecting the next provider tools after an unavailable-tool batch. */
    PendingUnavailableRepair pendingUnavailableRepair(ToolCatalogSession catalog) {
        AgentStep last = steps.steps(request.runId()).stream()
                .filter(step -> step.kind() == AgentStep.Kind.MODEL)
                .max(Comparator.comparingLong(AgentStep::startSequence)).orElse(null);
        if (last == null || last.state() != AgentStep.State.COMPLETED
                || last.input() == null || last.output() == null
                || !last.output().has("message")) return null;
        if (providerTools.inputRedacted(last.id()) || providerTools.outputRedacted(last.id())) {
            throw recoveryRequired(last, "persisted unavailable-tool model step was redacted");
        }
        // A pre-observation-token desktop action has already been rejected as a
        // whole batch during recovery. Its old schema is never restored or run.
        if (legacyDesktopRecovery.legacyDesktopBatch(last)) return null;
        providerTools.validate(last, catalog, decisionCallback);
        AssistantMessage assistant = (AssistantMessage) StepMessageCodec.message(
                last.output().path("message"));
        if (assistant.getToolCalls().isEmpty()) return null;
        List<String> offered = providerTools.offeredNames(last);
        Set<String> visible = new HashSet<>(offered);
        if (assistant.getToolCalls().stream().allMatch(call -> visible.contains(call.name()))) {
            return null;
        }
        boolean rejected = rejectedUnavailableBatch(last, assistant.getToolCalls());
        if (!rejected && !pausedUnavailableBatchWithoutExecution(last, assistant.getToolCalls())) {
            return null;
        }
        List<String> requested = assistant.getToolCalls().stream()
                .map(AssistantMessage.ToolCall::name).distinct().toList();
        return new PendingUnavailableRepair(last.id().value(), requested, offered, rejected);
    }

    private boolean pausedUnavailableBatchWithoutExecution(
            AgentStep modelStep, List<AssistantMessage.ToolCall> calls) {
        Set<String> ids = new HashSet<>();
        for (var call : calls) {
            if (!ids.add(call.id()) || steps.step(request.runId(), StepId.tool(
                    request.runId(), invocationId(modelStep.id(), call))).isPresent()) {
                return false;
            }
        }
        return runs.eventsAfter(request.runId(), modelStep.lastSequence()).stream()
                .anyMatch(event -> event.type().equals("core.run.paused")
                        && event.payload().path("output").path("kind").asText()
                                .equals("tool.recovery_required")
                        && event.payload().path("output").path("stepId").asText()
                                .equals(modelStep.id().value()));
    }
    ToolInvocationResult invoke(FrameworkTool tool, JsonNode arguments, ToolInvocationGateway gateway) {
        String invocation = invocationId(tool.descriptor().name(), arguments);
        return invoke(tool, arguments, gateway, invocation, currentModel);
    }

    ObjectNode invokeForModel(
            FrameworkTool tool, JsonNode arguments, ToolInvocationGateway gateway) {
        String invocation = invocationId(tool.descriptor().name(), arguments);
        ToolInvocationResult result = invoke(tool, arguments, gateway, invocation, currentModel);
        return SpringAiToolCallback.modelVisibleResult(
                result, runs, request.runId(), observationReuse.evidenceInvocation(invocation));
    }

    /** An invalid raw call has an exact durable identity, but no parsed arguments or dispatch. */
    synchronized ObjectNode rejectMalformedArgumentsForModel(FrameworkTool tool, String rawArguments,
            ToolArgumentValidationException invalid) {
        for (int index = 0; index < pendingCalls.size(); index++) {
            var call = pendingCalls.get(index);
            if (!call.name().equals(tool.descriptor().name())
                    || !java.util.Objects.equals(call.arguments(), rawArguments)) continue;
            pendingCalls.remove(index);
            String invocation = invocationId(currentModel, call);
            return SpringAiToolCallback.modelVisibleResult(rejectInvalidArguments(tool,
                    JsonNodeFactory.instance.nullNode(), invocation, currentModel, invalid, callFingerprint(call)));
        }
        throw new IllegalStateException("invalid tool JSON is not associated with a durable model response: "
                + tool.descriptor().name());
    }
    private ToolInvocationResult invoke(FrameworkTool tool, JsonNode arguments,
                                        ToolInvocationGateway gateway, String invocation, StepId modelStep) {
        StepId id = StepId.tool(request.runId(), invocation);
        var existing = steps.step(request.runId(), id);
        if (existing.isPresent()) {
            AgentStep step = existing.get();
            if (BatchObservationReuse.isAlias(step)) {
                return observationReuse.reuse(tool, arguments, invocation, modelStep, step);
            }
            if (BrowserNavigationFeedback.isFeedback(step)) {
                return navigationFeedback.replay(tool, arguments, invocation, modelStep, step);
            }
            if (step.kind() != AgentStep.Kind.TOOL
                    || step.state() != AgentStep.State.COMPLETED) {
                throw new ToolRecoveryRequiredException(id.value());
            }
            com.javaclaw.framework.core.BrowserUserInputRecovery.restore(
                    request.runRequest(), runs, request.runId(), step, tool);
            return replay(step);
        }
        ToolInvocationResult reused = observationReuse.reuse(tool, arguments, invocation, modelStep, null);
        if (reused != null) return reused;
        try {
            return SpringAiToolCallback.invoke(tool, arguments, request, gateway, invocation);
        } catch (ToolArgumentValidationException invalid) {
            return rejectInvalidArguments(tool, arguments, invocation, modelStep, invalid, null);
        } catch (com.javaclaw.framework.core.PendingEffectObservationRequiredException pending) {
            return navigationFeedback.reject(tool, arguments, invocation, modelStep, pending);
        }
    }

    private ToolInvocationResult rejectInvalidArguments(
            FrameworkTool tool, JsonNode arguments, String invocation,
            StepId modelStep, ToolArgumentValidationException invalid, String malformedCallFingerprint) {
        long rejected = steps.steps(request.runId()).stream()
                .filter(step -> step.kind() == AgentStep.Kind.TOOL
                        && step.state() == AgentStep.State.COMPLETED
                        && step.output().path("validationRejected").asBoolean(false))
                .count();
        if (rejected >= MAX_INVALID_ARGUMENT_FEEDBACKS) {
            throw new ToolRecoveryRequiredException(
                    StepId.tool(request.runId(), invocation).value(),
                    "Model repeatedly supplied invalid tool arguments; review and correct "
                            + "the tool call before continuing");
        }
        var descriptor = tool.descriptor();
        ToolInvocationResult rejectedResult = SpringAiToolCallback.invalidArgumentsResult(
                tool, invalid);
        JsonNode feedback = rejectedResult.output();
        StepId id = StepId.tool(request.runId(), invocation);
        var input = JsonNodeFactory.instance.objectNode()
                .put("tool", descriptor.name())
                .put("invocationId", invocation)
                .put("fingerprint", ToolInvocationFingerprint.create(
                        descriptor.name(), arguments));
        input.set("arguments", arguments);
        if (malformedCallFingerprint != null) input.put("argumentJsonInvalid", true)
                .put("callFingerprint", malformedCallFingerprint);
        StepEvents.started(request.events(), id, AgentStep.Kind.TOOL, input,
                modelStep == null ? null : modelStep.value());
        var output = JsonNodeFactory.instance.objectNode()
                .put("validationRejected", true)
                .put("durationMillis", 0)
                .put("status", rejectedResult.status().name())
                .put("errorCode", rejectedResult.errorCode())
                .put("displayMessage", rejectedResult.displayMessage());
        output.set("rawOutput", feedback);
        output.set("modelOutput", feedback);
        StepEvents.completed(request.events(), id, output, null);
        request.events().emit("core.tool.arguments_rejected", 1,
                "framework.springai", JsonNodeFactory.instance.objectNode()
                        .put("tool", descriptor.name())
                        .put("invocationId", invocation)
                        .set("feedback", feedback));
        return rejectedResult;
    }

    /** Uses the latest actual provider request, including all preceding tool response messages. */
    private boolean humanContractRevisionAfter(long modelSequence) {
        if (InteractionHumanAmendment.latest(runs, json, request.runId())
                .filter(revision -> revision.contractSequence() > modelSequence).isPresent()) return true;
        List<RunEventEnvelope> events = runs.eventsAfter(request.runId(), modelSequence);
        for (RunEventEnvelope revision : events) {
            if (!revision.type().equals("core.task.contract_revised") || revision.schemaVersion() != 3
                    || !revision.producer().equals("framework.core")
                    || revision.payload().path("version").asInt() != 3
                    || !java.util.Set.of("model", "model-repair", "unknown")
                            .contains(revision.payload().path("source").asText())) continue;
            if (events.stream().anyMatch(resumed -> resumed.sequence() < revision.sequence()
                    && resumed.type().equals("core.run.resumed") && resumed.schemaVersion() == 1
                    && resumed.producer().equals("framework.core")
                    && ("core.run.resumed:" + resumed.sequence()).equals(revision.causationId())
                    && java.util.Set.of("user.input", "input")
                            .contains(resumed.payload().path("commandType").asText())
                    && resumed.payload().path("command").path("text").isTextual()
                    && !resumed.payload().path("command").path("text").asText().isBlank())) return true;
        }
        return false;
    }

    Recovery recover(List<FrameworkTool> tools, ToolInvocationGateway gateway,
            boolean appendResume) {
        return recover(tools, gateway, null, appendResume);
    }

    Recovery recover(List<FrameworkTool> tools, ToolInvocationGateway gateway,
            ToolCatalogSession catalog, boolean appendResume) {
        postClickObservation = new DesktopPostClickObservation(request, runs, json, tools, gateway);
        List<AgentStep> history = steps.steps(request.runId());
        AgentStep last = null;
        for (AgentStep step : history) if (step.kind() == AgentStep.Kind.MODEL) last = step;
        if (last == null) return null;
        long lastModelSequence = last.lastSequence();
        if (InteractionHumanAmendment.acceptedAfter(runs, request.runId(), lastModelSequence)
                && InteractionHumanAmendment.latest(runs, json, request.runId())
                    .filter(revision -> revision.contractSequence() > lastModelSequence).isEmpty()) {
            throw recoveryRequired(last, "interaction amendment provenance is incomplete; old task cannot be replayed");
        }
        if (humanContractRevisionAfter(last.lastSequence())) {
            // Keep durable receipts and effect fences, but do not dispatch the old goal's batch.
            return null;
        }
        if (providerTools.inputRedacted(last.id())) {
            throw new ToolRecoveryRequiredException(last.id().value(),
                    "Persisted provider prompt was redacted; reconcile before replaying model step "
                            + last.id().value());
        }
        if (last.input() == null || !last.input().path("messages").isArray()
                || !last.input().path("toolNames").isArray()) {
            throw recoveryRequired(last, "persisted provider prompt or tool directory is unavailable");
        }
        boolean legacyDesktopBatch = legacyDesktopRecovery.legacyDesktopBatch(last);
        if (!legacyDesktopBatch) providerTools.validate(last, catalog, decisionCallback);
        postClickObservation.afterBatch(List.of(), last.id());
        List<Message> messages = new ArrayList<>(StepMessageCodec.messages(last.input().path("messages")));
        if (request.plan().descriptor().stepContextPolicy() != null
                || OriginalTaskSnapshot.hasAttachments(request)
                || messages.stream().anyMatch(message -> message instanceof UserMessage user
                        && SpringAiPromptFactory.isOriginalTask(user))) {
            try {
                messages = OriginalTaskSnapshot.restore(request, messages);
            } catch (IllegalStateException invalid) {
                throw recoveryRequired(last, invalid.getMessage());
            }
        }
        ChatResponse finalResponse = null;
        RunEventEnvelope protocolRepair = null;
        if (last.state() == AgentStep.State.COMPLETED && last.output().has("message")) {
            AssistantMessage assistant = (AssistantMessage) StepMessageCodec.message(last.output().path("message"));
            var matchedRepair = HarnessProtocolRepairContext.recover(
                    request.runId(), runs, last, history, assistant);
            if (matchedRepair.isPresent()) {
                if (providerTools.outputRedacted(last.id())) {
                    throw recoveryRequired(last, "protocol repair model output was redacted");
                }
                protocolRepair = matchedRepair.get();
                messages.add(TaskRepairContext.fromEvent(
                        protocolRepair, request.runId().value(), last.id().value()));
            } else {
                messages.add(assistant);
            }
            if (protocolRepair == null && assistant.getToolCalls().isEmpty()) {
                finalResponse = new ChatResponse(List.of(new Generation(assistant)),
                        org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                                .model(last.output().path("model").asText(request.plan().descriptor().modelPolicyRef())).build());
            } else if (protocolRepair == null) {
                long decisions = assistant.getToolCalls().stream()
                        .filter(call -> HarnessDecisionToolCallback.NAME.equals(call.name())).count();
                if (decisions > 0 && (decisions != 1 || assistant.getToolCalls().size() != 1)) {
                    throw new ToolRecoveryRequiredException(last.id().value(),
                            "persisted harness decision batch contains other calls; no pending call was executed");
                }
                if (legacyDesktopBatch) legacyDesktopRecovery.persistLegacyDesktopBatch(
                        last, assistant.getToolCalls());
                boolean rejectedLegacy = legacyDesktopRecovery.rejectedLegacyDesktopBatch(last);
                boolean rejected = !rejectedLegacy
                        && rejectedUnavailableBatch(last, assistant.getToolCalls());
                if (!rejected && !rejectedLegacy && pausedUnavailableBatchWithoutExecution(
                        last, assistant.getToolCalls())) {
                    if (providerTools.outputRedacted(last.id())) {
                        throw recoveryRequired(last,
                                "paused unavailable-tool model output was redacted");
                    }
                    List<String> offered = providerTools.offeredNames(last);
                    if (assistant.getToolCalls().stream().allMatch(call ->
                            offered.contains(call.name()))) {
                        throw recoveryRequired(last,
                                "paused unavailable-tool batch has no unadvertised call");
                    }
                    persistUnavailableToolBatch(messages.subList(0, messages.size() - 1),
                            assistant, last, offered);
                    rejected = rejectedUnavailableBatch(last, assistant.getToolCalls());
                }
                if (!rejected && !rejectedLegacy) {
                    validatePersistedToolVisibility(last, assistant.getToolCalls());
                }
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                boolean redacted = providerTools.outputRedacted(last.id());
                for (var call : assistant.getToolCalls()) {
                    String invocation = invocationId(last.id(), call);
                    StepId toolStep = StepId.tool(request.runId(), invocation);
                    var persisted = steps.step(request.runId(), toolStep);
                    ToolInvocationResult result;
                    if (persisted.isPresent()) {
                        if (persisted.get().state() != AgentStep.State.COMPLETED) {
                            if (!HarnessDecisionToolCallback.NAME.equals(call.name()) || redacted) {
                                throw new ToolRecoveryRequiredException(toolStep.value());
                            }
                            result = decisionJournal.recoverDecision(last.id(), invocation, call.arguments());
                            responses.add(new ToolResponseMessage.ToolResponse(
                                    call.id(), call.name(), result.output().toString()));
                            continue;
                        }
                        if (!rejected && !rejectedLegacy
                                && persisted.get().kind() != AgentStep.Kind.TOOL
                                && !BatchObservationReuse.isAlias(persisted.get())
                                && !BrowserNavigationFeedback.isFeedback(persisted.get())
                                && !(HarnessDecisionToolCallback.NAME.equals(call.name())
                                    && persisted.get().kind() == AgentStep.Kind.ORCHESTRATION))
                            throw new ToolRecoveryRequiredException(toolStep.value());
                        // A control response is usable only with its separate durable decision event.
                        if (HarnessDecisionToolCallback.NAME.equals(call.name())) {
                            result = decisionJournal.recoverDecision(last.id(), invocation, call.arguments());
                        } else if (BatchObservationReuse.isAlias(persisted.get())) {
                            FrameworkTool tool = tools.stream().filter(candidate ->
                                    candidate.descriptor().name().equals(call.name())).findFirst().orElseThrow(() ->
                                    new ToolRecoveryRequiredException(toolStep.value()));
                            result = observationReuse.reuse(tool, parse(call.arguments()),
                                    invocation, last.id(), persisted.get());
                        } else if (BrowserNavigationFeedback.isFeedback(persisted.get())) {
                            FrameworkTool tool = tools.stream().filter(candidate ->
                                    candidate.descriptor().name().equals(call.name())).findFirst().orElseThrow(() ->
                                    new ToolRecoveryRequiredException(toolStep.value()));
                            result = navigationFeedback.replay(tool, parse(call.arguments()),
                                    invocation, last.id(), persisted.get());
                        } else {
                            // Completed work needs only its processed result, never the original credentials.
                            if (com.javaclaw.framework.core.BrowserUserInputRecovery.candidate(request.runRequest(), persisted.get())) {
                                FrameworkTool tool = tools.stream().filter(candidate -> candidate.descriptor().name().equals(call.name()))
                                        .findFirst().orElseThrow(() -> new ToolRecoveryRequiredException(toolStep.value()));
                                com.javaclaw.framework.core.BrowserUserInputRecovery.restore(
                                        request.runRequest(), runs, request.runId(), persisted.get(), tool);
                            }
                            result = replay(persisted.get());
                        }
                    } else {
                        if (redacted) throw new ToolRecoveryRequiredException(toolStep.value(),
                                "Pending tool input contains redacted credentials; reconcile the input before continuing step " + toolStep.value());
                        if (HarnessDecisionToolCallback.NAME.equals(call.name())) {
                            result = decisionJournal.recoverDecision(last.id(), invocation, call.arguments());
                        } else {
                            FrameworkTool tool = tools.stream().filter(candidate ->
                                    candidate.descriptor().name().equals(call.name())).findFirst().orElseThrow(() ->
                                    new IllegalStateException("persisted tool no longer exists: " + call.name()));
                            JsonNode arguments;
                            try {
                                arguments = SpringAiToolCallback.parseArguments(json, call.arguments());
                            } catch (ToolArgumentValidationException invalid) {
                                result = rejectInvalidArguments(tool, JsonNodeFactory.instance.nullNode(),
                                        invocation, last.id(), invalid, callFingerprint(call));
                                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                                        SpringAiToolCallback.modelVisibleResult(result).toString()));
                                continue;
                            }
                            result = invoke(tool, arguments, gateway, invocation, last.id());
                        }
                    }
                    String visible = HarnessDecisionToolCallback.NAME.equals(call.name())
                            || rejected || rejectedLegacy
                            ? result.output().toString()
                            : SpringAiToolCallback.modelVisibleResult(
                                    result, runs, request.runId(), observationReuse.evidenceInvocation(invocation)).toString();
                    responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), visible));
                }
                messages.add(ToolResponseMessage.builder().responses(responses).build());
                if (!rejected && !rejectedLegacy) messages = new ArrayList<>(
                        postClickObservation.afterBatch(messages, last.id()));
                if (!rejected && !rejectedLegacy && decisions == 1) {
                    // returnDirect control calls terminate the live provider loop. A restart
                    // after that call must use its persisted decision, not ask the model again.
                    finalResponse = new ChatResponse(List.of(new Generation(assistant)),
                            org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                                    .model(last.output().path("model").asText(
                                            request.plan().descriptor().modelPolicyRef())).build());
                }
            }
        }
        // A completion check can reject an otherwise final model response. The feedback is
        // durable and tied to that exact model step, so recovery continues the same Run
        // without accepting the old final answer or repeating an already completed tool.
        if (last.state() == AgentStep.State.COMPLETED && protocolRepair == null) {
            String finalModelStepId = last.id().value();
            var repair = runs.eventsAfter(request.runId(), last.lastSequence()).stream()
                    .filter(event -> TaskRepairContext.trusted(
                            event, request.runId().value(), finalModelStepId))
                    .findFirst();
            if (repair.isPresent()) {
                String feedback = repair.get().payload().path("feedback").asText("");
                if (feedback.isBlank()) {
                    throw recoveryRequired(last, "task repair feedback is unavailable");
                }
                messages.add(TaskRepairContext.fromEvent(
                        repair.get(), request.runId().value(), finalModelStepId));
                finalResponse = null;
            }
        }
        UserMessage resume = appendResume
                ? SpringAiPromptFactory.resumeCommandMessage(request, messages, runs) : null;
        if (resume != null) {
            // 同一恢复命令已在持久化提示中出现时复用，不能重复添加必保留消息。
            if (!messages.contains(resume)) messages.add(resume);
            finalResponse = null;
        }
        boolean replayPrompt = last.state() != AgentStep.State.COMPLETED && resume == null;
        if (!replayPrompt) {
            messages = decisionJournal.restoreCompletedDecisionArguments(messages, history, last);
        }
        StepContextProjector.Projection projection;
        if (replayPrompt || request.plan().descriptor().onDemandContextPolicy() != null) {
            int characters = messages.stream().mapToInt(StepContextProjector::characters).sum();
            projection = new StepContextProjector.Projection(List.copyOf(messages),
                    new StepContextProjector.Statistics(messages.size(), messages.size(),
                            characters, characters, 0, false));
        } else {
            projection = projector.project(messages);
        }
        String system = projection.messages().stream()
                .filter(SystemMessage.class::isInstance)
                .map(Message::getText)
                .collect(java.util.stream.Collectors.joining("\n\n"));
        List<Message> projectedMessages = projection.messages().stream()
                .filter(message -> !(message instanceof SystemMessage))
                .toList();
        List<String> visibleTools = new ArrayList<>();
        last.input().path("toolNames").forEach(value -> {
            if (value.isTextual()) visibleTools.add(value.asText());
        });
        return new Recovery(
                system, projectedMessages, projection.statistics(), finalResponse,
                replayPrompt,
                List.copyOf(visibleTools),
                last.input().path("toolCandidateStepId").isTextual()
                        ? last.input().path("toolCandidateStepId").asText() : null,
                projection.messages());
    }

    private void validatePersistedToolVisibility(
            AgentStep modelStep, List<AssistantMessage.ToolCall> calls) {
        PersistedToolCallCodec.validatePersistedToolVisibility(request.runId(), modelStep, calls);
    }

    private boolean rejectedUnavailableBatch(
            AgentStep modelStep, List<AssistantMessage.ToolCall> calls) {
        List<AgentStep> persisted = new ArrayList<>();
        for (var call : calls) {
            StepId id = StepId.tool(request.runId(), invocationId(modelStep.id(), call));
            steps.step(request.runId(), id).ifPresent(persisted::add);
        }
        if (persisted.stream().noneMatch(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                && step.input() != null && REJECT_UNAVAILABLE_BATCH.equals(
                        step.input().path("phase").asText()))) {
            return false;
        }
        Set<String> visible = new HashSet<>();
        modelStep.input().path("toolNames").forEach(name -> {
            if (name.isTextual()) visible.add(name.asText());
        });
        if (persisted.size() != calls.size()
                || calls.stream().allMatch(call -> visible.contains(call.name()))) {
            throw recoveryRequired(modelStep, "persisted unavailable-tool rejection is incomplete");
        }
        List<String> offeredTools = providerTools.offeredNames(modelStep);
        for (var call : calls) {
            StepId id = StepId.tool(request.runId(), invocationId(modelStep.id(), call));
            AgentStep step = steps.step(request.runId(), id).orElseThrow(() ->
                    recoveryRequired(modelStep, "persisted unavailable-tool rejection is incomplete"));
            if (step.kind() != AgentStep.Kind.ORCHESTRATION
                    || step.state() != AgentStep.State.COMPLETED
                    || step.input() == null || step.output() == null
                    || !REJECT_UNAVAILABLE_BATCH.equals(step.input().path("phase").asText())
                    || !modelStep.id().value().equals(step.input().path("modelStepId").asText())
                    || !call.id().equals(step.input().path("callId").asText())
                    || !call.name().equals(step.input().path("toolName").asText())
                    || !callFingerprint(call).equals(step.input().path("callFingerprint").asText())
                    || !step.output().path("rejectedUnavailableToolBatch").asBoolean(false)
                    || !(UnavailableToolFeedback.current(call, offeredTools)
                            .equals(step.output().path("modelOutput"))
                            || UnavailableToolFeedback.previous(call, offeredTools)
                            .equals(step.output().path("modelOutput"))
                            || UnavailableToolFeedback.legacy(call)
                            .equals(step.output().path("modelOutput")))
                    || providerTools.inputRedacted(id) || providerTools.outputRedacted(id)) {
                throw recoveryRequired(modelStep, "persisted unavailable-tool rejection has changed");
            }
        }
        return true;
    }

    private static String invocationId(StepId model, AssistantMessage.ToolCall call) {
        return PersistedToolCallCodec.invocationId(model, call);
    }

    private JsonNode parse(String value) {
        return PersistedToolCallCodec.parse(json, value);
    }

    record Recovery(
            String systemPrompt,
            List<Message> messages,
            StepContextProjector.Statistics statistics,
            ChatResponse finalResponse,
            boolean replayPrompt,
            List<String> toolNames,
            String toolCandidateStepId,
            List<Message> providerMessages) {
        Recovery {
            messages = List.copyOf(messages);
            toolNames = List.copyOf(toolNames);
            providerMessages = List.copyOf(providerMessages);
        }

        /** Compatibility for callers without a separately frozen provider message list. */
        Recovery(String systemPrompt, List<Message> messages,
                StepContextProjector.Statistics statistics, ChatResponse finalResponse,
                boolean replayPrompt, List<String> toolNames, String toolCandidateStepId) {
            this(systemPrompt, messages, statistics, finalResponse, replayPrompt,
                    toolNames, toolCandidateStepId, legacyProviderMessages(systemPrompt, messages));
        }

        /** Exact system role boundaries and host metadata, before any next-step rebuilding. */
        List<SystemMessage> systemMessages() {
            return providerMessages.stream().filter(SystemMessage.class::isInstance)
                    .map(SystemMessage.class::cast).toList();
        }

        private static List<Message> legacyProviderMessages(String systemPrompt,
                List<Message> messages) {
            List<Message> provider = new ArrayList<>();
            if (!systemPrompt.isBlank()) provider.add(new SystemMessage(systemPrompt));
            provider.addAll(messages);
            return List.copyOf(provider);
        }
    }
    record PendingUnavailableRepair(String modelStepId, List<String> requestedNames,
                                    List<String> offeredNames, boolean alreadyRejected) { }
}
