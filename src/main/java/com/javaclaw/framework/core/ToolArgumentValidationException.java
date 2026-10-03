package com.javaclaw.framework.core;

import com.javaclaw.framework.api.DefinitionValidationIssue;

import java.util.List;
import java.util.Objects;

/** A model-supplied tool call that failed input-schema validation before execution. */
public final class ToolArgumentValidationException extends IllegalArgumentException {
    private final List<DefinitionValidationIssue> issues;

    public ToolArgumentValidationException(List<DefinitionValidationIssue> issues) {
        super("invalid tool arguments: " + issues);
        this.issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        if (this.issues.isEmpty()) {
            throw new IllegalArgumentException("validation issues must not be empty");
        }
    }

    public List<DefinitionValidationIssue> issues() {
        return issues;
    }
}
