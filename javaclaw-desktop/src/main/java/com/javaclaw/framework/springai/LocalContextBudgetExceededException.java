package com.javaclaw.framework.springai;

import java.util.List;
import java.util.Map;

/** Local projection admission failure, distinct from a provider or tool protocol error. */
final class LocalContextBudgetExceededException extends IllegalStateException {
    static final String CODE = "LOCAL_CONTEXT_BUDGET_EXCEEDED";

    private final String budgetKind;
    private final int budgetCharacters;
    private final int requiredCharacters;
    private final Map<String, Integer> components;
    private final List<String> requiredEvidenceRefs;

    LocalContextBudgetExceededException(String budgetKind, int budgetCharacters,
            int requiredCharacters, Map<String, Integer> components,
            List<String> requiredEvidenceRefs) {
        super(CODE + ": " + budgetKind + " limit=" + budgetCharacters
                + ", required=" + requiredCharacters + ", components=" + components
                + ", evidenceRefs=" + requiredEvidenceRefs);
        this.budgetKind = budgetKind;
        this.budgetCharacters = budgetCharacters;
        this.requiredCharacters = requiredCharacters;
        this.components = java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(components));
        this.requiredEvidenceRefs = List.copyOf(requiredEvidenceRefs);
    }

    String budgetKind() { return budgetKind; }
    int budgetCharacters() { return budgetCharacters; }
    int requiredCharacters() { return requiredCharacters; }
    Map<String, Integer> components() { return components; }
    List<String> requiredEvidenceRefs() { return requiredEvidenceRefs; }
}
