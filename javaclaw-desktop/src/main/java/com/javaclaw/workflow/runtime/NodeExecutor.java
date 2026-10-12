package com.javaclaw.workflow.runtime;

import com.javaclaw.workflow.model.NodeDefinition;

import java.util.List;

public interface NodeExecutor {
    String type();
    /** 执行器未提供去重契约时可以禁止内核自动重试副作用调用。 */
    default boolean supportsAutomaticRetry() { return true; }
    default List<String> validate(NodeDefinition node) { return List.of(); }
    NodeResult execute(NodeExecutionContext context) throws Exception;
}
