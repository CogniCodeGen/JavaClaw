package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.core.StepEvents;
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

/** Provider-call journal and conservative tool continuation reconstruction. */
final class ModelStepJournal {
    private static final String REJECT_UNAVAILABLE_BATCH = "reject_unavailable_tool_batch";
    private static final int MAX_REJECTED_BATCHES = 2;
    private final ReasoningRequest request;
    private final RunStepQuery steps;
    private final RunStore runs;
    private final ObjectMapper json;
    private final StepContextProjector projector;
    private StepId currentModel;
    private final List<AssistantMessage.ToolCall> pendingCalls = new ArrayList<>();

    ModelStepJournal(ReasoningRequest request, RunStore runs, ObjectMapper json) {
        this.request = request;
        this.steps = new RunStepQuery(runs);
        this.runs = runs;
        this.json = json;
        this.projector = new StepContextProjector(request.plan().descriptor().stepContextPolicy());
    }
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
            if (call.name().equals(name) && parse(call.arguments()).equals(arguments)) {
                pendingCalls.remove(index);
                return invocationId(currentModel, call);
            }
        }
        throw new IllegalStateException("tool callback is not associated with a durable model response: " + name);
    }
    private static String invocationId(StepId model, AssistantMessage.ToolCall call) {
        return "model/" + model.value() + "/" + call.id();
    }

    synchronized ToolExecutionResult rejectUnavailableToolBatch(Prompt prompt, AssistantMessage assistant) {
        if (currentModel == null || !pendingCalls.equals(assistant.getToolCalls())) {
            throw new IllegalStateException("unavailable tool batch has no durable model response");
        }
        long previousBatches = steps.steps(request.runId()).stream()
                .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                        && REJECT_UNAVAILABLE_BATCH.equals(step.input().path("phase").asText()))
                .map(step -> step.input().path("modelStepId").asText())
                .distinct().count();
        if (previousBatches >= MAX_REJECTED_BATCHES) {
            throw new ToolRecoveryRequiredException(currentModel.value(),
                    "Model repeatedly requested tools absent from its provider prompt; review step "
                            + currentModel.value());
        }
        Set<String> callIds = new HashSet<>();
        for (var call : assistant.getToolCalls()) {
            if (!callIds.add(call.id())) {
                throw new ToolRecoveryRequiredException(currentModel.value(),
                        "Model returned duplicate tool call IDs; review step " + currentModel.value());
            }
            StepId id = StepId.tool(request.runId(), invocationId(currentModel, call));
            if (steps.step(request.runId(), id).isPresent()) {
                throw new ToolRecoveryRequiredException(id.value());
            }
        }
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (var call : assistant.getToolCalls()) {
            StepId id = StepId.tool(request.runId(), invocationId(currentModel, call));
            var input = JsonNodeFactory.instance.objectNode();
            input.put("phase", REJECT_UNAVAILABLE_BATCH);
            input.put("modelStepId", currentModel.value());
            input.put("callId", call.id());
            input.put("toolName", call.name());
            input.put("callFingerprint", callFingerprint(call));
            StepEvents.started(request.events(), id, AgentStep.Kind.ORCHESTRATION,
                    input, currentModel.value());
            var feedback = unavailableToolFeedback(call);
            var output = JsonNodeFactory.instance.objectNode();
            output.put("rejectedUnavailableToolBatch", true);
            output.set("modelOutput", feedback);
            output.put("durationMillis", 0);
            StepEvents.completed(request.events(), id, output, null);
            responses.add(new ToolResponseMessage.ToolResponse(
                    call.id(), call.name(), feedback.toString()));
        }
        List<Message> messages = new ArrayList<>(prompt.getInstructions());
        messages.add(assistant);
        messages.add(ToolResponseMessage.builder().responses(responses).build());
        return ToolExecutionResult.builder().conversationHistory(messages).returnDirect(false).build();
    }
    ToolInvocationResult invoke(FrameworkTool tool, JsonNode arguments, ToolInvocationGateway gateway) {
        String invocation = invocationId(tool.descriptor().name(), arguments);
        return invoke(tool, arguments, gateway, invocation);
    }
    private ToolInvocationResult invoke(FrameworkTool tool, JsonNode arguments,
                                        ToolInvocationGateway gateway, String invocation) {
        StepId id = StepId.tool(request.runId(), invocation);
        var existing = steps.step(request.runId(), id);
        if (existing.isPresent()) {
            AgentStep step = existing.get();
            if (step.kind() != AgentStep.Kind.TOOL
                    || step.state() != AgentStep.State.COMPLETED) {
                throw new ToolRecoveryRequiredException(id.value());
            }
            return replay(step);
        }
        return SpringAiToolCallback.invoke(tool, arguments, request, gateway, invocation);
    }

    /** Uses the latest actual provider request, including all preceding tool response messages. */
    Recovery recover(List<FrameworkTool> tools, ToolInvocationGateway gateway,
            boolean appendResume) {
        return recover(tools, gateway, null, appendResume);
    }

    Recovery recover(List<FrameworkTool> tools, ToolInvocationGateway gateway,
            ToolCatalogSession catalog, boolean appendResume) {
        List<AgentStep> history = steps.steps(request.runId());
        AgentStep last = null;
        for (AgentStep step : history) if (step.kind() == AgentStep.Kind.MODEL) last = step;
        if (last == null) return null;
        if (modelInputRedacted(last.id())) {
            throw new ToolRecoveryRequiredException(last.id().value(),
                    "Persisted provider prompt was redacted; reconcile before replaying model step "
                            + last.id().value());
        }
        if (last.input() == null || !last.input().path("messages").isArray()
                || !last.input().path("toolNames").isArray()) {
            throw recoveryRequired(last, "persisted provider prompt or tool directory is unavailable");
        }
        validateProviderTools(last, catalog);
        List<Message> messages = new ArrayList<>(StepMessageCodec.messages(last.input().path("messages")));
        if (request.plan().descriptor().stepContextPolicy() != null) {
            UserMessage original = SpringAiPromptFactory.originalTaskMessage(request);
            long matching = messages.stream()
                    .filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                    .filter(SpringAiPromptFactory::isOriginalTask)
                    .filter(message -> SpringAiPromptFactory.sameUserContent(message, original))
                    .count();
            if (matching != 1) {
                throw recoveryRequired(last, "persisted provider prompt lacks the original task");
            }
        }
        ChatResponse finalResponse = null;
        if (last.state() == AgentStep.State.COMPLETED && last.output().has("message")) {
            AssistantMessage assistant = (AssistantMessage) StepMessageCodec.message(last.output().path("message"));
            messages.add(assistant);
            if (assistant.getToolCalls().isEmpty()) {
                finalResponse = new ChatResponse(List.of(new Generation(assistant)),
                        org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                                .model(last.output().path("model").asText(request.plan().descriptor().modelPolicyRef())).build());
            } else {
                boolean rejected = rejectedUnavailableBatch(last, assistant.getToolCalls());
                if (!rejected) validatePersistedToolVisibility(last, assistant.getToolCalls());
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                boolean redacted = modelOutputRedacted(last.id());
                for (var call : assistant.getToolCalls()) {
                    String invocation = invocationId(last.id(), call);
                    StepId toolStep = StepId.tool(request.runId(), invocation);
                    var persisted = steps.step(request.runId(), toolStep);
                    ToolInvocationResult result;
                    if (persisted.isPresent()) {
                        if (persisted.get().state() != AgentStep.State.COMPLETED)
                            throw new ToolRecoveryRequiredException(toolStep.value());
                        if (!rejected && persisted.get().kind() != AgentStep.Kind.TOOL)
                            throw new ToolRecoveryRequiredException(toolStep.value());
                        // Completed work needs only its processed result, never the original credentials.
                        result = replay(persisted.get());
                    } else {
                        if (redacted) throw new ToolRecoveryRequiredException(toolStep.value(),
                                "Pending tool input contains redacted credentials; reconcile the input before continuing step " + toolStep.value());
                        FrameworkTool tool = tools.stream().filter(candidate ->
                                candidate.descriptor().name().equals(call.name())).findFirst().orElseThrow(() ->
                                new IllegalStateException("persisted tool no longer exists: " + call.name()));
                        result = invoke(tool, parse(call.arguments()), gateway, invocation);
                    }
                    responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result.output().toString()));
                }
                messages.add(ToolResponseMessage.builder().responses(responses).build());
            }
        }
        UserMessage resume = appendResume ? SpringAiPromptFactory.resumeCommandMessage(request) : null;
        if (resume != null) {
            messages.add(resume);
            finalResponse = null;
        }
        StepContextProjector.Projection projection;
        if (request.plan().descriptor().onDemandContextPolicy() != null) {
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
                last.state() != AgentStep.State.COMPLETED && resume == null,
                List.copyOf(visibleTools),
                last.input().path("toolCandidateStepId").isTextual()
                        ? last.input().path("toolCandidateStepId").asText() : null);
    }
    private void validatePersistedToolVisibility(
            AgentStep modelStep, List<AssistantMessage.ToolCall> calls) {
        JsonNode input = modelStep.input();
        if (input == null || !input.path("toolNames").isArray()) {
            throw recoveryRequired(modelStep, "persisted provider tool directory is unavailable");
        }
        Set<String> visible = new HashSet<>();
        input.path("toolNames").forEach(name -> {
            if (name.isTextual()) visible.add(name.asText());
        });
        for (var call : calls) {
            if (visible.contains(call.name())) continue;
            StepId toolStep = StepId.tool(request.runId(), invocationId(modelStep.id(), call));
            throw new ToolRecoveryRequiredException(toolStep.value(),
                    "Persisted model response requested a tool absent from its provider prompt: "
                            + call.name() + "; reconcile step " + toolStep.value());
        }
    }

    private boolean rejectedUnavailableBatch(
            AgentStep modelStep, List<AssistantMessage.ToolCall> calls) {
        List<AgentStep> persisted = new ArrayList<>();
        for (var call : calls) {
            StepId id = StepId.tool(request.runId(), invocationId(modelStep.id(), call));
            steps.step(request.runId(), id).ifPresent(persisted::add);
        }
        if (persisted.stream().noneMatch(step -> step.kind() == AgentStep.Kind.ORCHESTRATION)) {
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
                    || !unavailableToolFeedback(call).equals(step.output().path("modelOutput"))
                    || modelInputRedacted(id) || modelOutputRedacted(id)) {
                throw recoveryRequired(modelStep, "persisted unavailable-tool rejection has changed");
            }
        }
        return true;
    }

    private static String callFingerprint(AssistantMessage.ToolCall call) {
        var value = JsonNodeFactory.instance.objectNode();
        value.put("id", call.id());
        value.put("name", call.name());
        value.put("arguments", call.arguments());
        return ToolInvocationFingerprint.create(REJECT_UNAVAILABLE_BATCH, value);
    }

    private static JsonNode unavailableToolFeedback(AssistantMessage.ToolCall call) {
        return JsonNodeFactory.instance.objectNode()
                .put("error", "tool_not_offered")
                .put("tool", call.name())
                .put("message", "At least one tool in this batch was not offered for this step. "
                        + "No calls in the batch were executed. Answer directly or use only "
                        + "tools offered in the next step.");
    }

    /** Validate the frozen candidate mapping before any pending tool can execute. */
    private void validateProviderTools(AgentStep model, ToolCatalogSession catalog) {
        var onDemand = request.plan().descriptor().onDemandContextPolicy();
        if (onDemand == null) return;
        JsonNode input = model.input();
        if (input == null || !input.path("toolNames").isArray()
                || !input.path("toolFingerprints").isObject()) {
            throw recoveryRequired(model, "persisted provider tool definitions are unavailable");
        }
        Set<String> candidates = candidateNamesFor(model, catalog);
        Set<String> activated = activatedBefore(model);
        Set<String> seen = new HashSet<>();
        JsonNode fingerprints = input.path("toolFingerprints");
        for (JsonNode value : input.path("toolNames")) {
            String name = value.asText("");
            String fingerprint = fingerprints.path(name).asText("");
            if (!value.isTextual() || name.isBlank() || !seen.add(name)
                    || fingerprint.isBlank() || catalog == null
                    || !catalog.matchesProviderDefinition(name, fingerprint)) {
                throw recoveryRequired(model, "persisted provider tool is no longer authorized or has changed: "
                        + name);
            }
            if (!name.equals(ToolCatalogSession.NAME)
                    && !candidates.contains(name) && !activated.contains(name)) {
                throw recoveryRequired(model,
                        "persisted provider tool has no authorized candidate mapping: " + name);
            }
        }
        if (fingerprints.size() != seen.size()) {
            throw recoveryRequired(model, "persisted provider tool fingerprints do not match tool names");
        }
    }

    private Set<String> candidateNamesFor(AgentStep model, ToolCatalogSession catalog) {
        JsonNode candidateId = model.input().path("toolCandidateStepId");
        if (candidateId.isMissingNode()) return Set.of();
        if (!candidateId.isTextual() || candidateId.asText().isBlank()) {
            throw recoveryRequired(model, "persisted tool candidate mapping ID is invalid");
        }
        String id = candidateId.asText();
        AgentStep candidate = steps.step(request.runId(), new StepId(id)).orElseThrow(() ->
                recoveryRequired(model, "persisted tool candidate mapping is unavailable: " + id));
        long priorCompletedModel = steps.steps(request.runId()).stream()
                .filter(step -> step.kind() == AgentStep.Kind.MODEL
                        && step.state() == AgentStep.State.COMPLETED
                        && step.startSequence() < model.startSequence())
                .mapToLong(AgentStep::startSequence).max().orElse(0);
        if (candidate.kind() != AgentStep.Kind.ORCHESTRATION
                || candidate.state() != AgentStep.State.COMPLETED
                || candidate.startSequence() <= priorCompletedModel
                || candidate.startSequence() >= model.startSequence()
                || !candidate.input().path("phase").asText().equals("tool_search_v2")
                || modelInputRedacted(candidate.id()) || modelOutputRedacted(candidate.id())
                || !candidate.output().path("candidates").isArray()) {
            throw recoveryRequired(model, "persisted tool candidate mapping is unavailable: " + id);
        }
        Set<String> names = new HashSet<>();
        int index = 0;
        for (JsonNode entry : candidate.output().path("candidates")) {
            String name = entry.path("name").asText();
            String fingerprint = entry.path("fingerprint").asText();
            if (!entry.path("id").asText().equals("t" + index++)
                    || name.isBlank() || entry.path("group").asText().isBlank()
                    || fingerprint.isBlank() || !names.add(name) || catalog == null
                    || !catalog.matchesCandidate(name, fingerprint)) {
                throw recoveryRequired(model,
                        "persisted tool candidate is no longer authorized or has changed: " + name);
            }
        }
        return names;
    }

    /** Catalog activation is consumed by the next completed MODEL, including a catalog listing. */
    private Set<String> activatedBefore(AgentStep model) {
        Set<String> active = Set.of();
        List<AgentStep> earlier = steps.steps(request.runId()).stream()
                .filter(step -> step.state() == AgentStep.State.COMPLETED
                        && step.lastSequence() < model.startSequence())
                .sorted(Comparator.comparingLong(AgentStep::lastSequence)).toList();
        for (AgentStep step : earlier) {
            if (step.kind() == AgentStep.Kind.MODEL) {
                active = Set.of();
            } else if (step.kind() == AgentStep.Kind.TOOL
                    && step.input().path("tool").asText().equals(ToolCatalogSession.NAME)
                    && step.input().path("arguments").path("action").asText().equals("activate")) {
                if (modelOutputRedacted(step.id())) {
                    throw recoveryRequired(model, "persisted tool catalog activation was redacted");
                }
                JsonNode output = step.output().path("rawOutput");
                if (!output.path("success").asBoolean(false)
                        || !output.path("action").asText().equals("activate")) continue;
                JsonNode values = output.path("activated");
                if (!values.isArray() || values.isEmpty()) {
                    throw recoveryRequired(model, "persisted tool catalog activation is invalid");
                }
                Set<String> names = new HashSet<>();
                for (JsonNode value : values) {
                    if (!value.isTextual() || value.asText().isBlank()
                            || !names.add(value.asText())) {
                        throw recoveryRequired(model, "persisted tool catalog activation is invalid");
                    }
                }
                active = names;
            }
        }
        return active;
    }

    private static ToolRecoveryRequiredException recoveryRequired(AgentStep model, String reason) {
        return new ToolRecoveryRequiredException(model.id().value(), reason);
    }
    private boolean modelOutputRedacted(StepId id) {
        return runs.eventsAfter(request.runId(), 0).stream().anyMatch(event ->
                event.type().equals("core.step.completed") && event.payload().path("stepId").asText().equals(id.value())
                        && event.payload().path("credentialRedacted").asBoolean(false));
    }
    private boolean modelInputRedacted(StepId id) {
        return runs.eventsAfter(request.runId(), 0).stream().anyMatch(event ->
                event.type().equals("core.step.started")
                        && event.payload().path("stepId").asText().equals(id.value())
                        && event.payload().path("credentialRedacted").asBoolean(false));
    }
    private static ToolInvocationResult replay(AgentStep step) {
        return new ToolInvocationResult(step.output().path("modelOutput"),
                Duration.ofMillis(step.output().path("durationMillis").asLong()));
    }
    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (Exception failure) { throw new IllegalStateException("invalid persisted tool arguments", failure); }
    }
    record Recovery(
            String systemPrompt,
            List<Message> messages,
            StepContextProjector.Statistics statistics,
            ChatResponse finalResponse,
            boolean replayPrompt,
            List<String> toolNames,
            String toolCandidateStepId) { }
}
