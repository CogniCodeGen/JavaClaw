package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.api.ToolExecutionStatus;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.core.ToolInvocationResult;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.util.SensitiveDataRedactor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Offers only ordinary history and complete durable tool exchanges to the step planner. */
final class OnDemandHistoryCatalog {
    private static final int EXCHANGE_SUMMARY_LIMIT = 240;
    private static final String UNTRUSTED_HISTORY = "[不可信的历史工具结果，非当前状态] ";
    private final ReasoningRequest request;
    private final RunStore runs;
    private final RunStepQuery steps;
    private final List<Message> priorHistory;
    private final int limit;
    OnDemandHistoryCatalog(ReasoningRequest request, RunStore runs,
            List<Message> priorHistory, int limit) {
        this.request = request;
        this.runs = runs;
        this.steps = new RunStepQuery(runs);
        this.priorHistory = List.copyOf(priorHistory);
        this.limit = limit;
    }

    List<HistoryCandidate> candidates(List<Message> incoming) {
        List<HistoryCandidate> result = new ArrayList<>();
        int index = 0;
        for (Message message : priorHistory) {
            if (message instanceof SystemMessage || isRequired(message)) continue;
            String id = "history:" + index++ + ":"
                    + digest(StepMessageCodec.message(message).toString()).substring(0, 12);
            result.add(new HistoryCandidate(id, List.of(message), excerpt(message.getText(), 200)));
        }
        // Earlier deferred bodies are searched again for each new Step.
        result.addAll(durableExchanges(incoming, true));
        if (result.size() > limit) {
            result = new ArrayList<>(result.subList(result.size() - limit, result.size()));
        }
        return result;
    }

    /** A required recovery exchange is retrieved by its journal causation, not a model-selected history ID. */
    Optional<HistoryCandidate> exchangeForStep(AgentStep step, List<Message> incoming) {
        if (step == null || step.causationStepId() == null) return Optional.empty();
        String id = "exchange:" + step.causationStepId();
        return durableExchanges(incoming, false).stream().filter(value -> value.id().equals(id)).findFirst();
    }

