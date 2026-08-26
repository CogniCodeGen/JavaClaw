package com.javaclaw.framework.core;

import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.BudgetExceededException;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.ModelTokenUsage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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

    @Test
    void detailedSubsetsAreObservableWithoutDoubleCountingTotals() {
        java.util.concurrent.atomic.AtomicReference<ModelTokenUsage> observed =
                new java.util.concurrent.atomic.AtomicReference<>();
        RunUsageLedger ledger = new RunUsageLedger(new com.javaclaw.framework.spi.RunUsageObserver() {
            @Override public void recorded(RunId runId, RunScope scope, long inputTokens,
                                           long outputTokens, BigDecimal cost) { }
            @Override public void recorded(RunId runId, RunScope scope, ModelTokenUsage usage,
                                           BigDecimal cost) { observed.set(usage); }
        });
        RunId run = open(ledger, "details", budget(1_000, 1_000, "5"));
        ledger.record(run, new ModelTokenUsage(100, 40, 5, 30, 12, 1), BigDecimal.ZERO);
        RunUsageLedger.UsageSnapshot snapshot = ledger.snapshot(run);

        assertEquals(130, snapshot.inputTokens() + snapshot.outputTokens());
        assertEquals(40, snapshot.cacheReadInputTokens());
        assertEquals(5, snapshot.cacheWriteInputTokens());
        assertEquals(12, snapshot.reasoningTokens());
        assertEquals(1, snapshot.modelCalls());
        assertEquals(130, observed.get().totalTokens());
    }

    @Test
    void durableCallIdentityPreventsDuplicateProjectionAndObserverFailureIsNonFatal() {
        java.util.concurrent.atomic.AtomicInteger projections = new java.util.concurrent.atomic.AtomicInteger();
        RunUsageLedger ledger = new RunUsageLedger(new com.javaclaw.framework.spi.RunUsageObserver() {
            @Override public void recorded(RunId runId, RunScope scope, long inputTokens,
                                           long outputTokens, BigDecimal cost) { }
            @Override public void recorded(RunId runId, RunScope scope, ModelTokenUsage usage,
                                           BigDecimal cost) {
                projections.incrementAndGet();
                throw new IllegalStateException("daily projection unavailable");
            }
        });
        RunId run = open(ledger, "idempotent", budget(1_000, 1_000, "5"));
        ModelTokenUsage usage = new ModelTokenUsage(10, 2, 1, 4, 3, 1);

        assertDoesNotThrow(() -> ledger.recordOnce(run, "call-1", usage, BigDecimal.ZERO));
        assertDoesNotThrow(() -> ledger.recordOnce(run, "call-1", usage, BigDecimal.ZERO));

        assertEquals(10, ledger.snapshot(run).inputTokens());
        assertEquals(4, ledger.snapshot(run).outputTokens());
        assertEquals(1, ledger.snapshot(run).modelCalls());
        assertEquals(1, projections.get());
    }

    @Test
    void expiredTerminalAccountCanBeRestoredWithoutReprojectingHistoricalCalls() {
        AtomicLong ticker = new AtomicLong(1L);
        java.util.concurrent.atomic.AtomicInteger projections = new java.util.concurrent.atomic.AtomicInteger();
        var observer = new com.javaclaw.framework.spi.RunUsageObserver() {
            @Override public void recorded(RunId runId, RunScope scope, long inputTokens,
                                           long outputTokens, BigDecimal cost) { }
            @Override public void recorded(RunId runId, RunScope scope, ModelTokenUsage usage,
                                           BigDecimal cost) { projections.incrementAndGet(); }
        };
        RunUsageLedger ledger = new RunUsageLedger(observer, ticker::get);
        RunBudget budget = budget(1_000, 1_000, "5");
        RunId run = open(ledger, "restored", budget);
        ModelTokenUsage first = new ModelTokenUsage(10, 2);
        ledger.recordOnce(run, "call-1", first, BigDecimal.ZERO);
        ledger.close(run);
        ticker.set(1L + Duration.ofHours(2).toNanos());
        // Trigger expiry, then recreate exactly what the durable event stream contains.
        org.junit.jupiter.api.Assertions.assertFalse(ledger.hasAccount(run));
        ledger.restoreAccount(run, budget, new RunScope("workspace", "user", "session"),
                new DurableUsageHistory.Snapshot(first, BigDecimal.ZERO, Set.of("call-1")), true);

        ledger.recordOnce(run, "call-1", first, BigDecimal.ZERO);
        ledger.recordOnce(run, "call-2", new ModelTokenUsage(3, 1), BigDecimal.ZERO);

        assertEquals(13, ledger.snapshot(run).inputTokens());
        assertEquals(3, ledger.snapshot(run).outputTokens());
        assertEquals(2, projections.get(), "history restoration must not notify the observer");
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
