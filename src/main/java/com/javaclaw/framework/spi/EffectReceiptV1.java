package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Objects;

/** A bounded, versioned account of what a trusted tool boundary actually established. */
public record EffectReceiptV1(
        String invocationId,
        String tool,
        String operation,
        String target,
        Status status,
        Instant observedAt,
        String evidenceRef,
        String reason,
        String subject,
        java.util.Map<String, String> metadata) {

    public enum Status { FAILED, ACCEPTED, OBSERVED, VERIFIED, UNKNOWN }
    public static final int MAX_CONDITION_EVIDENCE_CHARACTERS = 32_000;

    public EffectReceiptV1 {
        invocationId = required(invocationId, "invocationId");
        tool = required(tool, "tool");
        operation = required(operation, "operation");
        target = target == null ? "" : target.strip();
        status = Objects.requireNonNull(status, "status");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        evidenceRef = required(evidenceRef, "evidenceRef");
        reason = reason == null ? "" : reason.strip();
        subject = subject == null ? "" : subject.strip();
        metadata = metadata == null ? java.util.Map.of() : java.util.Map.copyOf(metadata);
        if (target.length() > 512 || reason.length() > 512 || subject.length() > 128) {
            throw new IllegalArgumentException("receipt metadata exceeds bound");
        }
        if (metadata.size() > 12) throw new IllegalArgumentException("receipt metadata exceeds bound");
        for (var entry : metadata.entrySet()) {
            // Only a committed desktop observation may carry bounded structured content proof.
            int limit = entry.getKey().equals("conditionEvidence")
                    && tool.equals("desktop_session_observe") && operation.equals("observe")
                    && status == Status.OBSERVED ? MAX_CONDITION_EVIDENCE_CHARACTERS : 512;
            if (entry.getKey().length() > 64 || entry.getValue().length() > limit)
                throw new IllegalArgumentException("receipt metadata exceeds bound");
        }
    }

    public EffectReceiptV1(String invocationId, String tool, String operation, String target,
            Status status, Instant observedAt, String evidenceRef, String reason, String subject) {
        this(invocationId, tool, operation, target, status, observedAt, evidenceRef,
                reason, subject, java.util.Map.of());
    }

    public EffectReceiptV1(String invocationId, String tool, String operation, String target,
            Status status, Instant observedAt, String evidenceRef, String reason) {
        this(invocationId, tool, operation, target, status, observedAt, evidenceRef, reason, "");
    }

    public static EffectReceiptV1 unknown(String invocationId, String tool,
            Instant at, String evidenceRef) {
        return new EffectReceiptV1(invocationId, tool, "execute", "", Status.UNKNOWN,
                at, evidenceRef, "No trusted effect adapter established a postcondition");
    }

    public ObjectNode toJson() {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("invocationId", invocationId);
        value.put("tool", tool);
        value.put("operation", operation);
        value.put("target", target);
        value.put("status", status.name());
        value.put("observedAt", observedAt.toString());
        value.put("evidenceRef", evidenceRef);
        value.put("reason", reason);
        value.put("subject", subject);
        if (!metadata.isEmpty()) {
            ObjectNode extra = value.putObject("metadata");
            metadata.forEach(extra::put);
        }
        return value;
    }

    private static String required(String value, String name) {
        value = Objects.requireNonNull(value, name).strip();
        if (value.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
