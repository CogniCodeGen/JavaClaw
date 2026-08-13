package com.javaclaw.framework.api;

import java.util.List;

public record DefinitionValidationResult(List<DefinitionValidationIssue> issues) {
    public DefinitionValidationResult {
        issues = List.copyOf(issues);
    }

    public boolean valid() {
        return issues.stream().noneMatch(issue ->
                issue.severity() == DefinitionValidationIssue.Severity.ERROR);
    }
}
