package com.javaclaw.framework.testkit;

import java.util.Comparator;
import java.util.List;

/** Framework-independent result that can be asserted from JUnit or a plugin build. */
public record ExtensionContractReport(List<ExtensionContractIssue> issues) {
    public ExtensionContractReport {
        issues = issues == null ? List.of() : issues.stream()
                .sorted(Comparator.comparing(ExtensionContractIssue::severity)
                        .thenComparing(ExtensionContractIssue::subject)
                        .thenComparing(ExtensionContractIssue::code)
                        .thenComparing(ExtensionContractIssue::message))
                .toList();
    }

    public boolean valid() {
        return issues.stream().noneMatch(issue ->
                issue.severity() == ExtensionContractIssue.Severity.ERROR);
    }

    public List<ExtensionContractIssue> errors() {
        return issues.stream().filter(issue ->
                issue.severity() == ExtensionContractIssue.Severity.ERROR).toList();
    }

    /** Convenient CI assertion without coupling the TestKit artifact to JUnit. */
    public void requireValid() {
        if (!valid()) throw new IllegalStateException("extension contract failed: " + errors());
    }
}
