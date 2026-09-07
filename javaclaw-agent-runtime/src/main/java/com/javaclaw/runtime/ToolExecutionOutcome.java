package com.javaclaw.runtime;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolExecutionFact;

/**
 * 治理层工具结果及本次搜索展开的工具。
 *
 * @param result 脱敏执行结果
 * @param revealedTools 只允许来自冻结目录
 * @param facts 仅由可信平台边界产生的有界副作用事实
 */
public record ToolExecutionOutcome(
        ToolCallResult result, List<ToolDescriptor> revealedTools, List<ToolExecutionFact> facts) {
    /** 复制列表并校验结果。 */
    public ToolExecutionOutcome {
        Objects.requireNonNull(result, "result");
        revealedTools = List.copyOf(revealedTools);
        facts = List.copyOf(facts);
        if (facts.size() > 256) {
            throw new IllegalArgumentException("单次工具执行事实不能超过 256 条");
        }
    }

    /**
     * 创建没有额外平台事实的兼容结果。
     *
     * @param result 执行结果
     * @param revealedTools 冻结工具目录中的展开项
     */
    public ToolExecutionOutcome(ToolCallResult result, List<ToolDescriptor> revealedTools) {
        this(result, revealedTools, List.of());
    }

    /**
     * 创建不展开目录的结果。
     *
     * @param result 工具结果
     * @return outcome
     */
    public static ToolExecutionOutcome resultOnly(ToolCallResult result) {
        return new ToolExecutionOutcome(result, List.of());
    }
}
