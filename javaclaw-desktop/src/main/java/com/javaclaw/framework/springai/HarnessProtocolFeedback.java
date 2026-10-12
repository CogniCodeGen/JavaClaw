package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.spi.RunStore;

import java.util.Comparator;
import java.util.List;

/** Reads durable decision rejections and renders bounded protocol repair feedback. */
final class HarnessProtocolFeedback {
    private HarnessProtocolFeedback() { }

    static JsonNode latestDecisionRejection(ReasoningRequest request, RunStore runStore) {
        List<AgentStep> history = new RunStepQuery(runStore).steps(request.runId());
        AgentStep model = history.stream()
                .filter(step -> step.kind() == AgentStep.Kind.MODEL
                        && step.state() == AgentStep.State.COMPLETED)
                .max(Comparator.comparingLong(AgentStep::lastSequence)).orElse(null);
        if (model == null) return null;
        return history.stream()
                .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                        && step.state() == AgentStep.State.COMPLETED
                        && step.input() != null && step.output() != null
                        && "harness.decision_invalid".equals(
                                step.input().path("phase").asText(""))
                        && model.id().value().equals(
                                step.input().path("modelStepId").asText("")))
                .max(Comparator.comparingLong(AgentStep::lastSequence))
                .map(step -> step.output().path("modelOutput"))
                .filter(feedback -> "INVALID_DECISION_ARGUMENTS".equals(
                        feedback.path("errorCode").asText("")))
                .orElse(null);
    }

    static void addDecisionRejection(ObjectNode target, JsonNode rejection) {
        if (rejection == null) return;
        target.put("decisionErrorCode", bounded(rejection.path("reasonCode")
                .asText("INVALID_DECISION_ARGUMENTS"), 128));
        target.put("decisionErrorDetail", bounded(rejection.path("message").asText(""), 1_000));
    }

    static String protocolRepairFeedback(JsonNode rejection) {
        String feedback = "Submit exactly one harness_submit_decision control call "
                + "in its own tool-call batch. The decision must be CLAIM_DONE, "
                + "NEEDS_INPUT, or BLOCKED when ending the turn. Put ordinary "
                + "user-facing text only in userMessage. Earlier prose was not delivered. "
                + "Include the actual full answer there in its required format/schema, "
                + "not a completion summary (full schema-matching JSON for JSON tasks). "
                + "Do not infer completion "
                + "from final prose; continue tool work first if necessary. "
                + "evidenceRefs is optional: copy only exact host-issued IDs from tool "
                + "response evidenceRefs, or use []. Never use descriptions as IDs. "
                + "Correct rejected control arguments without repeating business tools.";
        if (rejection == null) return feedback;
        String prefix = feedback + "\nPrevious control rejection and available IDs: ";
        ObjectNode correction = JsonNodeFactory.instance.objectNode()
                .put("errorCode", "INVALID_DECISION_ARGUMENTS")
                .put("reasonCode", bounded(rejection.path("reasonCode").asText(""), 128))
                .put("message", bounded(rejection.path("message").asText(""), 500));
        correction.putArray("availableEvidenceRefs");
        correction.putArray("availableCriterionIds");
        int budget = 1_950 - prefix.length();
        fitDiagnostics(correction, budget);
        if ("UNKNOWN_CRITERION_ID".equals(rejection.path("reasonCode").asText(""))) {
            appendCompleteIds(correction, rejection, "availableCriterionIds", budget);
            appendCompleteIds(correction, rejection, "availableEvidenceRefs", budget);
        } else {
            appendCompleteIds(correction, rejection, "availableEvidenceRefs", budget);
            appendCompleteIds(correction, rejection, "availableCriterionIds", budget);
        }
        // Bound the list as whole IDs, so the correction remains parseable after recovery.
        return prefix + correction;
    }

    private static void fitDiagnostics(ObjectNode correction, int characterBudget) {
        // JSON escaping can expand bounded diagnostic text. Fit serialized fields before adding
        // whole IDs; never truncate the final JSON or a recovery evidence identifier.
        for (String field : List.of("message", "reasonCode")) {
            String value = correction.path(field).asText("");
            while (!value.isEmpty() && correction.toString().length() > characterBudget) {
                value = value.substring(0, value.offsetByCodePoints(value.length(), -1));
                correction.put(field, value);
            }
        }
        if (correction.toString().length() > characterBudget) {
            throw new IllegalStateException("protocol repair feedback exceeds its bounded envelope");
        }
    }

    private static void appendCompleteIds(ObjectNode target, JsonNode source,
            String field, int characterBudget) {
        for (JsonNode id : source.path(field)) {
            if (!id.isTextual() || id.asText().isBlank()) continue;
            var ids = target.withArray(field);
            ids.add(id.asText());
            if (target.toString().length() > characterBudget) {
                ids.remove(ids.size() - 1);
                break;
            }
        }
    }

    private static String bounded(String value, int maxCharacters) {
        String safe = com.javaclaw.util.SensitiveDataRedactor.redactText(value == null ? "" : value);
        return safe.length() <= maxCharacters ? safe : safe.substring(0, maxCharacters) + "…";
    }

}
