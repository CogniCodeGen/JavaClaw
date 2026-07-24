package com.javaclaw.workflow.runtime;

import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.model.RunStatus;

import java.util.Objects;
import java.util.UUID;

/** 单次图执行的可持久化状态；修改只由 GraphEngine 所在线程完成。 */
public final class GraphRun {
    private final String id;
    private final String workflowId;
    private final int workflowVersion;
    private final String threadId;
    private final GraphDefinition definition;
    private final long createdAt;
    private GraphState state;
    private RunStatus status;
    private String currentNodeId;
    private String nextNodeId;
    private int stepCount;
    private int checkpointSeq;
    private String output;
    private String error;
    private NodeResult.Interrupt interrupt;
    private long updatedAt;

    public GraphRun(GraphDefinition definition, String threadId, GraphState initialState) {
        this(UUID.randomUUID().toString(), definition.id(), definition.version(), threadId,
                definition, initialState, RunStatus.CREATED, null, definition.startNodeId(),
                0, 0, null, null, null, System.currentTimeMillis(), System.currentTimeMillis());
    }

    public GraphRun(String id, String workflowId, int workflowVersion, String threadId,
                    GraphDefinition definition, GraphState state, RunStatus status,
                    String currentNodeId, String nextNodeId, int stepCount, int checkpointSeq,
                    String output, String error, NodeResult.Interrupt interrupt,
                    long createdAt, long updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.workflowId = Objects.requireNonNull(workflowId);
        this.workflowVersion = workflowVersion;
        this.threadId = Objects.requireNonNull(threadId);
        this.definition = Objects.requireNonNull(definition);
        this.state = state == null ? new GraphState() : state;
        this.status = status == null ? RunStatus.CREATED : status;
        this.currentNodeId = currentNodeId;
        this.nextNodeId = nextNodeId;
        this.stepCount = stepCount;
        this.checkpointSeq = checkpointSeq;
        this.output = output;
        this.error = error;
        this.interrupt = interrupt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String id() { return id; }
    public String workflowId() { return workflowId; }
    public int workflowVersion() { return workflowVersion; }
    public String threadId() { return threadId; }
    public GraphDefinition definition() { return definition; }
    public GraphState state() { return state; }
    public RunStatus status() { return status; }
    public String currentNodeId() { return currentNodeId; }
    public String nextNodeId() { return nextNodeId; }
    public int stepCount() { return stepCount; }
    public int checkpointSeq() { return checkpointSeq; }
    public String output() { return output; }
    public String error() { return error; }
    public NodeResult.Interrupt interrupt() { return interrupt; }
    public long createdAt() { return createdAt; }
    public long updatedAt() { return updatedAt; }

    void status(RunStatus value) { status = value; touch(); }
    void currentNodeId(String value) { currentNodeId = value; touch(); }
    void nextNodeId(String value) { nextNodeId = value; touch(); }
    void state(GraphState value) { state = value; touch(); }
    void stepCount(int value) { stepCount = value; touch(); }
    /** 由检查点存储实现分配本 run 内单调递增序号。 */
    public int nextCheckpointSeq() { touch(); return ++checkpointSeq; }
    void output(String value) { output = value; touch(); }
    void error(String value) { error = value; touch(); }
    void interrupt(NodeResult.Interrupt value) { interrupt = value; touch(); }
    private void touch() { updatedAt = System.currentTimeMillis(); }
}
