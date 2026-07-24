package com.javaclaw.workflow.runtime;

import com.javaclaw.workflow.model.NodeDefinition;

import java.util.List;

public interface NodeExecutor {
    String type();
    default List<String> validate(NodeDefinition node) { return List.of(); }
    NodeResult execute(NodeExecutionContext context) throws Exception;
}
