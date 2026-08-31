package com.javaclaw.agent.runtime;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BudgetAccountTest {
    @Test
    void retirementDoesNotRefundUnsettledDescendantReservations() {
        BudgetAccount root = new BudgetAccount(8, 1000);
        BudgetAccount child = root.allocate(4, 600);
        BudgetAccount model = child.allocate(1, 300);
        child.retire();
        assertEquals(4, root.remainingCalls());
        assertThrows(IllegalStateException.class, () -> child.consumeCall("late call"));
        model.consumeCall("in flight");
        model.chargeTokens(100);
        model.close();
        assertEquals(7, root.remainingCalls());
        assertEquals(900, root.remainingTokens());
        assertEquals(1, root.usedCalls());
        child.close();
        root.close();
    }

    @Test
    void nestedOverageClearsEachAncestorReservationWithoutMintingTokens() {
        BudgetAccount root = new BudgetAccount(5, 1_000);
        try (BudgetAccount child = root.allocate(3, 600)) {
            try (BudgetAccount grandchild = child.allocate(2, 400)) {
                assertEquals(1, grandchild.consumeCall("first"));
                grandchild.chargeTokens(700);
                assertEquals(700, root.usedTokens());
            }
            assertEquals(300, root.remainingTokens());
        }
        assertEquals(300, root.remainingTokens());
        assertEquals(2, root.consumeCall("second"));
    }

    @Test
    void childReservationsCannotMintNewCallsOrTokens() {
        BudgetAccount root = new BudgetAccount(5, 1_000);
        try (BudgetAccount child = root.allocate(3, 600)) {
            assertEquals(2, root.remainingCalls());
            assertEquals(400, root.remainingTokens());
            child.consumeCall("child");
            child.chargeTokens(150);
            assertEquals(1, root.usedCalls());
            assertEquals(150, root.usedTokens());
            assertThrows(BudgetExceededException.class, () -> root.allocate(3, 10));
        }
        assertEquals(4, root.remainingCalls());
        assertEquals(850, root.remainingTokens());
    }

    @Test
    void explicitBudgetSurvivesVirtualThreadBoundariesAndCapsConcurrentCalls() throws Exception {
        BudgetAccount budget = new BudgetAccount(8, 1_000);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int index = 0; index < 30; index++) {
                tasks.add(() -> {
                    try {
                        budget.consumeCall("parallel");
                        return true;
                    } catch (BudgetExceededException exhausted) {
                        return false;
                    }
                });
            }
            int accepted = 0;
            for (var result : workers.invokeAll(tasks)) {
                if (result.get()) {
                    accepted++;
                }
            }
            assertEquals(8, accepted);
            assertEquals(8, budget.usedCalls());
        }
    }
}
