package com.javaclaw.framework.spi;

import java.util.Objects;

/** A local, predeclared view postcondition that verifies one uncertain desktop action. */
public record EffectCheckpointV1(
        EffectReconciliationV1 effect,
        long contractSequence,
        String clickCriterionId,
        String viewCriterionId,
        String requiredSubject,
        String observationEvidenceRef) {
    public EffectCheckpointV1 {
        effect = Objects.requireNonNull(effect, "effect");
        if (contractSequence <= 0) throw new IllegalArgumentException("contractSequence must be positive");
        clickCriterionId = required(clickCriterionId, "clickCriterionId");
        viewCriterionId = required(viewCriterionId, "viewCriterionId");
        requiredSubject = required(requiredSubject, "requiredSubject");
        observationEvidenceRef = required(observationEvidenceRef, "observationEvidenceRef");
    }

    private static String required(String value, String name) {
        value = Objects.requireNonNull(value, name).strip();
        if (value.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
