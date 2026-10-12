package com.javaclaw.agent.vision;

/** A bounded decision about one original condition in the same complete desktop frame. */
public record DesktopVisualConditionResult(String criterionId, String subject, Outcome outcome,
        boolean complete, double confidence, DesktopVisualTarget content) {
    public enum Outcome { TRUE, FALSE, UNKNOWN }

    public DesktopVisualConditionResult {
        if (criterionId == null || criterionId.isBlank() || subject == null || subject.isBlank()
                || outcome == null) throw new IllegalArgumentException("invalid desktop condition result");
        if (outcome == Outcome.UNKNOWN) {
            complete = false;
            confidence = 0;
            content = null;
        } else if (!complete || !Double.isFinite(confidence) || confidence < 0.85
                || confidence > 1 || content == null) {
            throw new IllegalArgumentException("unverified desktop condition decision");
        }
    }

    public static DesktopVisualConditionResult unknown(DesktopObservationCondition condition) {
        return new DesktopVisualConditionResult(condition.criterionId(), condition.subject(),
                Outcome.UNKNOWN, false, 0, null);
    }
}
