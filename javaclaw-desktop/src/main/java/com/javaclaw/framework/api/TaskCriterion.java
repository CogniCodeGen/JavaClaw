package com.javaclaw.framework.api;

import java.util.Locale;
import java.util.Objects;

/** One explicit completion condition and the minimum trusted receipt it requires. */
public record TaskCriterion(
        String id,
        String description,
        String target,
        String requiredOperation,
        String requiredEvidence,
        String requiredSubject) {
    public TaskCriterion {
        id = required(id, "id");
        description = required(description, "description");
        target = required(target, "target");
        requiredOperation = required(requiredOperation, "requiredOperation");
        requiredEvidence = required(requiredEvidence, "requiredEvidence").toUpperCase(Locale.ROOT);
        if (!requiredEvidence.equals("ACCEPTED") && !requiredEvidence.equals("OBSERVED")
                && !requiredEvidence.equals("VERIFIED")) {
            throw new IllegalArgumentException("unsupported requiredEvidence: " + requiredEvidence);
        }
        requiredSubject = requiredSubject == null ? "" : requiredSubject.strip().toLowerCase(Locale.ROOT);
    }

    public TaskCriterion(String id, String description, String target,
                         String requiredOperation, String requiredEvidence) {
        this(id, description, target, requiredOperation, requiredEvidence, "");
    }

    private static String required(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
