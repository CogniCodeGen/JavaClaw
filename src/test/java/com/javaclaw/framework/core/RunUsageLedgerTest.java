package com.javaclaw.framework.core;

import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.BudgetExceededException;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunScope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunUsageLedgerTest {

    @Test
    void reportsTheExactExceededModelBudgetDimensionAndStillAccountsTheResponse() {
        RunUsageLedger ledger = new RunUsageLedger();
        RunId inputRun = open(ledger, "input", budget(10, 20, "5"));
        ledger.record(inputRun, 6, 2, new BigDecimal("1"));

        BudgetExceededException input = assertThrows(BudgetExceededException.class,
                () -> ledger.record(inputRun, 5, 1, new BigDecimal("1")));
        assertEquals(BudgetExceededException.Kind.MODEL_INPUT_TOKENS, input.kind());
        assertEquals("11", input.actual());
        assertEquals("10", input.limit());
        assertEquals(11, ledger.snapshot(inputRun).inputTokens());

        RunId outputRun = open(ledger, "output", budget(100, 2, "5"));
        BudgetExceededException output = assertThrows(BudgetExceededException.class,
                () -> ledger.record(outputRun, 1, 3, BigDecimal.ZERO));
        assertEquals(BudgetExceededException.Kind.MODEL_OUTPUT_TOKENS, output.kind());
        assertEquals("3", output.actual());
        assertEquals("2", output.limit());

        RunId costRun = open(ledger, "cost", budget(100, 100, "0.50"));
        BudgetExceededException cost = assertThrows(BudgetExceededException.class,
                () -> ledger.record(costRun, 1, 1, new BigDecimal("0.75")));
        assertEquals(BudgetExceededException.Kind.MODEL_COST, cost.kind());
        assertEquals("0.75", cost.actual());
        assertEquals("0.50", cost.limit());
    }

    private static RunId open(RunUsageLedger ledger, String suffix, RunBudget budget) {
        RunId runId = new RunId("usage-" + suffix);
        ledger.open(runId, budget, new RunScope("workspace", "user", "session"));
        return runId;
    }

    private static RunBudget budget(long input, long output, String cost) {
        return new RunBudget(Duration.ofMinutes(1), input, output, 10,
                new BigDecimal(cost));
    }
}