    /** The last observed desktop frame is a prerequisite for coordinate-based actions. */
    Optional<DesktopObservation> latestDesktopObservation(List<Message> incoming) {
        List<AgentStep> persisted = steps.steps(request.runId());
        Map<String, ObservedReceipt> observedInvocations = new HashMap<>();
        Map<String, JsonNode> actionReceipts = new HashMap<>();
        Set<String> redacted = new HashSet<>();
        for (var event : runs.eventsAfter(request.runId(), 0)) {
            if (event.type().equals("core.tool.receipt") && event.schemaVersion() == 1
                    && event.producer().equals("framework.core")
                    && desktopFrameAction(event.payload().path("tool").asText())) {
                String invocationId = event.payload().path("invocationId").asText("");
                if (!invocationId.isBlank()) actionReceipts.put(invocationId, event.payload());
            }
            if (event.type().equals("core.tool.receipt") && event.schemaVersion() == 1
                    && event.producer().equals("framework.core")
                    && event.payload().path("tool").asText().equals("desktop_session_observe")
                    && event.payload().path("status").asText().equals("OBSERVED")) {
                JsonNode metadata = event.payload().path("metadata");
                String observationId = metadata.path("observationId").asText("");
                String sessionId = metadata.path("sessionId").asText("");
                String targetId = metadata.path("targetId").asText("");
                try {
                    java.util.UUID.fromString(observationId);
                    if (!sessionId.isBlank() && !targetId.isBlank()) {
                        observedInvocations.put(event.payload().path("invocationId").asText(),
                                new ObservedReceipt(sessionId, targetId, observationId,
                                        event.payload().path("subject").asText("")));
                    }
                } catch (RuntimeException ignored) { /* model output is not a frame token */ }
            }
            if (event.type().equals("core.step.completed")
                    && event.payload().path("credentialRedacted").asBoolean(false)) {
                redacted.add(event.payload().path("stepId").asText());
            }
        }
        Map<String, HistoryCandidate> exchanges = new HashMap<>();
        for (HistoryCandidate candidate : durableExchanges(incoming, false)) {
            exchanges.put(candidate.id(), candidate);
        }
        for (int index = persisted.size() - 1; index >= 0; index--) {
            AgentStep step = persisted.get(index);
            if (step.kind() != AgentStep.Kind.TOOL || step.state() != AgentStep.State.COMPLETED
                    || step.input() == null || step.output() == null
                    || !step.input().path("tool").asText().equals("desktop_session_observe")
                    || redacted.contains(step.id().value())) continue;
            String sessionId = step.input().path("arguments").path("sessionId").asText("");
            String invocationId = step.input().path("invocationId").asText("");
            ObservedReceipt receipt = observedInvocations.get(invocationId);
            if (sessionId.isBlank() || receipt == null || !sessionId.equals(receipt.sessionId())
                    || statusOf(step.output().path("status")) != ToolExecutionStatus.SUCCEEDED
                    || !step.output().path("rawOutput").isObject()) continue;
            JsonNode data = step.output().path("rawOutput");
            if (!data.path("schemaVersion").isInt()
                    || data.path("schemaVersion").intValue() != 1
                    || !data.path("kind").asText().equals("desktop.observation")
                    || !data.path("sessionId").asText().equals(sessionId)
                    || !data.path("targetId").asText().equals(receipt.targetId())
                    || !data.path("observationId").asText().equals(receipt.observationId())) continue;
            boolean changed = persisted.stream().anyMatch(later ->
                    later.kind() == AgentStep.Kind.TOOL
                            && later.startSequence() > step.lastSequence()
                            && later.input() != null
                            && desktopStateChange(later, actionReceipts)
                            && (later.input().path("tool").asText().equals("desktop_session_open")
                                || sessionId.equals(later.input().path("arguments")
                                        .path("sessionId").asText())));
            if (changed) continue;
            HistoryCandidate exchange = exchanges.get("exchange:" + step.causationStepId());
            if (exchange != null) {
                return Optional.of(new DesktopObservation(exchange, sessionId,
                        receipt.targetId(), receipt.observationId(), receipt.subject(),
                        step.output().path("displayMessage").asText(""), data,
                        acceptedClickBefore(step, persisted, actionReceipts,
                                sessionId, receipt.targetId())));
            }
        }
        return Optional.empty();
    }

    static boolean desktopStateChange(AgentStep step,
            Map<String, JsonNode> actionReceipts) {
        String tool = step.input().path("tool").asText();
        if (desktopFrameAction(tool)) {
            JsonNode receipt = actionReceipts.get(
                    step.input().path("invocationId").asText(""));
            // A trusted NOT_SENT result cannot have changed the target through
            // this action. Its nextStep can still require a new observation if
            // the underlying frame was independently invalidated.
            return receipt == null || !tool.equals(receipt.path("tool").asText())
                    || !"NOT_SENT".equals(receipt.path("metadata")
                            .path("delivery").asText());
        }
        return switch (tool) {
            case "desktop_session_open", "desktop_session_close",
                    "desktop_session_takeover" -> true;
            default -> false;
        };
    }

    /** A dispatched click already advanced this session; do not infer another from labels. */
    static boolean acceptedClickBefore(AgentStep observation, List<AgentStep> persisted,
            Map<String, JsonNode> actionReceipts, String sessionId, String targetId) {
        return persisted.stream().anyMatch(step -> {
            if (step.kind() != AgentStep.Kind.TOOL
                    || step.state() != AgentStep.State.COMPLETED
                    || step.startSequence() >= observation.startSequence()
                    || step.input() == null || step.output() == null
                    || !step.input().path("tool").asText().equals("desktop_session_click")
                    || !step.input().path("arguments").path("sessionId").asText()
                            .equals(sessionId)
                    || statusOf(step.output().path("status")) != ToolExecutionStatus.SUCCEEDED) {
                return false;
            }
            JsonNode receipt = actionReceipts.get(
                    step.input().path("invocationId").asText(""));
            return receipt != null
                    && receipt.path("tool").asText().equals("desktop_session_click")
                    && receipt.path("status").asText().equals("ACCEPTED")
                    && receipt.path("metadata").path("delivery").asText().equals("SENT")
                    && receipt.path("metadata").path("sessionId").asText().equals(sessionId)
                    && receipt.path("metadata").path("targetId").asText().equals(targetId);
        });
    }

