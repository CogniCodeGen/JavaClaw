package com.javaclaw.server.persistence;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.TurnId;

/** Extension Job 失败单元保留的脱敏 Turn 与 EffectReceipt 证据。 */
record ExtensionJobFailureEvidence(String errorCode, Optional<TurnId> turnId, Optional<String> effectReceiptKey) {
    ExtensionJobFailureEvidence {
        errorCode = requireCode(errorCode);
        turnId = Objects.requireNonNull(turnId, "turnId");
        effectReceiptKey = Objects.requireNonNull(effectReceiptKey, "effectReceiptKey")
                .map(value -> text(value, "effectReceiptKey", 500));
    }

    static ExtensionJobFailureEvidence code(String errorCode) {
        return new ExtensionJobFailureEvidence(errorCode, Optional.empty(), Optional.empty());
    }

    private static String requireCode(String value) {
        String normalized = text(value, "errorCode", 160);
        if (!normalized.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("errorCode contains unsupported characters");
        }
        return normalized;
    }

    private static String text(String value, String name, int limit) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty() || normalized.length() > limit) {
            throw new IllegalArgumentException(name + " length is invalid");
        }
        return normalized;
    }
}
