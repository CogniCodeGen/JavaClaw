package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.api.ToolExecutionStatus;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.core.StepEvents;
import com.javaclaw.framework.core.ToolInvocationGateway;
import com.javaclaw.framework.core.ToolInvocationRequest;
import com.javaclaw.framework.core.ToolInvocationResult;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.ToolExecutionContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** A separate ordinary read after delivered clicks; never a retry or an effect verdict. */
final class DesktopPostClickObservation {
    private static final String CLICK = "desktop_session_click";
    private static final String OBSERVE = "desktop_session_observe";
    private static final String PHASE = "desktop_post_click_observation";
    private final ReasoningRequest request;
    private final RunStore runs;
    private final RunStepQuery steps;
    private final ObjectMapper json;
    private final List<FrameworkTool> tools;
    private final ToolInvocationGateway gateway;

    DesktopPostClickObservation(ReasoningRequest request, RunStore runs, ObjectMapper json,
            List<FrameworkTool> tools, ToolInvocationGateway gateway) {
        this.request = request;
        this.runs = runs;
        this.steps = new RunStepQuery(runs);
        this.json = json;
        this.tools = List.copyOf(tools);
        this.gateway = gateway;
    }

    List<Message> afterBatch(List<Message> history, StepId model) {
        if (model == null || tools.stream().noneMatch(tool -> trusted(tool, CLICK))) return history;
        AgentStep modelStep = steps.step(request.runId(), model).orElse(null);
        if (modelStep == null || modelStep.output() == null || !modelStep.output().has("message")) return history;
        if (!(StepMessageCodec.message(modelStep.output().path("message")) instanceof AssistantMessage assistant)) return history;
        List<Message> result = new ArrayList<>(history);
        for (var call : assistant.getToolCalls()) {
            if (!CLICK.equals(call.name())) continue;
            String invocation = PersistedToolCallCodec.invocationId(model, call);
            RunEventEnvelope receipt = deliveredClick(invocation);
            if (receipt == null) continue;
            ObjectNode observation = observe(invocation, receipt);
            // Keep the provider's original click response and its evidence references intact.
            for (int index = result.size() - 1; index >= 0; index--) {
                if (!(result.get(index) instanceof ToolResponseMessage responses)) continue;
                boolean found = false;
                List<ToolResponseMessage.ToolResponse> values = new ArrayList<>();
                for (var response : responses.getResponses()) {
                    if (response.id().equals(call.id()) && response.name().equals(CLICK)) {
                        try {
                            JsonNode parsed = json.readTree(response.responseData());
                            if (parsed instanceof ObjectNode envelope) {
                                envelope.set("postClickObservation", observation);
                                response = new ToolResponseMessage.ToolResponse(response.id(), response.name(), envelope.toString());
                                found = true;
                            }
                        } catch (Exception malformed) { /* Never replace the original click result. */ }
                    }
                    values.add(response);
                }
                if (found) {
                    result.set(index, ToolResponseMessage.builder().responses(values)
                            .metadata(responses.getMetadata()).build());
                    break;
                }
            }
        }
        return List.copyOf(result);
    }

