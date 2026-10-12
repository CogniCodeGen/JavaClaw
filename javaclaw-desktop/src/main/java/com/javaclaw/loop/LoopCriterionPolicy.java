package com.javaclaw.loop;

import com.javaclaw.agent.goal.SuccessCriterion;

import java.util.List;

/** Filters planner proposals; a model cannot authorize a literal output predicate. */
final class LoopCriterionPolicy {
    private LoopCriterionPolicy() { }

    static List<SuccessCriterion> fromModel(List<SuccessCriterion> proposed) {
        if (proposed == null) return List.of();
        return proposed.stream()
                .filter(criterion -> criterion != null
                        && !LoopConstants.CRITERION_OUTPUT_CONTAINS.equals(
                                criterion.normalizedType()))
                .toList();
    }
}
