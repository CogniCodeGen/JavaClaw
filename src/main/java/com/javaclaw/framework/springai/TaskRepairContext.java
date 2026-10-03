package com.javaclaw.framework.springai;

import com.javaclaw.framework.api.RunEventEnvelope;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

/** Framework completion feedback remains distinct from user or screen text. */
final class TaskRepairContext {
    static final String MODEL_STEP_METADATA = "javaclaw.taskRepair.modelStepId";
    static final String SEQUENCE_METADATA = "javaclaw.taskRepair.sequence";

    private TaskRepairContext() { }

    static boolean trusted(RunEventEnvelope event, String runId, String modelStepId) {
        return event.runId().equals(runId)
                && (event.type().equals("core.task.repair_requested")
                        || event.type().equals("core.harness.protocol_repair_requested"))
                && event.producer().equals("framework.springai")
                && (event.schemaVersion() == 1 || event.schemaVersion() == 2
                        || event.schemaVersion() == 3)
                && !modelStepId.isBlank()
                && event.payload().path("modelStepId").asText().equals(modelStepId);
    }

    static UserMessage fromEvent(RunEventEnvelope event, String runId, String modelStepId) {
        if (!trusted(event, runId, modelStepId)) {
            throw new IllegalArgumentException("untrusted task repair event");
        }
        String feedback = event.payload().path("feedback").asText("");
        if (feedback.isBlank()) throw new IllegalArgumentException("task repair feedback is unavailable");
        return UserMessage.builder().text(feedback).metadata(Map.of(
                MODEL_STEP_METADATA, modelStepId, SEQUENCE_METADATA, event.sequence())).build();
    }

    static boolean isRepair(Message message) {
        return message instanceof UserMessage user
                && user.getMetadata().get(MODEL_STEP_METADATA) instanceof String id && !id.isBlank()
                && user.getMetadata().get(SEQUENCE_METADATA) instanceof Number sequence
                && sequence.longValue() > 0;
    }

    static UserMessage latest(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (isRepair(messages.get(index))) return (UserMessage) messages.get(index);
        }
        return null;
    }

    static String plannerFeedback(List<Message> messages, int limit) {
        UserMessage repair = latest(messages);
        return repair == null ? "" : boundedFeedback(repair.getText(), limit);
    }

    static String boundedFeedback(String text, int limit) {
        if (limit <= 0) return "";
        if (text.length() <= limit) return text;
        // The missing completion criteria are at the end of generated feedback.
        return limit == 1 ? "…" : "…" + text.substring(text.length() - limit + 1);
    }
}
