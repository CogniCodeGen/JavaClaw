package com.javaclaw.workflow.store;

import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.model.RunStatus;
import com.javaclaw.workflow.runtime.CheckpointPhase;
import com.javaclaw.workflow.runtime.GraphRun;

import java.util.List;

public interface GraphCheckpointStore {
    void createRun(GraphRun run);

    /** 原子创建一条已经进入 RUNNING 的新运行。 */
    void createRunningRun(GraphRun run);

    /**
     * 把已有运行从 expectedStatus 激活为当前 run 所携带的 RUNNING 状态。
     */
    void activateExistingRun(GraphRun run, RunStatus expectedStatus);

    void updateRun(GraphRun run);
    void checkpoint(GraphRun run, String nodeId, CheckpointPhase phase);
    GraphRun loadRun(String runId);
    List<GraphRun> listRuns(String workflowId, int limit);
    List<GraphRun> listNonTerminalRuns();
    GraphRun findWaitingRun(String workflowId, String threadId);
    GraphRun findRecoverableRun(String workflowId, String threadId);
    GraphState loadThreadState(String workflowId, String threadId);
    void saveThreadState(String workflowId, String threadId, GraphState state);
    int markRunningAsRecoveryRequired();
    /** Permanently removes one exact coordinator's runs, checkpoints and reusable state. */
    void deleteThread(String threadId);
}
