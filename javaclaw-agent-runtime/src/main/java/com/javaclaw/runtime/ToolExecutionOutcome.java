package com.javaclaw.runtime;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.ToolDescriptor;

/**
 * 治理层工具结果及本次搜索展开的工具。
 *
 * @param result 脱敏执行结果
 * @param revealedTools 只允许来自冻结目录
 */
public record ToolExecutionOutcome(ToolCallResult result, List<ToolDescriptor> revealedTools) {
    /** 复制列表并校验结果。 */
    public ToolExecutionOutcome {
        Objects.requireNonNull(result, "result");
        revealedTools = List.copyOf(revealedTools);
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
