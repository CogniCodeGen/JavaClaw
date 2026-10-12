package com.javaclaw.workflow.runtime;

import com.javaclaw.workflow.model.RunStatus;

/** 图运行事件，供聊天桥与工作流中心共同消费。 */
public sealed interface GraphEvent permits GraphEvent.RunStarted, GraphEvent.NodeStarted,
        GraphEvent.NodeCompleted, GraphEvent.NodeRetry, GraphEvent.Transition,
        GraphEvent.Interrupted, GraphEvent.RunFinished {

    String runId();

    record RunStarted(String runId, String workflowId, String threadId) implements GraphEvent {}
    record NodeStarted(String runId, String nodeId, String label, int step) implements GraphEvent {}
    record NodeCompleted(String runId, String nodeId, String label, int step) implements GraphEvent {}
    record NodeRetry(String runId, String nodeId, int attempt, String message) implements GraphEvent {}
    record Transition(String runId, String source, String target, String edgeId) implements GraphEvent {}
    record Interrupted(String runId, String nodeId, String prompt) implements GraphEvent {}
    record RunFinished(String runId, RunStatus status, String output, String error) implements GraphEvent {}
}
