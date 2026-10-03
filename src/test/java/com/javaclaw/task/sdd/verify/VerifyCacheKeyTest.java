package com.javaclaw.task.sdd.verify;

import com.javaclaw.task.sdd.spec.Criterion;
import com.javaclaw.task.sdd.spec.Scenario;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class VerifyCacheKeyTest {
    @Test
    void criterionAndScenarioFieldsCannotShareAnAcceptanceCacheEntry() {
        Scenario first = new Scenario("same", "left|right", "", "result A",
                new Criterion(Criterion.COMMAND_EXIT_ZERO, "check"));
        Scenario changed = new Scenario("same", "left", "right|", "result B",
                new Criterion(Criterion.COMMAND_EXIT_ZERO, "check"));
        Scenario differentType = new Scenario("same", "left|right", "", "result A",
                new Criterion(Criterion.FREEFORM, "check"));

        assertNotEquals(VerifyCache.key(first), VerifyCache.key(changed));
        assertNotEquals(VerifyCache.key(first), VerifyCache.key(differentType));
    }
}
