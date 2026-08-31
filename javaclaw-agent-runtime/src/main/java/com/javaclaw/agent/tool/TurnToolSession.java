package com.javaclaw.agent.tool;

import java.util.List;

import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.core.api.ToolExecutionResult;

/** Immutable tool catalog and execution authority for one Turn. */
public interface TurnToolSession extends AutoCloseable {
    /** 返回本 Turn 固定的可见工具描述符列表；不重新发现已更新的插件目录。 */
    List<ToolDescriptor> availableTools();

    /** 执行快照内的工具提案，仍需复核动态可用性和完整治理；返回审计及模型结果。 */
    ToolExecutionResult execute(ModelToolCall call) throws Exception;

    /** 以稳定领域步骤标识执行工具；恢复同一步骤必须沿用标识，新的有意操作必须使用新步骤。 */
    default ToolExecutionResult execute(ModelToolCall call, String stepIdentity) throws Exception {
        return execute(call);
    }

    @Override
    default void close() {}
}
