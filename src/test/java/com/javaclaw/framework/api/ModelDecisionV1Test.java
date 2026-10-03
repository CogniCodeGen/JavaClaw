package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelDecisionV1Test {
    @Test
    void unmetCriterionIdsAreARequiredIndependentControlField() {
        var missing = JsonNodeFactory.instance.objectNode()
                .put("decision", "CONTINUE").put("userMessage", "working");
        assertThrows(IllegalArgumentException.class, () -> ModelDecisionV1.fromJson(missing));

        var decision = new ModelDecisionV1(ModelDecisionV1.Decision.CONTINUE,
                "working", List.of(), List.of("criterion-1"));
        assertEquals(List.of("criterion-1"),
                ModelDecisionV1.fromJson(decision.toJson()).unmetCriterionIds());
        assertEquals(0, new ModelDecisionV1(ModelDecisionV1.Decision.CLAIM_DONE,
                "done", List.of()).toJson().path("unmetCriterionIds").size());
    }

    @Test
    void completionClaimCannotSimultaneouslyReportUnmetCriteria() {
        assertThrows(IllegalArgumentException.class, () -> new ModelDecisionV1(
                ModelDecisionV1.Decision.CLAIM_DONE, "done", List.of(),
                List.of("criterion-1")));
        assertThrows(IllegalArgumentException.class, () -> new ModelDecisionV1(
                ModelDecisionV1.Decision.CONTINUE, "working", List.of(),
                List.of("same", "same")));
    }
}
