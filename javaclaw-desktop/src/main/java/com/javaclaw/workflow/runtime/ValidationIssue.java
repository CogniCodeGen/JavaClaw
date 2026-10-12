package com.javaclaw.workflow.runtime;

public record ValidationIssue(Severity severity, String elementId, String message) {
    public enum Severity { ERROR, WARNING }
}
