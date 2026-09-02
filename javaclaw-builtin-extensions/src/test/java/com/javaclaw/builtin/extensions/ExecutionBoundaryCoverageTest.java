package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.extension.spi.OrchestratedTurnSummary;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionBoundaryCoverageTest {
    private static final OrchestrationContracts.ExecutionBudget BUDGET =
            new OrchestrationContracts.ExecutionBudget(1, 10, 10, 1);

    @Test
    void executionBudgetAcceptsExactLimitsAndRejectsEveryIndependentOverflow() {
        OrchestrationContracts.ExecutionConsumption exact = ExecutionBudgetGuard.consume(
                BUDGET, OrchestrationContracts.ExecutionConsumption.zero(), summary(10, 10, 1));

        assertEquals(new OrchestrationContracts.ExecutionConsumption(1, 10, 10, 1), exact);
        assertAll(
                () -> assertBudgetRejected(
                        new OrchestrationContracts.ExecutionConsumption(1, 0, 0, 0), summary(0, 0, 0)),
                () -> assertBudgetRejected(
                        new OrchestrationContracts.ExecutionConsumption(0, 10, 0, 0), summary(1, 0, 0)),
                () -> assertBudgetRejected(
                        new OrchestrationContracts.ExecutionConsumption(0, 0, 10, 0), summary(0, 1, 0)),
                () -> assertBudgetRejected(
                        new OrchestrationContracts.ExecutionConsumption(0, 0, 0, 1), summary(0, 0, 1)));
    }

    @Test
    void toolBudgetCountsExactlyOneCommittedInvocation() {
        OrchestrationContracts.ExecutionConsumption first =
                ExecutionBudgetGuard.consumeTool(BUDGET, OrchestrationContracts.ExecutionConsumption.zero());

        assertEquals(new OrchestrationContracts.ExecutionConsumption(0, 0, 0, 1), first);
        assertThrows(IllegalStateException.class, () -> ExecutionBudgetGuard.consumeTool(BUDGET, first));
    }

    @Test
    void chunkerReturnsExplicitPlaceholderForMissingOrBlankExtraction() {
        assertEquals(List.of("（未提取到可检索文本）"), KnowledgeChunker.split(null, 10, 2));
        assertEquals(List.of("（未提取到可检索文本）"), KnowledgeChunker.split("  \n ", 10, 2));
    }

    @Test
    void chunkerPreservesOverlapAndAlwaysMovesForward() {
        assertEquals(List.of("abcd", "cdef"), KnowledgeChunker.split("abcdef", 4, 2));
        assertEquals(List.of("abc", "bcd", "cde", "def"), KnowledgeChunker.split("abcdef", 3, 20));
    }

    @Test
    void chunkerNeverSplitsUtf16SurrogatePairsAtEitherBoundary() {
        assertEquals(List.of("a", "😀", "b"), KnowledgeChunker.split("a😀b", 2, 0));
        assertEquals(List.of("a😀", "b"), KnowledgeChunker.split("a😀b", 3, 1));
    }

    private static void assertBudgetRejected(
            OrchestrationContracts.ExecutionConsumption current, OrchestratedTurnSummary turn) {
        assertThrows(IllegalStateException.class, () -> ExecutionBudgetGuard.consume(BUDGET, current, turn));
    }

    private static OrchestratedTurnSummary summary(long inputTokens, long outputTokens, int toolCalls) {
        return new OrchestratedTurnSummary("", inputTokens, outputTokens, toolCalls, Optional.empty(), List.of());
    }
}
