package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.spi.RunStore;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offers only ordinary history and complete durable tool exchanges to the step planner. */
final class OnDemandHistoryCatalog {
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
        result.addAll(durableExchanges(incoming));
        if (result.size() > limit) {
            result = new ArrayList<>(result.subList(result.size() - limit, result.size()));
        }
        return result;
    }

    private List<HistoryCandidate> durableExchanges(List<Message> incoming) {
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
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                StepId toolId = StepId.tool(request.runId(),
                        "model/" + model.id().value() + "/" + call.id());
                AgentStep tool = byId.get(toolId);
                if (tool == null || tool.state() != AgentStep.State.COMPLETED
                        || tool.output() == null || !tool.output().has("modelOutput")
                        || tool.output().path("waitingInput").asBoolean(false)
                        || redacted.contains(tool.id().value())) {
                    responses.clear();
                    break;
                }
                responses.add(new ToolResponseMessage.ToolResponse(
                        call.id(), call.name(), tool.output().path("modelOutput").toString()));
            }
            if (responses.size() != assistant.getToolCalls().size()) continue;
            List<Message> exchange = List.of(assistant,
                    ToolResponseMessage.builder().responses(responses).build());
            if (StepMessageCodec.messages(exchange).equals(latest)) continue;
            result.add(new HistoryCandidate("exchange:" + model.id().value(), exchange,
                    excerpt(exchange.toString(), 240)));
        }
        return result;
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
        return message instanceof UserMessage user
                && (SpringAiPromptFactory.isOriginalTask(user)
                    || SpringAiPromptFactory.isResumeCommand(user)
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
}
