package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.javaclaw.api.TurnId;
import com.javaclaw.api.UnattendedExecutionScope;
import com.javaclaw.api.WorkspaceId;

/** Schedule 无人值守来源与 Turn 的原子绑定 SQL。 */
final class UnattendedTurnScopeRepository {
    void insert(Connection connection, TurnId turnId, UnattendedExecutionScope scope, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.TURN_UNATTENDED_EXECUTION_SCOPE (
                    TURN_ID, WORKSPACE_ID, SCHEDULE_ID, SCHEDULE_REVISION, OCCURRENCE_ID, CREATED_AT
                )
                SELECT T.ID, H.WORKSPACE_ID, ?, ?, ?, ?
                FROM CORE.AGENT_TURN T
                JOIN CORE.AGENT_THREAD H ON H.ID = T.THREAD_ID
                WHERE T.ID = ? AND H.WORKSPACE_ID = ?
                """)) {
            statement.setString(1, scope.scheduleId());
            statement.setLong(2, scope.scheduleRevision());
            statement.setString(3, scope.occurrenceId());
            statement.setObject(4, now.atOffset(ZoneOffset.UTC));
            statement.setString(5, turnId.toString());
            statement.setString(6, scope.workspaceId().toString());
            if (statement.executeUpdate() != 1) {
                throw PersistenceException.invalidRequest("无人值守来源与 Turn Workspace 不一致");
            }
        }
    }

    Optional<UnattendedExecutionScope> find(Connection connection, TurnId turnId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT WORKSPACE_ID, SCHEDULE_ID, SCHEDULE_REVISION, OCCURRENCE_ID
                FROM CORE.TURN_UNATTENDED_EXECUTION_SCOPE WHERE TURN_ID = ?
                """)) {
            statement.setString(1, turnId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new UnattendedExecutionScope(
                        WorkspaceId.parse(result.getString("WORKSPACE_ID")),
                        result.getString("SCHEDULE_ID"),
                        result.getLong("SCHEDULE_REVISION"),
                        result.getString("OCCURRENCE_ID")));
            }
        }
    }
}
