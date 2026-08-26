package com.javaclaw.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceManagerDeletionTest {

    @TempDir
    Path tempDirectory;

    @Test
    void 删除工作区会清理全部结构化数据和文件分桶() throws Exception {
        String previous = System.getProperty(DataRoot.DATA_DIR_PROPERTY);
        System.setProperty(DataRoot.DATA_DIR_PROPERTY,
                tempDirectory.resolve("data").toString());
        try {
            try (var root = ApplicationContexts.createRoot(DataRoot.resolve())) {
                WorkspaceManager manager = root.getBean(WorkspaceManager.class);
                DatabaseAccess database = root.getBean(DatabaseAccess.class);
                Path dataRoot = root.getBean(DataRoot.class).path();
                Workspace workspace = manager.createWorkspace("待删除工作区");
                String workspaceId = workspace.getId();
                String retainedWorkspaceId = manager.getCurrentWorkspaceId();

                seedWorkspaceRows(database, workspaceId, retainedWorkspaceId);
                List<Path> assetDirs = workspaceAssetDirs(dataRoot, workspaceId);
                for (Path dir : assetDirs) {
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve("marker.txt"), "workspace-private-data");
                }

                assertTrue(manager.deleteWorkspace(workspaceId));
                assertNull(manager.findById(workspaceId));
                for (Path dir : assetDirs) {
                    assertFalse(Files.exists(dir), "工作区文件资产未清理: " + dir);
                }
                for (String table : List.of(
                        "workflow_checkpoints", "workflow_runs", "workflow_threads",
                        "workflow_definitions", "app_properties",
                        "conversation_context_summary", "token_usage_projection_receipts",
                        "agent_runs", "agent_definition_versions", "agent_definitions",
                        "run_profile_versions", "run_profiles")) {
                    assertEquals(0, countByWorkspace(database, table, workspaceId),
                            "工作区数据库行未清理: " + table);
                }
                for (String table : List.of(
                        "agent_extension_state", "agent_run_outbox", "agent_run_events")) {
                    assertEquals(0, countByRun(database, table, "agent-run"));
                    assertEquals(0, countByRun(database, table, "shared-agent-run"));
                }
                assertEquals(0, countById(
                        database, "compiled_execution_plans", "plan_id", "exclusive-plan"));
                assertEquals(1, countById(
                        database, "compiled_execution_plans", "plan_id", "shared-plan"));
                assertEquals(1, countByRun(database, "agent_runs", "retained-agent-run"));
                assertEquals(0, countWorkspaceIndex(database, workspaceId));
            }
        } finally {
            if (previous == null) {
                System.clearProperty(DataRoot.DATA_DIR_PROPERTY);
            } else {
                System.setProperty(DataRoot.DATA_DIR_PROPERTY, previous);
            }
        }
    }

    @Test
    void 非终态AgentRun会阻止工作区删除() throws Exception {
        String previous = System.getProperty(DataRoot.DATA_DIR_PROPERTY);
        System.setProperty(DataRoot.DATA_DIR_PROPERTY,
                tempDirectory.resolve("active-run-data").toString());
        try {
            try (var root = ApplicationContexts.createRoot(DataRoot.resolve())) {
                WorkspaceManager manager = root.getBean(WorkspaceManager.class);
                DatabaseAccess database = root.getBean(DatabaseAccess.class);
                Workspace workspace = manager.createWorkspace("运行中工作区");
                seedAgentRun(database, workspace.getId(), "active-run", "active-plan", "PAUSED");

                assertFalse(manager.deleteWorkspace(workspace.getId()));
                assertTrue(manager.findById(workspace.getId()) != null);
                assertEquals(1, countByWorkspace(
                        database, "agent_runs", workspace.getId()));
                assertEquals(1, countById(
                        database, "compiled_execution_plans", "plan_id", "active-plan"));
            }
        } finally {
            if (previous == null) {
                System.clearProperty(DataRoot.DATA_DIR_PROPERTY);
            } else {
                System.setProperty(DataRoot.DATA_DIR_PROPERTY, previous);
            }
        }
    }

    private static void seedWorkspaceRows(
            DatabaseAccess database, String workspaceId, String retainedWorkspaceId)
            throws Exception {
        try (Connection c = database.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO app_properties(
                        workspace_id, namespace, prop_key, prop_value)
                    VALUES (?, 'test', 'key', 'value')
                    """)) {
                ps.setString(1, workspaceId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO conversation_context_summary(
                        workspace_id, session_id, summarized_messages, cursor_message_id,
                        source_hash, summary_json, rendered_summary)
                    VALUES (?, 'chat-session', 1, 'message-1', 'hash', '{}', 'summary')
                    """)) {
                ps.setString(1, workspaceId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO workflow_definitions(
                        workspace_id, id, name, description, draft_json, published_json,
                        draft_revision, published_version, archived, created_at, updated_at)
                    VALUES (?, 'wf', 'workflow', NULL, '{}', NULL, 1, 0, FALSE, 1, 1)
                    """)) {
                ps.setString(1, workspaceId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO workflow_threads(
                        workspace_id, workflow_id, thread_id, state_json, updated_at)
                    VALUES (?, 'wf', 'thread', '{}', 1)
                    """)) {
                ps.setString(1, workspaceId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO workflow_runs(
                        workspace_id, id, workflow_id, workflow_version, thread_id,
                        definition_json, state_json, status, current_node_id, next_node_id,
                        step_count, output_text, error_text, interrupt_json, created_at, updated_at)
                    VALUES (?, 'run', 'wf', 1, 'thread', '{}', '{}', 'PAUSED',
                            NULL, NULL, 0, NULL, NULL, NULL, 1, 1)
                    """)) {
                ps.setString(1, workspaceId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO workflow_checkpoints(
                        workspace_id, run_id, seq, node_id, phase, state_json, created_at)
                    VALUES (?, 'run', 1, NULL, 'BEFORE_NODE', '{}', 1)
                    """)) {
                ps.setString(1, workspaceId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO agent_definitions(
                        workspace_id, id, name, builtin, archived, draft_json,
                        draft_revision, published_version, created_at, updated_at)
                    VALUES (?, 'agent', 'agent', FALSE, FALSE, '{}', 1, 1, 1, 1)
                    """)) {
                ps.setString(1, workspaceId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO agent_definition_versions(
                        workspace_id, definition_id, version, definition_json,
                        checksum, published_at)
                    VALUES (?, 'agent', 1, '{}', 'checksum', 1)
                    """)) {
                ps.setString(1, workspaceId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO run_profiles(
                        workspace_id, id, name, builtin, archived, draft_json,
                        draft_revision, published_version, created_at, updated_at)
                    VALUES (?, 'profile', 'profile', FALSE, FALSE, '{}', 1, 1, 1, 1)
                    """)) {
                ps.setString(1, workspaceId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO run_profile_versions(
                        workspace_id, profile_id, version, profile_json,
                        checksum, published_at)
                    VALUES (?, 'profile', 1, '{}', 'checksum', 1)
                    """)) {
                ps.setString(1, workspaceId);
                ps.executeUpdate();
            }
        }
        seedAgentRun(database, workspaceId, "agent-run", "exclusive-plan", "COMPLETED");
        seedAgentRun(database, workspaceId, "shared-agent-run", "shared-plan", "FAILED");
        seedAgentRun(
                database, retainedWorkspaceId, "retained-agent-run", "shared-plan", "COMPLETED");
        seedAgentChildren(database, workspaceId, "agent-run");
        seedAgentChildren(database, workspaceId, "shared-agent-run");
    }

    private static void seedAgentRun(
            DatabaseAccess database, String workspaceId, String runId,
            String planId, String state) throws Exception {
        try (Connection c = database.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    MERGE INTO compiled_execution_plans(
                        plan_id, extension_generation, plan_json, checksum, created_at)
                    KEY(plan_id) VALUES (?, 1, '{}', 'checksum', 1)
                    """)) {
                ps.setString(1, planId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO agent_runs(
                        run_id, workspace_id, user_id, session_id, idempotency_key,
                        request_json, execution_plan_id, state, last_sequence,
                        output_json, error_text, version, created_at, updated_at)
                    VALUES (?, ?, 'user', 'session', NULL, '{}', ?, ?, 1,
                            NULL, NULL, 1, 1, 1)
                    """)) {
                ps.setString(1, runId);
                ps.setString(2, workspaceId);
                ps.setString(3, planId);
                ps.setString(4, state);
                ps.executeUpdate();
            }
        }
    }

    private static void seedAgentChildren(
            DatabaseAccess database, String workspaceId, String runId) throws Exception {
        try (Connection c = database.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO agent_run_events(
                        run_id, event_sequence, timestamp_ms, type, schema_version,
                        producer, correlation_id, causation_id, payload_json)
                    VALUES (?, 1, 1, 'core.run.created', 1, 'test', NULL, NULL, '{}')
                    """)) {
                ps.setString(1, runId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO agent_run_outbox(
                        run_id, event_sequence, event_type, envelope_json,
                        created_at, published_at)
                    VALUES (?, 1, 'core.run.created', '{}', 1, NULL)
                    """)) {
                ps.setString(1, runId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO agent_extension_state(
                        run_id, extension_id, state_key, schema_version,
                        state_json, updated_at)
                    VALUES (?, 'framework.skills', 'readable-catalog', 1, '{}', 1)
                    """)) {
                ps.setString(1, runId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO token_usage_projection_receipts(
                        workspace_id, model_call_id, run_id, event_timestamp_ms,
                        usage_date, projected_at)
                    VALUES (?, ?, ?, 1, '2026-08-25', 1)
                    """)) {
                ps.setString(1, workspaceId);
                ps.setString(2, "call-" + runId);
                ps.setString(3, runId);
                ps.executeUpdate();
            }
        }
    }

    private static List<Path> workspaceAssetDirs(Path dataRoot, String workspaceId) {
        return List.of(
                dataRoot.resolve("memory-stores").resolve(workspaceId),
                dataRoot.resolve("knowledge").resolve("workspaces").resolve(workspaceId),
                dataRoot.resolve("screenshots").resolve(workspaceId),
                dataRoot.resolve("workspace-data").resolve(workspaceId),
                dataRoot.resolve("browser").resolve(workspaceId),
                dataRoot.resolve("logs").resolve(workspaceId)
        );
    }

    private static int countByWorkspace(
            DatabaseAccess database, String table, String workspaceId) throws Exception {
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM " + table + " WHERE workspace_id = ?")) {
            ps.setString(1, workspaceId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int countByRun(
            DatabaseAccess database, String table, String runId) throws Exception {
        return countById(database, table, "run_id", runId);
    }

    private static int countById(
            DatabaseAccess database, String table, String column, String value) throws Exception {
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?")) {
            ps.setString(1, value);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int countWorkspaceIndex(DatabaseAccess database, String workspaceId)
            throws Exception {
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM workspaces WHERE id = ?")) {
            ps.setString(1, workspaceId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
