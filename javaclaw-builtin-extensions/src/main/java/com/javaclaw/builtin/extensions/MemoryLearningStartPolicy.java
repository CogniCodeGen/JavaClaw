package com.javaclaw.builtin.extensions;

import java.util.Optional;

import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.builtin.contracts.OrchestrationContracts;

/** 学习定义拥有执行选择；通用启动参数只能确认该选择，预算不得小于实际冻结的固定上限。 */
final class MemoryLearningStartPolicy {
    private MemoryLearningStartPolicy() {}

    static void requireCompatible(OrchestrationContracts.StartRequest start, ExecutionOverrides configured) {
        ExecutionOverrides requested = start.execution();
        if (!matches(requested.role(), configured.role())
                || !matches(requested.provider(), configured.provider())
                || !matches(requested.permissionProfile(), configured.permissionProfile())
                || !matches(requested.approvalPolicy(), configured.approvalPolicy())
                || !matches(requested.reasoning(), configured.reasoning())) {
            throw new IllegalArgumentException("学习执行选择由学习配置管理；请移除启动覆盖，或先保存对应的学习配置");
        }
        var fixed = MemoryLearningResource.BUDGET;
        if (start.budget().inputTokens() < fixed.inputTokens()
                || start.budget().outputTokens() < fixed.outputTokens()) {
            throw new IllegalArgumentException("学习启动总预算不能低于固定的 16000 输入 / 2000 输出 token 上限");
        }
        requested.budget().ifPresent(MemoryLearningStartPolicy::requireTurnBudget);
    }

    private static boolean matches(Optional<?> requested, Optional<?> configured) {
        return requested.isEmpty() || requested.equals(configured);
    }

    private static void requireTurnBudget(TurnBudget requested) {
        TurnBudget fixed = MemoryLearningResource.restricted(ExecutionOverrides.empty())
                .budget()
                .orElseThrow();
        // 工具和子任务始终为零，是对任意请求上限的收窄；较大的 token/时间上限也只使用固定学习预算。
        if (requested.inputTokens() < fixed.inputTokens()
                || requested.outputTokens() < fixed.outputTokens()
                || requested.wallTime().compareTo(fixed.wallTime()) < 0) {
            throw new IllegalArgumentException("学习单 Turn 限制不能低于固定的 16000 输入 / 2000 输出 token 和 120 秒上限");
        }
    }
}
