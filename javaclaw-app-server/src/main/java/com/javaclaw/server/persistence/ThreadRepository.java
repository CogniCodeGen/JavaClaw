package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ThreadStatus;
import com.javaclaw.api.WorkspaceId;

/** Thread 行映射与 SQL。 */
final class ThreadRepository {
    ConversationThread insert(
            Connection connection,
            WorkspaceId workspaceId,
            Optional<ThreadId> parentId,
            ThreadExecutionIntent executionIntent,
            String title,
            Instant now)
            throws SQLException {
        ConversationThread thread = new ConversationThread(
                ThreadId.random(), workspaceId, parentId, executionIntent, title, ThreadStatus.ACTIVE, 1, now, now);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.AGENT_THREAD (
                    ID, WORKSPACE_ID, PARENT_THREAD_ID, EXECUTION_INTENT, TITLE, STATUS, REVISION,
                    NEXT_SEQUENCE, CREATED_AT, UPDATED_AT
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                """)) {
            statement.setString(1, thread.id().toString());
            statement.setString(2, workspaceId.toString());
            statement.setString(3, parentId.map(ThreadId::toString).orElse(null));
            statement.setString(4, thread.executionIntent().name());
            statement.setString(5, thread.title());
            statement.setString(6, thread.status().name());
            statement.setLong(7, thread.revision());
            statement.setObject(8, at(now));
            statement.setObject(9, at(now));
            statement.executeUpdate();
        }
        return thread;
    }

    Optional<ConversationThread> find(Connection connection, ThreadId id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, WORKSPACE_ID, PARENT_THREAD_ID, EXECUTION_INTENT, TITLE, STATUS, REVISION,
                       CREATED_AT, UPDATED_AT
                FROM CORE.AGENT_THREAD WHERE ID = ?
                """)) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    List<ConversationThread> listByWorkspace(Connection connection, WorkspaceId workspaceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, WORKSPACE_ID, PARENT_THREAD_ID, EXECUTION_INTENT, TITLE, STATUS, REVISION,
                       CREATED_AT, UPDATED_AT
                FROM CORE.AGENT_THREAD WHERE WORKSPACE_ID = ? ORDER BY UPDATED_AT DESC, ID
                """)) {
            statement.setString(1, workspaceId.toString());
            try (ResultSet result = statement.executeQuery()) {
                List<ConversationThread> values = new ArrayList<>();
                while (result.next()) {
                    values.add(map(result));
                }
                return List.copyOf(values);
            }
        }
    }

    List<ConversationThread> listByExecutionIntent(Connection connection, ThreadExecutionIntent executionIntent)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, WORKSPACE_ID, PARENT_THREAD_ID, EXECUTION_INTENT, TITLE, STATUS, REVISION,
                       CREATED_AT, UPDATED_AT
                FROM CORE.AGENT_THREAD WHERE EXECUTION_INTENT = ? ORDER BY CREATED_AT, ID
                """)) {
            statement.setString(1, executionIntent.name());
            try (ResultSet result = statement.executeQuery()) {
                List<ConversationThread> values = new ArrayList<>();
                while (result.next()) {
                    values.add(map(result));
                }
                return List.copyOf(values);
            }
        }
    }

    private ConversationThread map(ResultSet result) throws SQLException {
        String parent = result.getString("PARENT_THREAD_ID");
        return new ConversationThread(
                ThreadId.parse(result.getString("ID")),
                WorkspaceId.parse(result.getString("WORKSPACE_ID")),
                parent == null ? Optional.empty() : Optional.of(ThreadId.parse(parent)),
                ThreadExecutionIntent.valueOf(result.getString("EXECUTION_INTENT")),
                result.getString("TITLE"),
                ThreadStatus.valueOf(result.getString("STATUS")),
                result.getLong("REVISION"),
                instant(result, "CREATED_AT"),
                instant(result, "UPDATED_AT"));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toInstant();
    }
}
