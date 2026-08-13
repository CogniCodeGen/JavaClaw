package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.ToolClient;

import java.util.Objects;

/** Framework-neutral invocation supplied by the WorkflowEngine adapter. */
public record WorkflowNodeInvocation(
        String workflowRunId,
        String nodeId,
        JsonNode configuration,
        ObjectNode state,
        AgentClient agents,
        ToolClient tools,
        CancellationToken cancellation) {
    public WorkflowNodeInvocation {
        workflowRunId = Objects.requireNonNull(workflowRunId, "workflowRunId");
        nodeId = Objects.requireNonNull(nodeId, "nodeId");
        configuration = Objects.requireNonNull(configuration, "configuration").deepCopy();
        state = Objects.requireNonNull(state, "state").deepCopy();
        agents = Objects.requireNonNull(agents, "agents");
        tools = Objects.requireNonNull(tools, "tools");
        cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }
    @Override public JsonNode configuration() { return configuration.deepCopy(); }
    @Override public ObjectNode state() { return state.deepCopy(); }
}
