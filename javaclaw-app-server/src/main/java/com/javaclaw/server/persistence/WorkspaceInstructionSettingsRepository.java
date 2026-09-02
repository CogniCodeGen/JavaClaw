package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceInstructionSettings;

/** Workspace 项目约定设置的行映射与乐观锁更新。 */
final class WorkspaceInstructionSettingsRepository {
    WorkspaceInstructionSettings insert(Connection connection, WorkspaceId workspaceId, Instant now)
            throws SQLException {
        WorkspaceInstructionSettings settings = new WorkspaceInstructionSettings(workspaceId, Optional.empty(), 1, now);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.WORKSPACE_INSTRUCTION_SETTING
                    (WORKSPACE_ID, FALLBACK_BASENAME, REVISION, UPDATED_AT)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, workspaceId.toString());
            statement.setString(2, null);
            statement.setLong(3, settings.revision());
            statement.setObject(4, at(now));
            statement.executeUpdate();
        }
        return settings;
    }

    Optional<WorkspaceInstructionSettings> find(Connection connection, WorkspaceId workspaceId, boolean lock)
            throws SQLException {
        String suffix = lock ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT WORKSPACE_ID, FALLBACK_BASENAME, REVISION, UPDATED_AT
                FROM CORE.WORKSPACE_INSTRUCTION_SETTING
                WHERE WORKSPACE_ID = ?
                """ + suffix)) {
            statement.setString(1, workspaceId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    WorkspaceInstructionSettings update(
            Connection connection, WorkspaceInstructionSettings current, Optional<String> fallbackBasename, Instant now)
            throws SQLException {
        WorkspaceInstructionSettings next = new WorkspaceInstructionSettings(
                current.workspaceId(), fallbackBasename, Math.addExact(current.revision(), 1), now);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.WORKSPACE_INSTRUCTION_SETTING
                SET FALLBACK_BASENAME = ?, REVISION = ?, UPDATED_AT = ?
                WHERE WORKSPACE_ID = ? AND REVISION = ?
                """)) {
            statement.setString(1, next.fallbackBasename().orElse(null));
            statement.setLong(2, next.revision());
            statement.setObject(3, at(now));
            statement.setString(4, next.workspaceId().toString());
            statement.setLong(5, current.revision());
            if (statement.executeUpdate() != 1) {
                throw PersistenceException.revisionConflict("项目约定设置 revision 已改变");
            }
        }
        return next;
    }

    private static WorkspaceInstructionSettings map(ResultSet result) throws SQLException {
        return new WorkspaceInstructionSettings(
                WorkspaceId.parse(result.getString("WORKSPACE_ID")),
                Optional.ofNullable(result.getString("FALLBACK_BASENAME")),
                result.getLong("REVISION"),
                result.getObject("UPDATED_AT", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
