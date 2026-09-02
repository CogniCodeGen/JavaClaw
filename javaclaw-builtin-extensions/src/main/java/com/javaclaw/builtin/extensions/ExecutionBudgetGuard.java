package com.javaclaw.builtin.extensions;

import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.extension.spi.OrchestratedTurnSummary;

/** 自动化 Job checkpoint 的累计预算扣减。 */
final class ExecutionBudgetGuard {
    private ExecutionBudgetGuard() {}

    static OrchestrationContracts.ExecutionConsumption consume(
            OrchestrationContracts.ExecutionBudget budget,
            OrchestrationContracts.ExecutionConsumption current,
            OrchestratedTurnSummary turn) {
        var updated = new OrchestrationContracts.ExecutionConsumption(
                Math.addExact(current.turns(), 1),
                Math.addExact(current.inputTokens(), turn.inputTokens()),
                Math.addExact(current.outputTokens(), turn.outputTokens()),
                Math.addExact(current.toolCalls(), turn.toolCalls()));
        if (updated.turns() > budget.maximumTurns()
                || updated.inputTokens() > budget.inputTokens()
                || updated.outputTokens() > budget.outputTokens()
                || updated.toolCalls() > budget.toolCalls()) {
            throw new IllegalStateException("Automation Execution budget exhausted");
        }
        return updated;
    }

    static OrchestrationContracts.ExecutionConsumption consumeTool(
            OrchestrationContracts.ExecutionBudget budget, OrchestrationContracts.ExecutionConsumption current) {
        var updated = new OrchestrationContracts.ExecutionConsumption(
                current.turns(), current.inputTokens(), current.outputTokens(), Math.addExact(current.toolCalls(), 1));
        if (updated.toolCalls() > budget.toolCalls()) {
            throw new IllegalStateException("Automation Execution tool-call budget exhausted");
        }
        return updated;
    }
}
