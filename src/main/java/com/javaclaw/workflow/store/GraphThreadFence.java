package com.javaclaw.workflow.store;

import java.sql.Connection;
import java.sql.SQLException;

/** Serializes legacy graph writes and deletion, including graphs created before Agent threads. */
final class GraphThreadFence {
    private GraphThreadFence() { }
    static void lock(Connection connection, String workspace, String thread) throws SQLException {
        try (var merge = connection.prepareStatement("""
                MERGE INTO workflow_thread_lifecycle AS target
                USING (VALUES (?,?)) AS source(workspace_id,thread_id)
                ON target.workspace_id=source.workspace_id AND target.thread_id=source.thread_id
                WHEN NOT MATCHED THEN INSERT(workspace_id,thread_id,deleted)
                    VALUES(source.workspace_id,source.thread_id,FALSE)
                """)) {
            merge.setString(1, workspace); merge.setString(2, thread);
            try { merge.executeUpdate(); }
            catch (SQLException race) { if (!"23505".equals(race.getSQLState())) throw race; }
        }
        try (var query = connection.prepareStatement("SELECT deleted FROM workflow_thread_lifecycle "
                + "WHERE workspace_id=? AND thread_id=? FOR UPDATE")) {
            query.setString(1, workspace); query.setString(2, thread);
            try (var row = query.executeQuery()) { if (!row.next()) throw new IllegalStateException("graph fence missing"); }
        }
    }
    static void requireLive(Connection connection, String workspace, String thread) throws SQLException {
        lock(connection, workspace, thread);
        try (var query = connection.prepareStatement("SELECT status FROM agent_threads "
                + "WHERE workspace_id=? AND user_id='local-user' AND thread_id=? FOR UPDATE")) {
            query.setString(1, workspace); query.setString(2, thread);
            try (var row = query.executeQuery()) {
                if (row.next() && deletedStatus(row.getString(1))) throw new WorkflowRunMissingException("所属会话已删除");
            }
        }
        if (deleted(connection, workspace, thread)) throw new WorkflowRunMissingException("所属会话已删除");
    }
    static boolean deleted(Connection connection, String workspace, String thread) throws SQLException {
        try (var query = connection.prepareStatement("SELECT deleted FROM workflow_thread_lifecycle WHERE workspace_id=? AND thread_id=?")) {
            query.setString(1, workspace); query.setString(2, thread);
            try (var row = query.executeQuery()) { if (row.next() && row.getBoolean(1)) return true; }
        }
        try (var query = connection.prepareStatement("SELECT status FROM agent_threads WHERE workspace_id=? AND user_id='local-user' AND thread_id=?")) {
            query.setString(1, workspace); query.setString(2, thread);
            try (var row = query.executeQuery()) { return row.next() && deletedStatus(row.getString(1)); }
        }
    }
    static String readablePredicate(String table) {
        return "NOT EXISTS (SELECT 1 FROM workflow_thread_lifecycle fence WHERE fence.workspace_id=" + table
                + ".workspace_id AND fence.thread_id=" + table + ".thread_id AND fence.deleted=TRUE) "
                + "AND NOT EXISTS (SELECT 1 FROM agent_threads owner WHERE owner.workspace_id=" + table
                + ".workspace_id AND owner.thread_id=" + table + ".thread_id AND owner.user_id='local-user' "
                + "AND owner.status IN ('DELETING','DELETED'))";
    }
    private static boolean deletedStatus(String status) { return status.equals("DELETING") || status.equals("DELETED"); }
}
