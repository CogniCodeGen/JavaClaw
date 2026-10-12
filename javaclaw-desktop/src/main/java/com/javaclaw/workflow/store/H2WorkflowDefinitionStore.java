package com.javaclaw.workflow.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.GraphKind;
import com.javaclaw.workflow.model.NodeDefinition;
import com.javaclaw.workflow.model.NodeType;
import com.javaclaw.workflow.model.ResumeSafety;
import com.javaclaw.workflow.model.RetryPolicy;
import com.javaclaw.workflow.model.EdgeDefinition;
import com.javaclaw.workflow.model.EdgeKind;
import com.javaclaw.workflow.runtime.GraphValidator;
import com.javaclaw.workflow.runtime.NodeExecutorRegistry;
import com.javaclaw.workflow.runtime.ValidationIssue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 自定义工作流草稿/发布存储。系统图不写入本表。 */
public final class H2WorkflowDefinitionStore implements WorkflowDefinitionStore {
    private final String workspaceId;
    private final DatabaseAccess database;
    private final ObjectMapper json;

    public H2WorkflowDefinitionStore(
            String workspaceId, DatabaseAccess database, ObjectMapper json) {
        this.workspaceId = Objects.requireNonNull(workspaceId);
        this.database = Objects.requireNonNull(database);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public List<WorkflowDefinitionRecord> list(boolean includeArchived) {
        List<WorkflowDefinitionRecord> out = new ArrayList<>();
        String sql = "SELECT * FROM workflow_definitions WHERE workspace_id=?"
                + (includeArchived ? "" : " AND archived=FALSE") + " ORDER BY updated_at DESC";
        try (Connection c = database.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, workspaceId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(read(rs)); }
            return List.copyOf(out);
        } catch (Exception e) {
            throw new IllegalStateException("列出工作流定义失败", e);
        }
    }

