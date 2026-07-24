package com.javaclaw.workflow.store;

import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.runtime.CheckpointPhase;
import com.javaclaw.workflow.runtime.GraphRun;

import java.util.List;

public interface GraphCheckpointStore {
    void createRun(GraphRun run);
    void updateRun(GraphRun run);
    void checkpoint(GraphRun run, String nodeId, CheckpointPhase phase);
    GraphRun loadRun(String runId);
    List<GraphRun> listRuns(String workflowId, int limit);
    GraphRun findWaitingRun(String workflowId, String threadId);
    GraphRun findRecoverableRun(String workflowId, String threadId);
    GraphState loadThreadState(String workflowId, String threadId);
    void saveThreadState(String workflowId, String threadId, GraphState state);
    int markRunningAsRecoveryRequired();
}
