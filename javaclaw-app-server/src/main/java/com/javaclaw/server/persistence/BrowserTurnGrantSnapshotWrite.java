package com.javaclaw.server.persistence;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BrowserGrantContracts.GrantRef;
import com.javaclaw.builtin.contracts.BrowserGrantContracts.Snapshot;
import com.javaclaw.protocol.CanonicalJson;

/**
 * 在 Turn 创建事务内固定浏览器授权版本；调用者持有与授权确认、撤销相同的 Thread 行锁。
 *
 * <p>授权顺序来自事务提交关系，不依赖可静止或回拨的墙钟。恢复 turn/start 幂等回执不会重新执行本写入。
 */
final class BrowserTurnGrantSnapshotWrite {
    private BrowserTurnGrantSnapshotWrite() {}

    static void insert(Connection connection, AgentTurn turn, WorkspaceId workspace, CanonicalJson json)
            throws SQLException {
        Map<URI, GrantRef> grants = current(connection, turn, workspace);
        Snapshot snapshot = new Snapshot(
                UUID.randomUUID().toString(), workspace, turn.threadId(), turn.id(), grants, turn.createdAt());
        try (var statement = connection.prepareStatement("""
                INSERT INTO CORE.BROWSER_TURN_GRANT_SNAPSHOT(TURN_ID,SNAPSHOT_ID,WORKSPACE_ID,THREAD_ID,PAYLOAD)
                VALUES(?,?,?,?,?)
                """)) {
            statement.setString(1, turn.id().toString());
            statement.setString(2, snapshot.snapshotId());
            statement.setString(3, workspace.toString());
            statement.setString(4, turn.threadId().toString());
            statement.setString(5, json.encode(snapshot).json());
            statement.executeUpdate();
        }
    }

    private static Map<URI, GrantRef> current(Connection connection, AgentTurn turn, WorkspaceId workspace)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT G.ORIGIN,G.ID,G.REVISION FROM CORE.BROWSER_ORIGIN_GRANT G
                JOIN (SELECT ID,MAX(REVISION) REVISION FROM CORE.BROWSER_ORIGIN_GRANT
                      WHERE THREAD_ID=? GROUP BY ID) L ON G.ID=L.ID AND G.REVISION=L.REVISION
                WHERE G.WORKSPACE_ID=? AND G.THREAD_ID=? AND G.STATE='ACTIVE'
                ORDER BY G.CREATED_AT DESC,G.ID
                """)) {
            statement.setString(1, turn.threadId().toString());
            statement.setString(2, workspace.toString());
            statement.setString(3, turn.threadId().toString());
            try (var rows = statement.executeQuery()) {
                Map<URI, GrantRef> grants = new LinkedHashMap<>();
                // 仅兼容旧版本超限数据：按固定顺序保留至多 128 来源，不阻断普通 Turn，遗漏来源仍拒绝网络。
                while (grants.size() < 128 && rows.next()) {
                    grants.putIfAbsent(URI.create(rows.getString(1)), new GrantRef(rows.getString(2), rows.getLong(3)));
                }
                return grants;
            }
        }
    }
}
