package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;

import com.javaclaw.api.TurnId;

/** 成功 Turn 的通用提交顺序索引；必须与成功终态和最终 Item 在同一事务写入。 */
public final class ConversationCompletionIndex {
    private ConversationCompletionIndex() {}

    /**
     * 固定成功 Turn 的 Item 上界并分配 Workspace 提交序号。
     *
     * <p>先锁独立索引 head，再读取序号；持锁至终态事务提交，避免提前分配的序号在游标推进后迟提交。 重复调用按 Turn ID 幂等，不改变已经冻结的证据边界。
     *
     * @param connection 当前终态事务
     * @param turnId 已变为 COMPLETED 的 Turn
     * @param completedAt 成功完成时间
     * @throws SQLException 事务存储失败
     */
    public static void record(Connection connection, TurnId turnId, Instant completedAt) throws SQLException {
        String workspace;
        String thread;
        try (var query = connection.prepareStatement("""
                SELECT T.WORKSPACE_ID, T.ID FROM CORE.AGENT_THREAD T
                JOIN CORE.AGENT_TURN R ON R.THREAD_ID = T.ID
                WHERE R.ID = ? AND R.STATUS = 'COMPLETED'
                """)) {
            query.setString(1, turnId.toString());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    return;
                }
                workspace = rows.getString(1);
                thread = rows.getString(2);
            }
        }
        ensureHead(connection, workspace);
        try (var lock = connection.prepareStatement(
                "SELECT COMMITTED_SEQUENCE FROM CORE.CONVERSATION_COMPLETION_HEAD WHERE WORKSPACE_ID = ? FOR UPDATE")) {
            lock.setString(1, workspace);
            try (var rows = lock.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("completion head does not exist");
                }
            }
        }
        if (alreadyRecorded(connection, turnId)) {
            return;
        }
        long next = 1;
        try (var query = connection.prepareStatement(
                "SELECT COMMITTED_SEQUENCE FROM CORE.CONVERSATION_COMPLETION_HEAD WHERE WORKSPACE_ID = ?")) {
            query.setString(1, workspace);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    next = Math.addExact(rows.getLong(1), 1);
                }
            }
        }
        try (var write = connection.prepareStatement("""
                INSERT INTO CORE.CONVERSATION_COMPLETION
                (TURN_ID, WORKSPACE_ID, THREAD_ID, COMPLETION_SEQUENCE, LAST_ITEM_SEQUENCE, COMPLETED_AT)
                SELECT ?, ?, ?, ?, COALESCE(MAX(SEQUENCE), 0), ? FROM CORE.ITEM WHERE TURN_ID = ?
                """)) {
            write.setString(1, turnId.toString());
            write.setString(2, workspace);
            write.setString(3, thread);
            write.setLong(4, next);
            write.setObject(5, completedAt.atOffset(ZoneOffset.UTC));
            write.setString(6, turnId.toString());
            write.executeUpdate();
        }
        try (var write = connection.prepareStatement(
                "MERGE INTO CORE.CONVERSATION_COMPLETION_HEAD (WORKSPACE_ID, COMMITTED_SEQUENCE) KEY (WORKSPACE_ID) VALUES (?, ?)")) {
            write.setString(1, workspace);
            write.setLong(2, next);
            write.executeUpdate();
        }
    }

    private static boolean alreadyRecorded(Connection connection, TurnId turnId) throws SQLException {
        try (var query =
                connection.prepareStatement("SELECT TURN_ID FROM CORE.CONVERSATION_COMPLETION WHERE TURN_ID = ?")) {
            query.setString(1, turnId.toString());
            try (var rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static void ensureHead(Connection connection, String workspace) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT WORKSPACE_ID FROM CORE.CONVERSATION_COMPLETION_HEAD WHERE WORKSPACE_ID = ?")) {
            query.setString(1, workspace);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    return;
                }
            }
        }
        // 只有首次创建 head 使用独立初始化锁，不锁 Workspace 或反向访问 Thread。
        try (var lock = connection.prepareStatement(
                        "SELECT ID FROM CORE.CONVERSATION_COMPLETION_INIT WHERE ID = 1 FOR UPDATE");
                var rows = lock.executeQuery()) {
            rows.next();
        }
        try (var write = connection.prepareStatement(
                "INSERT INTO CORE.CONVERSATION_COMPLETION_HEAD (WORKSPACE_ID, COMMITTED_SEQUENCE) SELECT ?, 0 WHERE NOT EXISTS (SELECT 1 FROM CORE.CONVERSATION_COMPLETION_HEAD WHERE WORKSPACE_ID = ?)")) {
            write.setString(1, workspace);
            write.setString(2, workspace);
            write.executeUpdate();
        }
    }

    /**
     * 标记机器整理等不应再次作为原始对话证据的 Turn；与 Turn 创建原子提交。
     *
     * @param connection 当前创建事务
     * @param turnId 新建 Turn
     * @throws SQLException 写入失败
     */
    public static void exclude(Connection connection, TurnId turnId) throws SQLException {
        try (var write = connection.prepareStatement(
                "MERGE INTO CORE.CONVERSATION_EVIDENCE_EXCLUSION (TURN_ID) KEY (TURN_ID) VALUES (?)")) {
            write.setString(1, turnId.toString());
            write.executeUpdate();
        }
    }
}
