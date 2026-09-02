package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.ProfileBinding;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;

/** Workspace 与 Thread 默认 Profile 绑定 SQL。 */
final class ProfileBindingRepository {
    Optional<ProfileBinding> find(
            Connection connection, WorkspaceId workspaceId, Optional<ThreadId> threadId, boolean lock)
            throws SQLException {
        String sql = "SELECT WORKSPACE_ID, THREAD_ID, PROFILE_ID, PROFILE_REVISION, REVISION, UPDATED_AT "
                + "FROM CORE.PROFILE_BINDING WHERE WORKSPACE_ID = ? AND SCOPE_KEY = ?"
                + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, workspaceId.toString());
            statement.setString(2, scopeKey(threadId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    void insert(Connection connection, ProfileBinding binding) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.PROFILE_BINDING (
                    WORKSPACE_ID, THREAD_ID, SCOPE_KEY, PROFILE_ID, PROFILE_REVISION, REVISION, UPDATED_AT
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            bind(statement, binding);
            statement.executeUpdate();
        }
    }

    void update(Connection connection, ProfileBinding binding, long expectedRevision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.PROFILE_BINDING SET
                    PROFILE_ID = ?, PROFILE_REVISION = ?, REVISION = ?, UPDATED_AT = ?
                WHERE WORKSPACE_ID = ? AND SCOPE_KEY = ? AND REVISION = ?
                """)) {
            statement.setString(1, binding.profile().id());
            statement.setLong(2, binding.profile().revision());
            statement.setLong(3, binding.revision());
            statement.setObject(4, binding.updatedAt().atOffset(ZoneOffset.UTC));
            statement.setString(5, binding.workspaceId().toString());
            statement.setString(6, scopeKey(binding.threadId()));
            statement.setLong(7, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw PersistenceException.revisionConflict("Profile binding revision 已改变");
            }
        }
    }

    private static void bind(PreparedStatement statement, ProfileBinding binding) throws SQLException {
        statement.setString(1, binding.workspaceId().toString());
        statement.setString(2, binding.threadId().map(ThreadId::toString).orElse(null));
        statement.setString(3, scopeKey(binding.threadId()));
        statement.setString(4, binding.profile().id());
        statement.setLong(5, binding.profile().revision());
        statement.setLong(6, binding.revision());
        statement.setObject(7, binding.updatedAt().atOffset(ZoneOffset.UTC));
    }

    private static ProfileBinding map(ResultSet result) throws SQLException {
        String threadId = result.getString("THREAD_ID");
        return new ProfileBinding(
                WorkspaceId.parse(result.getString("WORKSPACE_ID")),
                Optional.ofNullable(threadId).map(ThreadId::parse),
                new AgentProfileRef(result.getString("PROFILE_ID"), result.getLong("PROFILE_REVISION")),
                result.getLong("REVISION"),
                result.getObject("UPDATED_AT", OffsetDateTime.class).toInstant());
    }

    private static String scopeKey(Optional<ThreadId> threadId) {
        return threadId.map(value -> "thread:" + value).orElse("workspace");
    }
}
