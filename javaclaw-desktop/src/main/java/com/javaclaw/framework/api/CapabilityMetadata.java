package com.javaclaw.framework.api;

import com.javaclaw.framework.spi.EffectReceiptV1;

import java.util.Objects;

/** Host-published receipt capability and declarative target binding. */
public record CapabilityMetadata(String id, String operation,
        EffectReceiptV1.Status evidenceCeiling, TargetKind targetKind,
        TargetSource targetSource, String targetArgument, String fixedTarget) {
    public enum TargetKind {
        FILE, URL, DESKTOP_APPLICATION, EMAIL_ADDRESS, SCHEDULE, COMMAND, RESOURCE
    }
    public enum TargetSource { ARGUMENT, FIXED, DECLARED }

    public CapabilityMetadata {
        id = required(id, "id");
        operation = Objects.requireNonNullElse(operation, "").strip();
        evidenceCeiling = Objects.requireNonNull(evidenceCeiling, "evidenceCeiling");
        targetKind = Objects.requireNonNull(targetKind, "targetKind");
        targetSource = Objects.requireNonNull(targetSource, "targetSource");
        targetArgument = Objects.requireNonNullElse(targetArgument, "").strip();
        fixedTarget = Objects.requireNonNullElse(fixedTarget, "").strip();
        if (targetSource == TargetSource.ARGUMENT && targetArgument.isEmpty()
                || targetSource == TargetSource.FIXED && fixedTarget.isEmpty()
                || targetSource == TargetSource.DECLARED
                        && (!targetArgument.isEmpty() || !fixedTarget.isEmpty())) {
            throw new IllegalArgumentException("invalid capability target binding");
        }
    }

    public CapabilityMetadata(String id, EffectReceiptV1.Status evidenceCeiling) {
        this(id, "", evidenceCeiling, TargetKind.RESOURCE,
                TargetSource.DECLARED, "", "");
    }

    private static String required(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException("blank " + field);
        return normalized;
    }
}
