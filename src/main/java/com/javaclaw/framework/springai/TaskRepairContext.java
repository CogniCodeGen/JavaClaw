package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.TaskResultEvaluator;
import com.javaclaw.framework.core.TrustedCapabilityRegistry;
import com.javaclaw.framework.spi.RunStore;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.Comparator;
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

    static boolean isProgress(Message message, String runId) {
        HostContextBlock.Metadata metadata = HostContextBlock.metadata(message);
        return message instanceof SystemMessage && metadata != null
                && metadata.kind() == HostContextBlock.Kind.CONTROL
                && metadata.scope().equals(runId)
                && metadata.id().equals(runId + "/task-repair-progress");
    }

    /** Current host evidence can supersede an old missing-item list, never the final decision. */
    static Message currentProgress(ReasoningRequest request, RunStore runs,
            ObjectMapper json, TrustedCapabilityRegistry capabilities) {
        String runId = request.runId().value();
        var stored = runs.find(request.runId()).orElse(null);
        if (stored == null || !stored.snapshot().id().equals(request.runId())
                || !stored.request().scope().equals(request.runRequest().scope())
                || stored.snapshot().state() != RunState.RUNNING) return null;
        List<RunEventEnvelope> events = runs.eventsAfter(request.runId(), 0);
        if (events.isEmpty() || events.stream().anyMatch(event -> !event.runId().equals(runId))) return null;
        // A malformed newer revision must not fall back to an older complete contract.
        RunEventEnvelope frozen = events.stream()
                .filter(event -> event.producer().equals("framework.core")
                        && (event.type().equals("core.task.contract")
                                || event.type().equals("core.task.contract_revised")))
                .max(Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
        if (frozen == null || frozen.schemaVersion() != 3) return null;
        var contract = TaskResultEvaluator.latestContractV3(List.of(frozen), json).orElse(null);
        if (contract == null || !contract.applicable() || !contract.reliable()
                || !contract.desktopObservationSubjectsValid() || contract.criteria().isEmpty()) return null;
        var result = TaskResultEvaluator.evaluateV3(contract, events, "", capabilities);
        if (result.outcome() != TaskOutcome.VERIFIED_COMPLETE) return null;
        List<String> ids = contract.criteria().stream().map(criterion -> criterion.id()).toList();
        List<String> refs = result.evidenceRefs();
        if (ids.stream().anyMatch(id -> !id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
                || refs.isEmpty() || refs.size() > contract.criteria().size()
                || refs.stream().anyMatch(ref -> ref.length() > 512
                        || !ref.startsWith("core.tool.completed:" + runId + ":")
                        || !ref.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*"))) return null;
        long sequence = events.stream().mapToLong(RunEventEnvelope::sequence).max().orElse(0);
        String text = "Current host-evidence progress at Run event sequence " + sequence + ":\n"
                + "satisfiedCriterionIds=" + json.valueToTree(ids) + "\n"
                + "evidenceRefs=" + json.valueToTree(refs) + "\n"
                + "unmetCriterionIds=[]\n"
                + "All currently verifiable frozen criteria are backed by trusted ordered host evidence. "
                + "An older task-repair list of missing criteria is historical for this snapshot. "
                + "Do not repeat business tools solely to establish these already-backed conditions. "
                + "This progress is not a final task result or a delivered answer and does not override "
                + "approvals, unknown-effect recovery, stop conditions or budgets. Review the full user "
                + "request and any additional work before choosing your next action. When ready to end, "
                + "submit your own valid harness_submit_decision in a batch containing no other calls, "
                + "with the complete requested answer in userMessage and only the existing schema fields.";
        // Never truncate an ID/ref into a different value or borrow extra context capacity.
        if (text.length() > 4096) return null;
        return HostContextBlock.mark(new SystemMessage(text), new HostContextBlock.Metadata(
                runId + "/task-repair-progress", HostContextBlock.Kind.CONTROL,
                Long.toString(sequence), runId, true, refs));
    }

    static String boundedFeedback(String text, int limit) {
        if (limit <= 0) return "";
        if (text.length() <= limit) return text;
        // Task completion feedback ends with the ordered repair guidance and the no-repeat
        // side-effect rule, which must survive a reduced planner context budget.
        return limit == 1 ? "…" : "…" + text.substring(text.length() - limit + 1);
    }
}