    private RunEventEnvelope deliveredClick(String invocation) {
        AgentStep click = steps.step(request.runId(), StepId.tool(request.runId(), invocation)).orElse(null);
        if (click == null || click.kind() != AgentStep.Kind.TOOL
                || click.input() == null || !click.input().path("trustedDesktopTool").asBoolean(false)
                || !CLICK.equals(click.input().path("tool").asText())
                || !invocation.equals(click.input().path("invocationId").asText())) return null;
        List<RunEventEnvelope> receipts = runs.eventsAfter(request.runId(), 0).stream()
                .filter(event -> event.runId().equals(request.runId().value())
                        && event.type().equals("core.tool.receipt") && event.schemaVersion() == 1
                        && event.producer().equals("framework.core")
                        && invocation.equals(event.payload().path("invocationId").asText())).toList();
        if (receipts.size() != 1) return null;
        var receipt = receipts.getFirst();
        JsonNode proof = receipt.payload(), metadata = proof.path("metadata");
        String session = click.input().path("arguments").path("sessionId").asText();
        String suffix = request.runId().value() + ":" + invocation;
        boolean typed = "click".equals(proof.path("operation").asText())
                && session.equals(metadata.path("sessionId").asText())
                && ((click.state() == AgentStep.State.COMPLETED
                        && proof.path("evidenceRef").asText().equals("core.tool.completed:" + suffix))
                    || proof.path("evidenceRef").asText().equals("core.tool.started:" + suffix));
        boolean failedUnknown = click.state() == AgentStep.State.FAILED
                && "execute".equals(proof.path("operation").asText())
                && "UNKNOWN".equals(proof.path("status").asText())
                && "MAYBE_SENT".equals(metadata.path("delivery").asText())
                && proof.path("evidenceRef").asText().equals("core.tool.failed:" + suffix);
        if (!CLICK.equals(proof.path("tool").asText())
                || !Set.of("SENT", "MAYBE_SENT").contains(metadata.path("delivery").asText())
                || session.isBlank() || !(typed || failedUnknown)) return null;
        try { Math.addExact(Instant.parse(proof.path("observedAt").asText()).toEpochMilli(), 150); }
        catch (RuntimeException invalid) { return null; }
        return receipt;
    }

