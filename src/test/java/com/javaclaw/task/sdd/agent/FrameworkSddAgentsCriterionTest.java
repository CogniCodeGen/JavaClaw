package com.javaclaw.task.sdd.agent;

import com.javaclaw.task.sdd.spec.Criterion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameworkSddAgentsCriterionTest {
    @Test
    void generatedContentMatchIsNotACommandAcceptancePredicate() {
        SddDrafts.ScenarioDraft draft = new SddDrafts.ScenarioDraft();
        draft.criterionType = SddDrafts.CriterionKind.FREEFORM;
        draft.criterionPredicate = "生成可用的报表";

        Criterion normalized = FrameworkSddAgents.criterionFromGeneratedScenario(draft);

        assertEquals(Criterion.FREEFORM, normalized.normalizedType());
        assertEquals("生成可用的报表", normalized.predicate());
        assertThrows(IllegalArgumentException.class,
                () -> SddDrafts.CriterionKind.valueOf("OUTPUT_CONTAINS"));
    }
}
