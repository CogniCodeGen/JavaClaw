package com.javaclaw.framework.api;

public record DefinitionValidationIssue(
        Severity severity, String path, String code, String message) {
    public enum Severity { ERROR, WARNING }
}
