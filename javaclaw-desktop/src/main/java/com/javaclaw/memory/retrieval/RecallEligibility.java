package com.javaclaw.memory.retrieval;

import com.javaclaw.memory.model.Fact;

/** Shared eligibility for facts exposed by immediate and deferred memory recall. */
public final class RecallEligibility {
    private RecallEligibility() { }

    public static boolean fact(Fact fact) {
        return fact != null && !fact.superseded && !fact.contested
                && (!fact.pending || fact.userAsserted || fact.userEdited
                    || "HABIT_REVIEW".equals(fact.sourceKind)
                    || "DISTILLED".equals(fact.sourceKind));
    }
}
