package com.javaclaw.framework.core;

import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.BudgetExceededException;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunScope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunUsageLedgerTest {

    @Test
    void 已逻辑失败的物理调用使同预算族的新请求及排队请求及时暂停() throws Exception {
        RunUsageLedger ledger = new RunUsageLedger();
        RunId parent = open(ledger, "abandoned-parent", RunBudget.UNBOUNDED);
        RunId child = new RunId("abandoned-child");
        ledger.open(child, RunBudget.UNBOUNDED, new RunScope("workspace", "user", "child"), parent);
        var lease = ledger.beginModelCall(parent);
        var queued = new java.util.concurrent.CompletableFuture<Throwable>();
        var waiting = new java.util.concurrent.CountDownLatch(1);
        Thread contender = Thread.startVirtualThread(() -> {
            waiting.countDown();
            try (var ignored = ledger.beginModelCall(child)) {
                queued.completeExceptionally(new AssertionError("旧物理调用尚未结束，不得准入新请求"));
            } catch (Throwable failure) { queued.complete(failure); }
        });
        try {
            assertTrue(waiting.await(1, java.util.concurrent.TimeUnit.SECONDS));
            lease.markAbandoned();
            assertTrue(queued.get(1, java.util.concurrent.TimeUnit.SECONDS)
                    instanceof com.javaclaw.framework.api.TurnPausedException);
            assertThrows(com.javaclaw.framework.api.TurnPausedException.class,
                    () -> ledger.beginModelCall(child));
            ledger.record(parent, 7, 3, BigDecimal.ONE);
            assertEquals(7, ledger.aggregateSnapshot(child).inputTokens() + ledger.snapshot(parent).inputTokens());
        } finally { lease.close(); contender.join(1000); }
        try (var ignored = ledger.beginModelCall(child)) {}
    }

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

    @Test void countsEachProviderChargeOnceAndEnforcesEveryAncestorBudget() {
        var observed = new java.util.ArrayList<RunId>();
        RunUsageLedger ledger = new RunUsageLedger((run, scope, input, output, cost) -> observed.add(run));
        RunId parent = open(ledger, "parent", budget(10, 100, "5"));
        RunId child = new RunId("child"), grandchild = new RunId("grandchild");
        ledger.open(child, RunBudget.UNBOUNDED, new RunScope("workspace", "user", "child-thread"), parent);
        ledger.open(grandchild, RunBudget.UNBOUNDED, new RunScope("workspace", "user", "grandchild-thread"), child);
        ledger.record(parent, 2, 1, BigDecimal.ONE);
        ledger.record(child, 3, 1, BigDecimal.ONE);
        ledger.record(grandchild, 4, 1, BigDecimal.ONE);
        assertEquals(2, ledger.snapshot(parent).inputTokens());
        assertEquals(7, ledger.aggregateSnapshot(child).inputTokens());
        assertEquals(9, ledger.aggregateSnapshot(parent).inputTokens());
        assertEquals(1, ledger.remainingBudget(grandchild).maxInputTokens());
        assertEquals(java.util.List.of(parent, child, grandchild), observed);
        var exceeded = assertThrows(BudgetExceededException.class,
                () -> ledger.record(grandchild, 2, 0, BigDecimal.ZERO));
        assertEquals("10", exceeded.limit());
        assertEquals(11, ledger.aggregateSnapshot(parent).inputTokens());
        assertEquals(6, ledger.snapshot(grandchild).inputTokens());
        assertThrows(BudgetExceededException.class, () -> ledger.beginModelCall(child));
        assertThrows(IllegalArgumentException.class, () -> ledger.open(new RunId("foreign"),
                RunBudget.UNBOUNDED, new RunScope("workspace", "different-user", "child"), parent));
    }

    @Test void replayReplacesDirectUsageWithoutObserversOrDuplicateAncestorCharges() {
        var observed = new java.util.concurrent.atomic.AtomicInteger();
        RunUsageLedger ledger = new RunUsageLedger((run, scope, input, output, cost) -> observed.incrementAndGet());
        RunId parent = open(ledger, "restore-parent", budget(10, 100, "5"));
        RunId child = new RunId("restored-child");
        ledger.open(child, RunBudget.UNBOUNDED, new RunScope("workspace", "user", "child"), parent);
        ledger.restore(child, 7, 1, BigDecimal.ONE);
        ledger.restore(child, 7, 1, BigDecimal.ONE);
        ledger.close(child);
        assertEquals(7, ledger.aggregateSnapshot(parent).inputTokens());
        assertEquals(0, observed.get());
        assertEquals(3, ledger.remainingBudget(parent).maxInputTokens());
    }

    @Test void siblingModelAdmissionWaitsForTheCurrentCallAndThenChecksSettledParentUsage() throws Exception {
        RunUsageLedger ledger = new RunUsageLedger();
        RunId parent = open(ledger, "concurrent", budget(10, 100, "5"));
        RunId first = new RunId("first-child"), next = new RunId("next-child");
        ledger.open(first, RunBudget.UNBOUNDED, new RunScope("workspace", "user", "first"), parent);
        ledger.open(next, RunBudget.UNBOUNDED, new RunScope("workspace", "user", "next"), parent);
        var waiting = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var lease = ledger.beginModelCall(first);
            var second = executor.submit(() -> {
                waiting.countDown();
                return assertThrows(BudgetExceededException.class, () -> ledger.beginModelCall(next));
            });
            try {
                waiting.await();
                ledger.record(first, 10, 1, BigDecimal.ONE);
            } finally { lease.close(); }
            assertEquals(BudgetExceededException.Kind.MODEL_INPUT_TOKENS, second.get().kind());
        }
    }

    @Test void preflightRejectsAProjectedCallAgainstAncestorWithoutChargingIt() {
        RunUsageLedger ledger = new RunUsageLedger();
        RunId parent = open(ledger, "preflight-parent", budget(10, 100, "5"));
        RunId child = new RunId("preflight-child");
        ledger.open(child, RunBudget.UNBOUNDED,
                new RunScope("workspace", "user", "child"), parent);
        ledger.record(parent, 9, 0, BigDecimal.ZERO);

        try (var admitted = ledger.beginModelCall(child)) {
            BudgetExceededException failure = assertThrows(BudgetExceededException.class,
                    () -> admitted.requireInputCapacity(2));
            assertEquals(BudgetExceededException.Kind.MODEL_INPUT_TOKENS, failure.kind());
            assertEquals("9", failure.actual());
            assertEquals("10", failure.limit());
            assertTrue(failure.getMessage().contains("remaining=1"));
            assertTrue(failure.getMessage().contains("approximatePromptFloor=2"));
            assertTrue(failure.getMessage().contains("provider was not called"));
            assertEquals(0, ledger.snapshot(child).inputTokens());
        }
        try (var admitted = ledger.beginModelCall(child)) {
            admitted.requireInputCapacity(1);
        }
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
