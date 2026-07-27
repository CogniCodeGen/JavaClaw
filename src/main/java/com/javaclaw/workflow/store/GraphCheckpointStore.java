package com.javaclaw.workflow.store;

import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.model.RunStatus;
import com.javaclaw.workflow.runtime.CheckpointPhase;
import com.javaclaw.workflow.runtime.GraphRun;

import java.util.List;

public interface GraphCheckpointStore {
    void createRun(GraphRun run);

    /**
     * 原子创建一条已经进入 RUNNING 的新运行。
     *
     * <p>默认实现供纯内存测试 Store 兼容；持久化实现必须覆写并校验写入结果。</p>
     */
    default void createRunningRun(GraphRun run) {
        createRun(run);
    }

    /**
     * 把已有运行从 expectedStatus 激活为当前 run 所携带的 RUNNING 状态。
     */
    default void activateExistingRun(GraphRun run, RunStatus expectedStatus) {
        updateRun(run);
    }

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