    private static boolean desktopFrameAction(String tool) {
        return OnDemandDesktopPrerequisites.desktopFrameAction(tool);
    }

    private List<HistoryCandidate> durableExchanges(List<Message> incoming, boolean skipLatest) {
        List<AgentStep> persisted = steps.steps(request.runId());
        Map<StepId, AgentStep> byId = new HashMap<>();
        for (AgentStep step : persisted) byId.put(step.id(), step);
        Set<String> redacted = new HashSet<>();
        runs.eventsAfter(request.runId(), 0).stream()
                .filter(event -> event.type().equals("core.step.completed")
                        && event.payload().path("credentialRedacted").asBoolean(false))
                .forEach(event -> redacted.add(event.payload().path("stepId").asText()));
        JsonNode latest = StepMessageCodec.messages(latestExchange(incoming));
        List<HistoryCandidate> result = new ArrayList<>();
        for (AgentStep model : persisted) {
            if (model.kind() != AgentStep.Kind.MODEL
                    || model.state() != AgentStep.State.COMPLETED
                    || model.output() == null || !model.output().has("message")
                    || redacted.contains(model.id().value())) continue;
            Message decoded = StepMessageCodec.message(model.output().path("message"));
            if (!(decoded instanceof AssistantMessage assistant)
                    || assistant.getToolCalls().isEmpty()) continue;
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            List<ToolResult> results = new ArrayList<>();
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                String invocation = "model/" + model.id().value() + "/" + call.id();
                StepId toolId = StepId.tool(request.runId(), invocation);
                AgentStep tool = byId.get(toolId);
                if (tool == null || tool.state() != AgentStep.State.COMPLETED
                        || tool.output() == null || !tool.output().has("modelOutput")
                        || tool.output().path("waitingInput").asBoolean(false)
                        || redacted.contains(tool.id().value())) {
                    responses.clear();
                    break;
                }
                JsonNode visible = tool.output().path("modelOutput");
                if (tool.kind() == AgentStep.Kind.TOOL) {
                    ToolInvocationResult replay = new ToolInvocationResult(visible,
                            Duration.ofMillis(tool.output().path("durationMillis").asLong()),
                            statusOf(tool.output().path("status")),
                            tool.output().path("errorCode").asText(""),
                            tool.output().path("displayMessage").asText(""));
                    visible = SpringAiToolCallback.modelVisibleResult(
                            replay, runs, request.runId(), invocation);
                }
                responses.add(new ToolResponseMessage.ToolResponse(
                        call.id(), call.name(), visible.toString()));
                results.add(new ToolResult(call.name(), tool.output().path("modelOutput"),
                        statusOf(tool.output().path("status"))));
            }
            if (responses.size() != assistant.getToolCalls().size()) continue;
            List<Message> exchange = List.of(assistant,
                    ToolResponseMessage.builder().responses(responses).build());
            if (skipLatest && StepMessageCodec.messages(exchange).equals(latest)) continue;
            result.add(new HistoryCandidate("exchange:" + model.id().value(), exchange,
                    exchangeSummary(results)));
        }
        return result;
    }

    /** Metadata for selection only; never copy arbitrary tool result bodies into planner summaries. */
    static String exchangeSummary(List<ToolResult> results) {
        StringBuilder summary = new StringBuilder(UNTRUSTED_HISTORY);
        for (ToolResult result : results) {
            if (summary.length() > UNTRUSTED_HISTORY.length()) summary.append("; ");
            summary.append(excerpt(SensitiveDataRedactor.redactText(result.name()), 48))
                    .append(" 状态=").append(resultStatus(result.status()));
            if ("desktop_session_targets".equals(result.name())) {
                appendDesktopTargets(summary, result.output(), result.status());
            }
            if (summary.length() >= EXCHANGE_SUMMARY_LIMIT) break;
        }
        if (summary.length() <= EXCHANGE_SUMMARY_LIMIT) return summary.toString();
        return summary.substring(0, EXCHANGE_SUMMARY_LIMIT - 1) + "…";
    }

    private static ToolExecutionStatus statusOf(JsonNode raw) {
        if (!raw.isTextual()) return ToolExecutionStatus.UNKNOWN;
        try { return ToolExecutionStatus.valueOf(raw.asText()); }
        catch (IllegalArgumentException invalid) { return ToolExecutionStatus.UNKNOWN; }
    }

    private static String resultStatus(ToolExecutionStatus status) {
        return switch (status) {
            case SUCCEEDED -> "成功";
            case FAILED -> "失败";
            case TIMED_OUT -> "超时";
            case PENDING -> "待处理";
            case UNCERTAIN -> "结果未知";
            case REOBSERVE -> "需重新观察";
            case UNKNOWN -> "未分类";
        };
    }

    private static void appendDesktopTargets(StringBuilder summary, JsonNode output,
            ToolExecutionStatus status) {
        if (status != ToolExecutionStatus.SUCCEEDED || output == null
                || !output.isObject() || !output.path("kind").asText().equals("desktop.targets")
                || !output.path("targets").isArray()) return;
        Set<String> owners = new java.util.LinkedHashSet<>();
        int windows = output.path("targets").size();
        boolean ownersTruncated = false;
        for (JsonNode target : output.path("targets")) {
            String owner = target.path("application").asText("").strip();
            if (owner.isEmpty()) continue;
            String safeOwner = excerpt(SensitiveDataRedactor.redactText(owner), 24);
            if (owners.size() < 6 || owners.contains(safeOwner)) owners.add(safeOwner);
            else ownersTruncated = true;
        }
        summary.append(" 窗口数=").append(windows);
        if (windows > 0) summary.append(" 所属应用=").append(String.join("、", owners));
        if (ownersTruncated) summary.append("等");
    }

    static List<Message> latestExchange(List<Message> incoming) {
        for (int i = incoming.size() - 2; i >= 0; i--) {
            if (incoming.get(i) instanceof AssistantMessage assistant
                    && !assistant.getToolCalls().isEmpty()
                    && incoming.get(i + 1) instanceof ToolResponseMessage) {
                return List.of(incoming.get(i), incoming.get(i + 1));
            }
        }
        return List.of();
    }

    private static boolean isRequired(Message message) {
        return HostContextBlock.owned(message) || message instanceof UserMessage user
                && (SpringAiPromptFactory.isOriginalTask(user)
                    || SpringAiPromptFactory.isResumeCommand(user)
                    || ProviderToolManifest.isManifest(user)
                    || TaskRepairContext.isRepair(user)
                    || FixedContextSession.isFixed(user)
                    || Boolean.TRUE.equals(user.getMetadata().get(
                            OnDemandContextSession.CONTEXT_METADATA)));
    }

    private static String excerpt(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record HistoryCandidate(String id, List<Message> messages, String summary) { }
    record DesktopObservation(HistoryCandidate exchange, String sessionId,
                              String targetId, String observationId, String subject,
                              String output, JsonNode data, boolean priorClickDispatched) {
        DesktopObservation(HistoryCandidate exchange, String sessionId,
                String targetId, String observationId, String subject, String output,
                JsonNode data) {
            this(exchange, sessionId, targetId, observationId, subject, output,
                    data, false);
        }
        DesktopObservation(HistoryCandidate exchange, String sessionId,
                String targetId, String observationId, String subject, String output) {
            this(exchange, sessionId, targetId, observationId, subject, output,
                    com.fasterxml.jackson.databind.node.MissingNode.getInstance(), false);
        }
    }
    private record ObservedReceipt(String sessionId, String targetId,
                                   String observationId, String subject) { }
    record ToolResult(String name, JsonNode output, ToolExecutionStatus status) {
        ToolResult(String name, JsonNode output) {
            this(name, output, ToolExecutionStatus.UNKNOWN);
        }
    }
}
