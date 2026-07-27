package com.javaclaw.workflow.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.AppDatabaseAccess;
import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.model.RunStatus;
import com.javaclaw.workflow.runtime.CheckpointPhase;
import com.javaclaw.workflow.runtime.GraphRun;
import com.javaclaw.workflow.runtime.NodeResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 工作区隔离的 H2 图检查点实现。每次写入均为独立短事务。 */
public final class H2GraphCheckpointStore implements GraphCheckpointStore {
    private static final Logger log = LoggerFactory.getLogger(H2GraphCheckpointStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String workspaceId;
    private final DatabaseAccess database;

    public H2GraphCheckpointStore(String workspaceId) {
        this(workspaceId, new AppDatabaseAccess());
    }

    public H2GraphCheckpointStore(String workspaceId, DatabaseAccess database) {
        this.workspaceId = Objects.requireNonNull(workspaceId);
        this.database = Objects.requireNonNull(database);
    }

    @Override
    public void createRun(GraphRun run) {
        try (Connection c = database.open();
            PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO workflow_runs(workspace_id,id,workflow_id,workflow_version,thread_id,
                         definition_json,state_json,status,current_node_id,next_node_id,step_count,
                         output_text,error_text,interrupt_json,created_at,updated_at)
                     VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                     """)) {
            bindRun(ps, run);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("INSERT 未写入运行记录: " + run.id());
            }
        } catch (Exception e) {
            logPersistenceFailure("create", run, e);
            throw new IllegalStateException("创建工作流运行记录失败", e);
        }
    }

    @Override
    public void createRunningRun(GraphRun run) {
        if (run.status() != RunStatus.RUNNING) {
            throw new IllegalArgumentException("新运行必须以 RUNNING 状态持久化: " + run.status());
        }
        try (Connection c = database.open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO workflow_runs(workspace_id,id,workflow_id,workflow_version,thread_id,
                            definition_json,state_json,status,current_node_id,next_node_id,step_count,
                            output_text,error_text,interrupt_json,created_at,updated_at)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """)) {
                    bindRun(ps, run);
                    if (ps.executeUpdate() != 1) {
                        throw new IllegalStateException("INSERT 未写入运行记录: " + run.id());
                    }
                }
                if (statusOnConnection(c, run.id()) != RunStatus.RUNNING) {
                    throw new WorkflowRunMissingException("新建后无法回读运行记录: " + run.id());
                }
                c.commit();
            } catch (Exception failure) {
                rollbackQuietly(c);
                throw failure;
            }
        } catch (Exception e) {
            logPersistenceFailure("create-running", run, e);
            if (e instanceof WorkflowRunMissingException missing) throw missing;
            throw new IllegalStateException("创建工作流运行记录失败", e);
        }
    }

    @Override
    public void activateExistingRun(GraphRun run, RunStatus expectedStatus) {
        if (run.status() != RunStatus.RUNNING) {
            throw new IllegalArgumentException("恢复运行必须切换为 RUNNING: " + run.status());
        }
        try (Connection c = database.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE workflow_runs SET state_json=?,status=?,current_node_id=?,next_node_id=?,
                        step_count=?,output_text=?,error_text=?,interrupt_json=?,updated_at=?
                    WHERE workspace_id=? AND id=? AND status=?
                    """)) {
                bindMutableRun(ps, run);
                ps.setString(12, expectedStatus.name());
                if (ps.executeUpdate() != 1) {
                    RunStatus actual = statusOnConnection(c, run.id());
                    if (actual == null) {
                        throw new WorkflowRunMissingException("待恢复运行记录不存在: " + run.id());
                    }
                    throw new WorkflowRunStateException(
                            "运行状态冲突: run=" + run.id() + ", expected="
                                    + expectedStatus + ", actual=" + actual);
                }
                c.commit();
            } catch (Exception failure) {
                rollbackQuietly(c);
                throw failure;
            }
        } catch (Exception e) {
            logPersistenceFailure("activate", run, e);
            if (e instanceof WorkflowRunMissingException missing) throw missing;
            if (e instanceof WorkflowRunStateException conflict) throw conflict;
            throw new IllegalStateException("恢复工作流运行记录失败", e);
        }
    }

    @Override
    public void updateRun(GraphRun run) {
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE workflow_runs SET state_json=?,status=?,current_node_id=?,next_node_id=?,
                         step_count=?,output_text=?,error_text=?,interrupt_json=?,updated_at=?
                     WHERE workspace_id=? AND id=?
                     """)) {
            bindMutableRun(ps, run);
            if (ps.executeUpdate() != 1) {
                throw new WorkflowRunMissingException("运行记录不存在: " + run.id());
            }
        } catch (Exception e) {
            logPersistenceFailure("update", run, e);
            if (e instanceof WorkflowRunMissingException missing) throw missing;
            throw new IllegalStateException("更新工作流运行记录失败", e);
        }
    }

    @Override
    public void checkpoint(GraphRun run, String nodeId, CheckpointPhase phase) {
        int seq = run.nextCheckpointSeq();
        try (Connection c = database.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement cp = c.prepareStatement("""
                    INSERT INTO workflow_checkpoints(workspace_id,run_id,seq,node_id,phase,state_json,created_at)
                    VALUES(?,?,?,?,?,?,?)
                    """)) {
                cp.setString(1, workspaceId);
                cp.setString(2, run.id());
                cp.setInt(3, seq);
                cp.setString(4, nodeId);
                cp.setString(5, phase.name());
                cp.setString(6, run.state().toJson());
                cp.setLong(7, System.currentTimeMillis());
                cp.executeUpdate();
            }
            updateRunOnConnection(c, run);
            c.commit();
        } catch (Exception e) {
            logPersistenceFailure("checkpoint-" + phase, run, e);
            if (e instanceof WorkflowRunMissingException missing) throw missing;
            throw new IllegalStateException("保存工作流检查点失败", e);
        }
    }

    @Override
    public GraphRun loadRun(String runId) {
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT *, COALESCE((SELECT MAX(seq) FROM workflow_checkpoints cp
                         WHERE cp.workspace_id=workflow_runs.workspace_id AND cp.run_id=workflow_runs.id),0) checkpoint_seq
                     FROM workflow_runs WHERE workspace_id=? AND id=?
                     """)) {
            ps.setString(1, workspaceId);
            ps.setString(2, runId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? readRun(rs) : null; }
        } catch (Exception e) {
            throw new IllegalStateException("读取工作流运行记录失败", e);
        }
    }

    @Override
    public List<GraphRun> listRuns(String workflowId, int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        List<GraphRun> out = new ArrayList<>();
        String sql = workflowId == null ? """
                SELECT *, 0 checkpoint_seq FROM workflow_runs WHERE workspace_id=?
                ORDER BY updated_at DESC LIMIT ?
                """ : """
                SELECT *, 0 checkpoint_seq FROM workflow_runs WHERE workspace_id=? AND workflow_id=?
                ORDER BY updated_at DESC LIMIT ?
                """;
        try (Connection c = database.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, workspaceId);
            if (workflowId == null) ps.setInt(2, capped);
            else { ps.setString(2, workflowId); ps.setInt(3, capped); }
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(readRun(rs)); }
            return List.copyOf(out);
        } catch (Exception e) {
            throw new IllegalStateException("列出工作流运行记录失败", e);
        }
    }

    @Override
    public GraphRun findWaitingRun(String workflowId, String threadId) {
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT *, 0 checkpoint_seq FROM workflow_runs
                     WHERE workspace_id=? AND workflow_id=? AND thread_id=? AND status='WAITING_INPUT'
                     ORDER BY updated_at DESC LIMIT 1
                     """)) {
            ps.setString(1, workspaceId); ps.setString(2, workflowId); ps.setString(3, threadId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? readRun(rs) : null; }
        } catch (Exception e) {
            throw new IllegalStateException("查找待输入工作流失败", e);
        }
    }

    @Override
    public GraphRun findRecoverableRun(String workflowId, String threadId) {
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT *, 0 checkpoint_seq FROM workflow_runs
                     WHERE workspace_id=? AND workflow_id=? AND thread_id=?
                       AND status IN ('PAUSED','RECOVERY_REQUIRED')
                     ORDER BY updated_at DESC LIMIT 1
                     """)) {
            ps.setString(1, workspaceId); ps.setString(2, workflowId); ps.setString(3, threadId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? readRun(rs) : null; }
        } catch (Exception e) {
            throw new IllegalStateException("查找待恢复工作流失败", e);
        }
    }

    @Override
    public GraphState loadThreadState(String workflowId, String threadId) {
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT state_json FROM workflow_threads WHERE workspace_id=? AND workflow_id=? AND thread_id=?
                     """)) {
            ps.setString(1, workspaceId); ps.setString(2, workflowId); ps.setString(3, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? GraphState.fromJson(rs.getString(1)) : new GraphState();
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取工作流 thread 状态失败", e);
        }
    }

    @Override
    public void saveThreadState(String workflowId, String threadId, GraphState state) {
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement("""
                     MERGE INTO workflow_threads(workspace_id,workflow_id,thread_id,state_json,updated_at)
                     KEY(workspace_id,workflow_id,thread_id) VALUES(?,?,?,?,?)
                     """)) {
            ps.setString(1, workspaceId); ps.setString(2, workflowId); ps.setString(3, threadId);
            ps.setString(4, state.toJson()); ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("保存工作流 thread 状态失败", e);
        }
    }

    @Override
    public int markRunningAsRecoveryRequired() {
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE workflow_runs SET status='RECOVERY_REQUIRED', updated_at=?
                     WHERE workspace_id=? AND status IN ('CREATED','RUNNING')
                     """)) {
            ps.setLong(1, System.currentTimeMillis()); ps.setString(2, workspaceId);
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("恢复遗留工作流状态失败", e);
        }
    }

    private void bindRun(PreparedStatement ps, GraphRun run) throws Exception {
        ps.setString(1, workspaceId); ps.setString(2, run.id()); ps.setString(3, run.workflowId());
        ps.setInt(4, run.workflowVersion()); ps.setString(5, run.threadId());
        ps.setString(6, MAPPER.writeValueAsString(run.definition())); ps.setString(7, run.state().toJson());
        ps.setString(8, run.status().name()); ps.setString(9, run.currentNodeId());
        ps.setString(10, run.nextNodeId()); ps.setInt(11, run.stepCount());
        ps.setString(12, run.output()); ps.setString(13, run.error());
        ps.setString(14, run.interrupt() == null ? null : MAPPER.writeValueAsString(run.interrupt()));
        ps.setLong(15, run.createdAt()); ps.setLong(16, run.updatedAt());
    }

    private void updateRunOnConnection(Connection c, GraphRun run) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE workflow_runs SET state_json=?,status=?,current_node_id=?,next_node_id=?,step_count=?,
                    output_text=?,error_text=?,interrupt_json=?,updated_at=? WHERE workspace_id=? AND id=?
                """)) {
            bindMutableRun(ps, run);
            if (ps.executeUpdate() != 1) {
                throw new WorkflowRunMissingException("检查点父运行记录不存在: " + run.id());
            }
        }
    }

    private void bindMutableRun(PreparedStatement ps, GraphRun run) throws Exception {
        ps.setString(1, run.state().toJson());
        ps.setString(2, run.status().name());
        ps.setString(3, run.currentNodeId());
        ps.setString(4, run.nextNodeId());
        ps.setInt(5, run.stepCount());
        ps.setString(6, run.output());
        ps.setString(7, run.error());
        ps.setString(8, run.interrupt() == null ? null : MAPPER.writeValueAsString(run.interrupt()));
        ps.setLong(9, run.updatedAt());
        ps.setString(10, workspaceId);
        ps.setString(11, run.id());
    }

    private RunStatus statusOnConnection(Connection c, String runId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT status FROM workflow_runs WHERE workspace_id=? AND id=?")) {
            ps.setString(1, workspaceId);
            ps.setString(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? RunStatus.valueOf(rs.getString(1)) : null;
            }
        }
    }

    private void logPersistenceFailure(String operation, GraphRun run, Exception failure) {
        log.error("工作流持久化失败 op={} db={} workspace={} run={} workflow={} thread={} status={} node={}",
                operation, database.description(), workspaceId, run.id(), run.workflowId(),
                run.threadId(), run.status(), run.currentNodeId(), failure);
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (Exception ignored) {
            // 保留原始异常。
        }
    }

    private GraphRun readRun(ResultSet rs) throws Exception {
        String interruptJson = rs.getString("interrupt_json");
        NodeResult.Interrupt interrupt = interruptJson == null ? null
                : MAPPER.readValue(interruptJson, NodeResult.Interrupt.class);
        return new GraphRun(rs.getString("id"), rs.getString("workflow_id"),
                rs.getInt("workflow_version"), rs.getString("thread_id"),
                MAPPER.readValue(rs.getString("definition_json"), GraphDefinition.class),
                GraphState.fromJson(rs.getString("state_json")), RunStatus.valueOf(rs.getString("status")),
                rs.getString("current_node_id"), rs.getString("next_node_id"), rs.getInt("step_count"),
                rs.getInt("checkpoint_seq"), rs.getString("output_text"), rs.getString("error_text"),
                interrupt, rs.getLong("created_at"), rs.getLong("updated_at"));
    }
}
