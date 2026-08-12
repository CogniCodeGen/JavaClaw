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
    void 删除工作区会清理工作流数据索引行和全部文件分桶() throws Exception {
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

                seedWorkspaceRows(database, workspaceId);
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
                        "workflow_definitions", "app_properties")) {
                    assertEquals(0, countByWorkspace(database, table, workspaceId),
                            "工作区数据库行未清理: " + table);
                }
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

    private static void seedWorkspaceRows(DatabaseAccess database, String workspaceId)
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
