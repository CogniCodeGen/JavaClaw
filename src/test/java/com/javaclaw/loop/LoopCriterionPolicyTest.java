package com.javaclaw.loop;

import com.javaclaw.agent.goal.SuccessCriterion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoopCriterionPolicyTest {
    @Test
    void modelCannotInventLiteralContentGate() {
        SuccessCriterion generated = new SuccessCriterion("output_contains", "完成");
        SuccessCriterion file = new SuccessCriterion("artifact_exists", "report.json");

        assertEquals(List.of(file), LoopCriterionPolicy.fromModel(List.of(generated, file)));
    }
}