    private ObjectNode observe(String source, RunEventEnvelope receipt) {
        String invocation = "post-click/" + source + "/observe";
        StepId intentId = StepId.tool(request.runId(), invocation + "/intent");
        long after = Math.addExact(Instant.parse(receipt.payload().path("observedAt").asText()).toEpochMilli(), 150);
        String session = steps.step(request.runId(), StepId.tool(request.runId(), source))
                .orElseThrow().input().path("arguments").path("sessionId").asText();
        ObjectNode arguments = json.createObjectNode().put("sessionId", session)
                .put("question", "观察点击后的当前界面、控件状态和弹窗；只报告画面证据，无法确认时明确说明。")
                .put("extractAllText", false);
        ObjectNode input = json.createObjectNode().put("phase", PHASE).put("sourceInvocationId", source)
                .put("sourceEvidenceRef", receipt.payload().path("evidenceRef").asText())
                .put("capturedAfterMillis", after).put("observationInvocationId", invocation);
        input.set("arguments", arguments);
        AgentStep intent = steps.step(request.runId(), intentId).orElse(null);
        if (intent != null && intent.kind() == AgentStep.Kind.ORCHESTRATION && input.equals(intent.input())
                && intent.state() == AgentStep.State.COMPLETED && intent.output() != null
                && intent.output().path("modelOutput") instanceof ObjectNode completed
                && (!"SUCCEEDED".equals(completed.path("result").path("status").asText())
                    || observedReceipt(completed.path("invocationId").asText(), session, after)))
            return completed.deepCopy();
        if (intent != null && (intent.kind() != AgentStep.Kind.ORCHESTRATION || !input.equals(intent.input())))
            return failed(source, invocation, after, "POST_CLICK_OBSERVATION_CONFLICT");
        if (intent == null) StepEvents.started(request.events(), intentId, AgentStep.Kind.ORCHESTRATION,
                input, StepId.tool(request.runId(), source).value());
        ObjectNode visible;
        try {
            request.control().throwIfCancelled();
            FrameworkTool tool = tools.stream().filter(candidate -> trusted(candidate, OBSERVE)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("host observation tool is unavailable"));
            // A restart may finish only this read. A started read gets one new, stable
            // recovery identity; the source click is never invoked by this component.
            ToolInvocationResult result = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                String readInvocation = attempt == 0 ? invocation : invocation + "/recovery";
                AgentStep read = steps.step(request.runId(), StepId.tool(request.runId(), readInvocation)).orElse(null);
                if (read != null && read.state() == AgentStep.State.COMPLETED) {
                    if (read.kind() != AgentStep.Kind.TOOL || read.input() == null
                            || !OBSERVE.equals(read.input().path("tool").asText())
                            || !read.input().path("trustedDesktopTool").asBoolean(false)
                            || read.input().path("capturedAfterMillis").asLong(-1) != after
                            || !arguments.equals(read.input().path("arguments"))) throw new IllegalStateException("read identity conflict");
                    result = PersistedToolCallCodec.replay(read);
                    // Tool completion precedes receipt persistence. A crash between
                    // them permits only a fresh read, never an unproven OBSERVED replay.
                    if (result.status() == ToolExecutionStatus.SUCCEEDED
                            && !observedReceipt(readInvocation, session, after)) {
                        result = null;
                        continue;
                    }
                    invocation = readInvocation;
                    break;
                }
                if (read != null) continue;
                ToolExecutionContext context = new ToolExecutionContext(request.runId(), readInvocation,
                        request.control(), request.control().deadline(), intentId.value(), false, after);
                result = gateway.invoke(new ToolInvocationRequest(tool, arguments, context, request.runRequest(),
                        request.plan().descriptor().permissions(), request.plan().descriptor().toolPolicy(),
                        request.plan().toolPolicies(), request.plan().toolResultPostProcessors(),
                        request.control(), request.events())).toCompletableFuture().join();
                invocation = readInvocation;
                break;
            }
            if (result == null) throw new IllegalStateException("observation recovery exhausted");
            if (result.status() == ToolExecutionStatus.SUCCEEDED
                    && !observedReceipt(invocation, session, after))
                throw new IllegalStateException("observation receipt unavailable");
            visible = json.createObjectNode().put("sourceInvocationId", source).put("invocationId", invocation)
                    .put("capturedAfterMillis", after).put("effect", "UNKNOWN");
            visible.set("result", SpringAiToolCallback.modelVisibleResult(result, runs, request.runId(), invocation));
        } catch (RuntimeException unavailable) {
            request.control().throwIfCancelled();
            visible = failed(source, invocation, after, "POST_CLICK_OBSERVATION_FAILED");
        }
        ObjectNode output = json.createObjectNode().put("durationMillis", 0);
        output.set("modelOutput", visible);
        StepEvents.completed(request.events(), intentId, output, null);
        return visible;
    }

    private boolean observedReceipt(String invocation, String session, long after) {
        List<RunEventEnvelope> receipts = runs.eventsAfter(request.runId(), 0).stream()
                .filter(event -> event.runId().equals(request.runId().value())
                        && event.type().equals("core.tool.receipt") && event.schemaVersion() == 1
                        && event.producer().equals("framework.core")
                        && invocation.equals(event.payload().path("invocationId").asText())).toList();
        if (receipts.size() != 1) return false;
        JsonNode proof = receipts.getFirst().payload(), metadata = proof.path("metadata");
        return OBSERVE.equals(proof.path("tool").asText())
                && "observe".equals(proof.path("operation").asText())
                && "OBSERVED".equals(proof.path("status").asText())
                && proof.path("evidenceRef").asText().equals(
                        "core.tool.completed:" + request.runId().value() + ":" + invocation)
                && session.equals(metadata.path("sessionId").asText())
                && metadata.path("capturedAtMillis").asLong(-1) > after;
    }

    private ObjectNode failed(String source, String invocation, long after, String error) {
        return json.createObjectNode().put("sourceInvocationId", source).put("invocationId", invocation)
                .put("capturedAfterMillis", after).put("effect", "UNKNOWN").put("errorCode", error)
                .put("message", "点击结果保持原状；点击后新观察未完成，先重新观察或澄清，禁止直接重试点击。");
    }

    private static boolean trusted(FrameworkTool tool, String name) {
        return name.equals(tool.descriptor().name()) && SpringAiAnnotatedToolRegistry.isExactHostTool(tool);
    }
}
