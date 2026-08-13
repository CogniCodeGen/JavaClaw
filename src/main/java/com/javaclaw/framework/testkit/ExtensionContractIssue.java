package com.javaclaw.framework.testkit;

import java.util.Objects;

/** One deterministic Extension TestKit finding. */
public record ExtensionContractIssue(
        Severity severity,
        String code,
        String subject,
        String message) {

    public enum Severity { ERROR, WARNING }

    public ExtensionContractIssue {
        severity = Objects.requireNonNull(severity, "severity");
        code = required(code, "code");
        subject = required(subject, "subject");
        message = required(message, "message");
    }

    private static String required(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
