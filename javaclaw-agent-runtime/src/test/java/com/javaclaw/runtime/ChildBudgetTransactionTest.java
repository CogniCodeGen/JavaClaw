package com.javaclaw.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.TurnBudget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChildBudgetTransactionTest {
    @Test
    void failedCommitLeavesEveryBudgetDimensionAvailable() {
        BudgetAccount account = account();
        AtomicInteger commits = new AtomicInteger();
        assertThrows(
                IllegalStateException.class,
                () -> account.reserveChild(new ReservedChildBudget(50, 50, 2), () -> {
                    commits.incrementAndGet();
                    throw new IllegalStateException("transaction rolled back");
                }));
        assertEquals(100, account.remainingInputTokens());
        assertEquals(100, account.remainingOutputTokens());
        account.reserveChild(new ReservedChildBudget(100, 100, 4));
        assertEquals(0, account.remainingInputTokens());
        assertEquals(0, account.remainingOutputTokens());
        assertEquals(1, commits.get());
        assertThrows(BudgetExceededException.class, account::consumeToolCall);
    }

    @Test
    void competingReservationsCommitOnlyWithinSharedParentLimit() throws Exception {
        BudgetAccount account = account();
        AtomicInteger committed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < 8; index++) {
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        return account.reserveChild(new ReservedChildBudget(25, 25, 1), () -> {
                            committed.incrementAndGet();
                            return true;
                        });
                    } catch (BudgetExceededException expected) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    accepted++;
                }
            }
            assertEquals(4, accepted);
            assertEquals(4, committed.get());
            assertEquals(0, account.remainingInputTokens());
            assertEquals(0, account.remainingOutputTokens());
        }
    }

    @Test
    void restartReplaysReservationsAlongsideActualUsageWithoutResettingDeadline() {
        TurnBudget limit = new TurnBudget(100, 100, 4, 4, Duration.ofMinutes(1));
        BudgetAccount recovered =
                new BudgetAccount(limit, RuntimeFixtures.CLOCK, RuntimeFixtures.NOW, new ModelUsage(10, 20, 0, 0), 1);
        recovered.reserveChild(new ReservedChildBudget(30, 40, 2));
        assertEquals(60, recovered.remainingInputTokens());
        assertEquals(40, recovered.remainingOutputTokens());
        assertEquals(1, recovered.toolCalls());
        recovered.consumeToolCall();
        assertThrows(BudgetExceededException.class, recovered::consumeToolCall);
        assertThrows(BudgetExceededException.class, () -> recovered.consume(new ModelUsage(61, 0, 0, 0)));
        assertEquals(60, recovered.remainingInputTokens());
    }

    private static BudgetAccount account() {
        return new BudgetAccount(new TurnBudget(100, 100, 4, 4, Duration.ofMinutes(1)), RuntimeFixtures.CLOCK);
    }
}