    @Override
    public WorkflowDefinitionRecord get(String id) {
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM workflow_definitions WHERE workspace_id=? AND id=?")) {
            ps.setString(1, workspaceId); ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? read(rs) : null; }
        } catch (Exception e) {
            throw new IllegalStateException("读取工作流定义失败", e);
        }
    }

    @Override
    public WorkflowDefinitionRecord createDraft(GraphDefinition definition) {
        if (definition.kind() == GraphKind.SYSTEM) throw new IllegalArgumentException("系统图不可保存为草稿");
        long now = System.currentTimeMillis();
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO workflow_definitions(workspace_id,id,name,description,draft_json,published_json,
                         draft_revision,published_version,archived,created_at,updated_at)
                     VALUES(?,?,?,?,?,?,?,?,?,?,?)
                     """)) {
            ps.setString(1, workspaceId); ps.setString(2, definition.id());
            ps.setString(3, definition.name()); ps.setString(4, definition.description());
            ps.setString(5, json.writeValueAsString(definition));
            ps.setString(6, null);
            ps.setInt(7, 1); ps.setInt(8, 0); ps.setBoolean(9, false);
            ps.setLong(10, now); ps.setLong(11, now);
            ps.executeUpdate();
            return new WorkflowDefinitionRecord(definition.id(), definition.name(),
                    definition.description(), definition, null, 1, 0, false, now, now);
        } catch (Exception e) {
            throw new IllegalStateException("创建工作流草稿失败", e);
        }
    }

    @Override
    public WorkflowDefinitionRecord saveDraft(GraphDefinition definition) {
        if (definition.kind() == GraphKind.SYSTEM) throw new IllegalArgumentException("系统图不可保存为草稿");
        try (Connection c = database.open()) {
            c.setAutoCommit(false);
            try {
                WorkflowDefinitionRecord old = lockDefinition(c, definition.id());
                int revision = old.draftRevision() + 1;
                long now = System.currentTimeMillis();
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE workflow_definitions SET name=?,description=?,draft_json=?,
                            draft_revision=?,updated_at=? WHERE workspace_id=? AND id=?
                        """)) {
                    ps.setString(1, definition.name()); ps.setString(2, definition.description());
                    ps.setString(3, json.writeValueAsString(definition)); ps.setInt(4, revision);
                    ps.setLong(5, now); ps.setString(6, workspaceId); ps.setString(7, definition.id());
                    if (ps.executeUpdate() != 1) throw new IllegalStateException("工作流已删除，不能保存草稿");
                }
                c.commit();
                return new WorkflowDefinitionRecord(definition.id(), definition.name(),
                        definition.description(), definition, old.published(), revision,
                        old.publishedVersion(), old.archived(), old.createdAt(), now);
            } catch (Exception failure) {
                rollback(c, failure);
                throw failure;
            }
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("保存工作流草稿失败", e); }
    }

    @Override
    public WorkflowDefinitionRecord publish(String id, NodeExecutorRegistry registry) {
        try (Connection c = database.open()) {
            c.setAutoCommit(false);
            try {
                WorkflowDefinitionRecord record = lockDefinition(c, id);
                GraphValidator.requireValid(record.draft(), registry);
                int version = record.publishedVersion() + 1;
                GraphDefinition published = new GraphDefinition(record.draft().schemaVersion(), record.draft().id(),
                        record.draft().name(), record.draft().description(), version, GraphKind.CUSTOM,
                        record.draft().startNodeId(), record.draft().nodes(), record.draft().edges(),
                        record.draft().maxSteps());
                long now = System.currentTimeMillis();
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE workflow_definitions SET published_json=?,published_version=?,updated_at=?
                        WHERE workspace_id=? AND id=?
                        """)) {
                    ps.setString(1, json.writeValueAsString(published)); ps.setInt(2, version);
                    ps.setLong(3, now); ps.setString(4, workspaceId); ps.setString(5, id);
                    if (ps.executeUpdate() != 1) throw new IllegalStateException("工作流已删除，不能发布");
                }
                c.commit();
                return new WorkflowDefinitionRecord(record.id(), record.name(), record.description(),
                        record.draft(), published, record.draftRevision(), version,
                        record.archived(), record.createdAt(), now);
            } catch (Exception failure) {
                rollback(c, failure);
                throw failure;
            }
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("发布工作流失败", e); }
    }

    @Override
    public WorkflowDefinitionRecord cloneFrom(GraphDefinition source, String newName) {
        String id = "wf-" + UUID.randomUUID();
        String name = newName == null || newName.isBlank() ? source.name() + " 副本" : newName;
        GraphDefinition clone = source.kind() == GraphKind.SYSTEM
                ? editableSystemTemplate(source, id, name)
                : new GraphDefinition(source.schemaVersion(), id, name,
                source.description(), 1, GraphKind.CUSTOM, source.startNodeId(),
                source.nodes(), source.edges(), source.maxSteps());
        return createDraft(clone);
    }

    /**
     * 内置 SYSTEM 节点依赖对应领域服务，不能泄漏到用户定义中。复制系统图时生成等价用途的
     * 公共 AGENT → OUTPUT 模板，使副本开箱即可校验、发布，并允许用户继续选择工具组和改提示词。
     */
    private GraphDefinition editableSystemTemplate(
            GraphDefinition source, String id, String name) {
        var nodes = json.getNodeFactory();
        NodeDefinition sourceStart = source.nodes().stream()
                .filter(node -> node.type() == NodeType.START).findFirst().orElse(null);
        NodeDefinition sourceEnd = source.nodes().stream()
                .filter(node -> node.type() == NodeType.END).findFirst().orElse(null);
        NodeDefinition sourceStage = source.nodes().stream()
                .filter(node -> node.type() == NodeType.SYSTEM).findFirst().orElse(null);

        double startX = sourceStart == null ? 80 : sourceStart.x();
        double startY = sourceStart == null ? 120 : sourceStart.y();
        double agentX = sourceStage == null ? 300 : sourceStage.x();
        double agentY = sourceStage == null ? 120 : sourceStage.y();
        double outputX = Math.max(agentX + 240, sourceEnd == null ? 540 : sourceEnd.x());
        double endX = outputX + 240;
        double endY = sourceEnd == null ? agentY : sourceEnd.y();

        var empty = nodes.objectNode();
        var agentConfig = nodes.objectNode();
        agentConfig.put("prompt", "你正在执行从内置系统工作流「" + source.name()
                + "」复制的本地流程。请围绕以下目标严谨完成用户请求：\n" + source.description());
        agentConfig.put("inputTemplate", "{{input}}");
        agentConfig.put("outputKey", "agent.output");
        agentConfig.put("maxIters", 8);
        agentConfig.putArray("toolGroups");
        var outputConfig = nodes.objectNode();
        outputConfig.put("template", "{{agent.output}}");
        outputConfig.put("outputKey", "output");

        NodeDefinition start = new NodeDefinition("start", NodeType.START, "start", "开始",
                empty, startX, startY, RetryPolicy.NONE, ResumeSafety.SAFE);
        NodeDefinition agent = new NodeDefinition("agent", NodeType.AGENT, "agent",
                sourceStage == null ? "智能体" : sourceStage.label(),
                agentConfig, agentX, agentY, RetryPolicy.NONE, ResumeSafety.SAFE);
        NodeDefinition output = new NodeDefinition("output", NodeType.OUTPUT, "output", "输出",
                outputConfig, outputX, agentY, RetryPolicy.NONE, ResumeSafety.SAFE);
        NodeDefinition end = new NodeDefinition("end", NodeType.END, "end", "结束",
                empty, endX, endY, RetryPolicy.NONE, ResumeSafety.SAFE);
        return new GraphDefinition(source.schemaVersion(), id, name, source.description(), 1,
                GraphKind.CUSTOM, "start", List.of(start, agent, output, end), List.of(
                new EdgeDefinition("start-agent", "start", "agent", EdgeKind.NORMAL, null, 0, false),
                new EdgeDefinition("agent-output", "agent", "output", EdgeKind.NORMAL, null, 0, false),
                new EdgeDefinition("output-end", "output", "end", EdgeKind.NORMAL, null, 0, false)),
                source.maxSteps());
    }

    @Override
    public boolean archive(String id, boolean archived) {
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE workflow_definitions SET archived=?,updated_at=? WHERE workspace_id=? AND id=?
                     """)) {
            ps.setBoolean(1, archived); ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, workspaceId); ps.setString(4, id);
            return ps.executeUpdate() == 1;
        } catch (Exception e) { throw new IllegalStateException("归档工作流失败", e); }
    }

    @Override
    public boolean delete(String id) {
        try (Connection c = database.open()) {
            c.setAutoCommit(false);
            try {
                // 新运行也锁此定义行，避免检查完成后又启动同一工作流。
                try (PreparedStatement definition = c.prepareStatement("""
                        SELECT id FROM workflow_definitions WHERE workspace_id=? AND id=? FOR UPDATE
                        """)) {
                    definition.setString(1, workspaceId); definition.setString(2, id);
                    try (ResultSet rs = definition.executeQuery()) {
                        if (!rs.next()) {
                            c.rollback();
                            return false;
                        }
                    }
                }
                try (PreparedStatement active = c.prepareStatement("""
                        SELECT 1 FROM workflow_runs WHERE workspace_id=? AND workflow_id=?
                        AND status NOT IN ('COMPLETED','FAILED','CANCELLED') LIMIT 1
                        """)) {
                    active.setString(1, workspaceId); active.setString(2, id);
                    try (ResultSet rs = active.executeQuery()) {
                        if (rs.next()) throw new IllegalStateException("工作流仍有未终结运行，请先取消后再删除");
                    }
                }
                try (PreparedStatement checkpoints = c.prepareStatement("""
                        DELETE FROM workflow_checkpoints WHERE workspace_id=? AND run_id IN
                        (SELECT id FROM workflow_runs WHERE workspace_id=? AND workflow_id=?)
                        """)) {
                    checkpoints.setString(1, workspaceId);
                    checkpoints.setString(2, workspaceId); checkpoints.setString(3, id);
                    checkpoints.executeUpdate();
                }
                for (String table : List.of("workflow_runs", "workflow_threads")) {
                    try (PreparedStatement rows = c.prepareStatement(
                            "DELETE FROM " + table + " WHERE workspace_id=? AND workflow_id=?")) {
                        rows.setString(1, workspaceId); rows.setString(2, id);
                        rows.executeUpdate();
                    }
                }
                boolean deleted;
                try (PreparedStatement definition = c.prepareStatement(
                        "DELETE FROM workflow_definitions WHERE workspace_id=? AND id=?")) {
                    definition.setString(1, workspaceId); definition.setString(2, id);
                    deleted = definition.executeUpdate() == 1;
                }
                c.commit();
                return deleted;
            } catch (Exception failure) {
                rollback(c, failure);
                throw failure;
            }
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("删除工作流失败", e); }
    }

    @Override
    public List<ValidationIssue> validate(GraphDefinition definition, NodeExecutorRegistry registry) {
        return GraphValidator.validate(definition, registry);
    }

    private WorkflowDefinitionRecord lockDefinition(Connection connection, String id) throws Exception {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM workflow_definitions WHERE workspace_id=? AND id=? FOR UPDATE
                """)) {
            query.setString(1, workspaceId); query.setString(2, id);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("工作流不存在或已删除: " + id);
                return read(row);
            }
        }
    }

    private void rollback(Connection connection, Exception failure) {
        try { connection.rollback(); }
        catch (Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
    }

    private WorkflowDefinitionRecord read(ResultSet rs) throws Exception {
        String published = rs.getString("published_json");
        return new WorkflowDefinitionRecord(rs.getString("id"), rs.getString("name"),
                rs.getString("description"),
                json.readValue(rs.getString("draft_json"), GraphDefinition.class),
                published == null ? null : json.readValue(published, GraphDefinition.class),
                rs.getInt("draft_revision"), rs.getInt("published_version"), rs.getBoolean("archived"),
                rs.getLong("created_at"), rs.getLong("updated_at"));
    }
}
