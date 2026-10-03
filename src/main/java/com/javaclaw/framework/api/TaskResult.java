package com.javaclaw.framework.api;

import java.util.List;
import java.util.Objects;

/** Durable user-task result, distinct from a terminal run state. */
public record TaskResult(
        TaskOutcome outcome,
        List<String> unmetCriteria,
        String stopReason,
        List<String> evidenceRefs,
        List<String> satisfiedCriteria) {
    public TaskResult {
        outcome = Objects.requireNonNull(outcome, "outcome");
        unmetCriteria = List.copyOf(Objects.requireNonNullElse(unmetCriteria, List.of()));
        stopReason = Objects.requireNonNullElse(stopReason, "");
        evidenceRefs = List.copyOf(Objects.requireNonNullElse(evidenceRefs, List.of()));
        satisfiedCriteria = List.copyOf(Objects.requireNonNullElse(satisfiedCriteria, List.of()));
        if ((outcome == TaskOutcome.VERIFIED_COMPLETE || outcome == TaskOutcome.DELIVERED)
                && !unmetCriteria.isEmpty()) {
            throw new IllegalArgumentException("completed result has unmet criteria");
        }
    }

    public TaskResult(TaskOutcome outcome, List<String> unmetCriteria,
                      String stopReason, List<String> evidenceRefs) {
        this(outcome, unmetCriteria, stopReason, evidenceRefs, List.of());
    }

    public static TaskResult unverified(String reason) {
        return new TaskResult(TaskOutcome.UNVERIFIED, List.of(), reason, List.of());
    }

    public static TaskResult notApplicable() {
        return new TaskResult(TaskOutcome.NOT_APPLICABLE, List.of(), "", List.of());
    }

    public static TaskResult delivered() {
        return new TaskResult(TaskOutcome.DELIVERED, List.of(), "", List.of());
    }
}
