package com.javaclaw.framework.api;

import com.javaclaw.framework.spi.EffectReceiptV1;

import java.util.Objects;

/** A completion condition referring to a host-registered capability. */
public record TaskCriterionV3(String id, String description, String capabilityId,
                              CapabilityMetadata.TargetKind targetType, String target,
                              EffectReceiptV1.Status requiredEvidence,
                              String requiredSubject) {
    public TaskCriterionV3 {
        id = required(id, "id");
        description = required(description, "description");
        capabilityId = required(capabilityId, "capabilityId");
        targetType = Objects.requireNonNull(targetType, "targetType");
        target = required(target, "target");
        requiredEvidence = Objects.requireNonNull(requiredEvidence, "requiredEvidence");
        if (requiredEvidence != EffectReceiptV1.Status.ACCEPTED
                && requiredEvidence != EffectReceiptV1.Status.OBSERVED
                && requiredEvidence != EffectReceiptV1.Status.VERIFIED) {
            throw new IllegalArgumentException("unsupported required evidence");
        }
        requiredSubject = Objects.requireNonNullElse(requiredSubject, "").strip();
    }

    private static String required(String value, String name) {
        value = Objects.requireNonNull(value, name).strip();
        if (value.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
