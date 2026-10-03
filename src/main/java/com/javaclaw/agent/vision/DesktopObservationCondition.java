package com.javaclaw.agent.vision;

/** An exact, host-owned condition from the current frozen task contract. */
public record DesktopObservationCondition(String criterionId, String subject) {
    public DesktopObservationCondition {
        if (criterionId == null || criterionId.isBlank() || criterionId.length() > 120
                || subject == null || subject.isBlank() || subject.length() > 240) {
            throw new IllegalArgumentException("invalid desktop observation condition");
        }
        criterionId = criterionId.strip();
        subject = subject.strip();
    }
}
