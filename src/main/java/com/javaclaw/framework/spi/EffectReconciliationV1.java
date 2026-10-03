package com.javaclaw.framework.spi;

import java.util.Objects;

/** Exact action and later frame that a trusted V2 task verifier has matched. */
public record EffectReconciliationV1(
        String actionInvocationId,
        String sessionId,
        String targetId,
        String actionObservationId,
        String evidenceObservationId) {
    public EffectReconciliationV1 {
        actionInvocationId = required(actionInvocationId, "actionInvocationId");
        sessionId = required(sessionId, "sessionId");
        targetId = required(targetId, "targetId");
        actionObservationId = required(actionObservationId, "actionObservationId");
        evidenceObservationId = required(evidenceObservationId, "evidenceObservationId");
    }

    private static String required(String value, String name) {
        value = Objects.requireNonNull(value, name).strip();
        if (value.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
