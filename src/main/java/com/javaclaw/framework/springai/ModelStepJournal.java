package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.core.*;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.RunStore;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import java.time.Duration;
import java.util.*;

/** Provider-call journal and conservative tool continuation reconstruction. */
final class ModelStepJournal {
    private final ReasoningRequest request;
    private final RunStepQuery steps;
    private final RunStore runs;
    private final ObjectMapper json;
    private StepId currentModel;
    private final List<AssistantMessage.ToolCall> pendingCalls = new ArrayList<>();

    ModelStepJournal(ReasoningRequest request, RunStore runs, ObjectMapper json) {
        this.request = request; this.steps = new RunStepQuery(runs); this.runs = runs; this.json = json;
    }
    StepId started(Prompt prompt, int attempt) {
        StepId id = StepId.random();
        var input = JsonNodeFactory.instance.objectNode();
        input.set("messages", StepMessageCodec.messages(prompt.getInstructions()));
        input.put("modelPolicy", request.plan().descriptor().modelPolicyRef());
        input.put("attempt", attempt);
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
            if (step.state() != AgentStep.State.COMPLETED) throw new ToolRecoveryRequiredException(id.value());
            return replay(step);
        }
        return SpringAiToolCallback.invoke(tool, arguments, request, gateway, invocation);
    }

    /** Uses the latest actual provider request, including all preceding tool response messages. */
    Recovery recover(List<FrameworkTool> tools, ToolInvocationGateway gateway, boolean appendResume) {
        List<AgentStep> history = steps.steps(request.runId());
        AgentStep last = null;
        for (AgentStep step : history) if (step.kind() == AgentStep.Kind.MODEL) last = step;
        if (last == null) {
            if (runs.eventsAfter(request.runId(), 0).stream().anyMatch(event -> event.type().equals("core.tool.started"))) {
                throw new ToolRecoveryRequiredException("legacy:" + request.runId().value());
            }
            return null;
        }
        List<Message> messages = new ArrayList<>(StepMessageCodec.messages(last.input().path("messages")));
        ChatResponse finalResponse = null;
        if (last.state() == AgentStep.State.COMPLETED && last.output().has("message")) {
            AssistantMessage assistant = (AssistantMessage) StepMessageCodec.message(last.output().path("message"));
            messages.add(assistant);
            if (assistant.getToolCalls().isEmpty()) {
                finalResponse = new ChatResponse(List.of(new Generation(assistant)),
                        org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                                .model(last.output().path("model").asText(request.plan().descriptor().modelPolicyRef())).build());
            } else {
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
        if (appendResume && request.resumeCommand() != null
                && !request.resumeCommand().type().equals("tool.approval")
                && !(Set.of("delegation.continue", "schedule.continue", "managed.continue")
                        .contains(request.resumeCommand().type()) && request.resumeCommand().payload().isEmpty())) {
            messages.add(new UserMessage("Resume command (" + request.resumeCommand().type() + "): "
                    + request.resumeCommand().payload()));
            finalResponse = null;
        }
        String system = messages.stream().filter(SystemMessage.class::isInstance)
                .map(Message::getText).collect(java.util.stream.Collectors.joining("\n\n"));
        messages.removeIf(SystemMessage.class::isInstance);
        return new Recovery(system, messages, finalResponse);
    }
    private boolean modelOutputRedacted(StepId id) {
        return runs.eventsAfter(request.runId(), 0).stream().anyMatch(event ->
                event.type().equals("core.step.completed") && event.payload().path("stepId").asText().equals(id.value())
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
    record Recovery(String systemPrompt, List<Message> messages, ChatResponse finalResponse) { }
}
