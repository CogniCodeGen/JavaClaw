package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.javaclaw.api.AgentRoleFilePreview;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;

/** Role 文件预览和导入记录；提交记录与 Role revision 必须在同一事务内写入。 */
final class AgentRoleImportRepository {
    private final CanonicalJson json;

    AgentRoleImportRepository(CanonicalJson json) {
        this.json = json;
    }

    void insert(Connection connection, AgentRoleFilePreview preview, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.AGENT_ROLE_IMPORT (ID, CONTENT_DIGEST, PREVIEW_PAYLOAD, CREATED_AT)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, preview.previewId());
            statement.setString(2, preview.contentDigest());
            statement.setString(3, json.encode(preview).json());
            statement.setObject(4, now.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    StoredImport require(Connection connection, String id, boolean lock) throws SQLException {
        String sql = "SELECT PREVIEW_PAYLOAD, COMMITTED_ROLE_ID, COMMITTED_ROLE_REVISION "
                + "FROM CORE.AGENT_ROLE_IMPORT WHERE ID = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw PersistenceException.invalidRequest("Role 导入预览不存在");
                }
                String roleId = result.getString("COMMITTED_ROLE_ID");
                Optional<AgentRoleRef> committed = roleId == null
                        ? Optional.empty()
                        : Optional.of(new AgentRoleRef(roleId, result.getLong("COMMITTED_ROLE_REVISION")));
                return new StoredImport(
                        json.decode(
                                new CanonicalPayload(result.getString("PREVIEW_PAYLOAD")), AgentRoleFilePreview.class),
                        committed);
            }
        }
    }

    void commit(Connection connection, String id, AgentRoleRef role, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.AGENT_ROLE_IMPORT
                SET COMMITTED_ROLE_ID = ?, COMMITTED_ROLE_REVISION = ?, COMMITTED_AT = ?
                WHERE ID = ? AND COMMITTED_ROLE_ID IS NULL
                """)) {
            statement.setString(1, role.id());
            statement.setLong(2, role.revision());
            statement.setObject(3, now.atOffset(ZoneOffset.UTC));
            statement.setString(4, id);
            if (statement.executeUpdate() != 1) {
                throw PersistenceException.revisionConflict("Role 导入预览已提交");
            }
        }
    }

    record StoredImport(AgentRoleFilePreview preview, Optional<AgentRoleRef> committed) {}
}
