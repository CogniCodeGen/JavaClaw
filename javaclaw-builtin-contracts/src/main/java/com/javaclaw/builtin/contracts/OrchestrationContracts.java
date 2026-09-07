package com.javaclaw.builtin.contracts;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;

/** 自动化 Definition 启动与冻结输入的共享契约。 */
public final class OrchestrationContracts {
    private OrchestrationContracts() {}

    /**
     * 尚未开放执行入口的 Definition 能力状态。
     *
     * @param executable 当前版本是否可创建 Execution
     * @param reason 不可用原因；可执行时为空字符串
     */
    public record Availability(boolean executable, String reason) {
        /** 校验状态与原因一致。 */
        public Availability {
            reason = Objects.requireNonNull(reason, "reason").strip();
            if ((executable && !reason.isEmpty()) || (!executable && reason.isEmpty())) {
                throw new IllegalArgumentException("availability reason does not match executable state");
            }
        }
    }

    /**
     * 从一个精确 Definition revision 启动 Execution。
     *
     * @param definitionId Definition 标识
     * @param execution 独立执行选择，Role 本身不授予权限
     * @param budget Execution 总预算；每个 Turn 仍受冻结配置约束
     */
    public record StartRequest(String definitionId, ExecutionOverrides execution, ExecutionBudget budget) {
        /** 校验启动输入。 */
        public StartRequest {
            definitionId = ContractValidation.text(definitionId, "definitionId");
            Objects.requireNonNull(execution, "execution");
            Objects.requireNonNull(budget, "budget");
        }
    }

    /**
     * 整个自动化 Execution 的总预算。
     *
     * @param maximumTurns 最大 Turn 数
     * @param inputTokens 累计输入 token 上限
     * @param outputTokens 累计输出 token 上限
     * @param toolCalls 累计工具调用上限
     */
    public record ExecutionBudget(int maximumTurns, long inputTokens, long outputTokens, int toolCalls) {
        /** 校验 Turn 数与 token 上限为有限正数，工具调用上限允许为零。 */
        public ExecutionBudget {
            if (maximumTurns < 1 || maximumTurns > 10_000 || inputTokens < 1 || outputTokens < 1 || toolCalls < 0) {
                throw new IllegalArgumentException("execution budget values must be finite and toolCalls nonnegative");
            }
        }
    }

    /**
     * 随 checkpoint 提交的累计消费；崩溃恢复不得归零。
     *
     * @param turns 已完成 Turn 数
     * @param inputTokens 已消费输入 token
     * @param outputTokens 已消费输出 token
     * @param toolCalls 已执行工具次数
     */
    public record ExecutionConsumption(int turns, long inputTokens, long outputTokens, int toolCalls) {
        /** 校验计数非负。 */
        public ExecutionConsumption {
            if (turns < 0 || inputTokens < 0 || outputTokens < 0 || toolCalls < 0) {
                throw new IllegalArgumentException("execution consumption must not be negative");
            }
        }

        /** @return 零消费 */
        public static ExecutionConsumption zero() {
            return new ExecutionConsumption(0, 0, 0, 0);
        }
    }

    /**
     * 平台共享消费与领域恢复指针的原子 checkpoint。
     *
     * @param domain 领域恢复指针
     * @param consumption 已提交累计消费
     */
    public record ExecutionCheckpoint(CanonicalPayload domain, ExecutionConsumption consumption) {
        /** 校验 checkpoint。 */
        public ExecutionCheckpoint {
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(consumption, "consumption");
        }
    }

    /**
     * Job 创建时冻结的执行输入。
     *
     * @param platform 平台权威冻结的 Role、模型、权限、单 Turn 预算与工具目录
     * @param parentThreadId 可选父 Thread
     * @param definition 完整 Definition 规范 payload
     * @param budget Execution 总预算
     */
    public record FrozenExecution(
            AutomationExecutionSnapshot platform,
            Optional<ThreadId> parentThreadId,
            CanonicalPayload definition,
            ExecutionBudget budget) {
        /** 校验冻结输入。 */
        public FrozenExecution {
            Objects.requireNonNull(platform, "platform");
            parentThreadId = Objects.requireNonNull(parentThreadId, "parentThreadId");
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(budget, "budget");
        }
    }

    /**
     * 单个工作单元提交的脱敏结果。
     *
     * @param unitId 稳定单元标识
     * @param turnId 对应 Turn
     * @param summary 用户可见摘要
     */
    public record UnitResult(String unitId, TurnId turnId, String summary) {
        /** 校验单元结果。 */
        public UnitResult {
            unitId = ContractValidation.text(unitId, "unitId");
            Objects.requireNonNull(turnId, "turnId");
            summary = Objects.requireNonNull(summary, "summary");
        }
    }
}
