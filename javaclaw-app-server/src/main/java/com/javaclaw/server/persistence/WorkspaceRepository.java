package com.javaclaw.server.persistence;

import java.nio.file.Path;
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

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;

/** Workspace 行映射与 SQL。 */
final class WorkspaceRepository {
    Workspace insert(Connection connection, String name, Path root, Instant now) throws SQLException {
        Workspace workspace = new Workspace(WorkspaceId.random(), name, root, WorkspaceLifecycle.ACTIVE, 1, now, now);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.WORKSPACE (ID, NAME, ROOT_PATH, STATE, REVISION, CREATED_AT, UPDATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, workspace.id().toString());
            statement.setString(2, workspace.name());
            statement.setString(3, workspace.root().toString());
            statement.setString(4, workspace.lifecycle().name());
            statement.setLong(5, workspace.revision());
            statement.setObject(6, at(now));
            statement.setObject(7, at(now));
            statement.executeUpdate();
        }
        return workspace;
    }

    Optional<Workspace> find(Connection connection, WorkspaceId id) throws SQLException {
        return find(connection, id, false);
    }

    Optional<Workspace> lock(Connection connection, WorkspaceId id) throws SQLException {
        return find(connection, id, true);
    }

    private Optional<Workspace> find(Connection connection, WorkspaceId id, boolean lock) throws SQLException {
        String suffix = lock ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, NAME, ROOT_PATH, STATE, REVISION, CREATED_AT, UPDATED_AT
                FROM CORE.WORKSPACE WHERE ID = ?
                """ + suffix)) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    List<Workspace> list(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, NAME, ROOT_PATH, STATE, REVISION, CREATED_AT, UPDATED_AT
                FROM CORE.WORKSPACE ORDER BY CREATED_AT, ID
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                List<Workspace> values = new ArrayList<>();
                while (result.next()) {
                    values.add(map(result));
                }
                return List.copyOf(values);
            }
        }
    }

    Workspace rename(Connection connection, Workspace current, String name, Instant now) throws SQLException {
        Workspace next = new Workspace(
                current.id(),
                name,
                current.root(),
                current.lifecycle(),
                Math.addExact(current.revision(), 1),
                current.createdAt(),
                now);
        replace(connection, current, next);
        return next;
    }

    Workspace archive(Connection connection, Workspace current, Instant now) throws SQLException {
        if (current.lifecycle() != WorkspaceLifecycle.ACTIVE) {
            throw PersistenceException.invalidRequest("Workspace 已归档");
        }
        Workspace next = new Workspace(
                current.id(),
                current.name(),
                current.root(),
                WorkspaceLifecycle.ARCHIVED,
                Math.addExact(current.revision(), 1),
                current.createdAt(),
                now);
        replace(connection, current, next);
        return next;
    }

    private void replace(Connection connection, Workspace current, Workspace next) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.WORKSPACE
                SET NAME = ?, STATE = ?, REVISION = ?, UPDATED_AT = ?
                WHERE ID = ? AND REVISION = ?
                """)) {
            statement.setString(1, next.name());
            statement.setString(2, next.lifecycle().name());
            statement.setLong(3, next.revision());
            statement.setObject(4, at(next.updatedAt()));
            statement.setString(5, next.id().toString());
            statement.setLong(6, current.revision());
            if (statement.executeUpdate() != 1) {
                throw PersistenceException.revisionConflict("Workspace revision 已改变");
            }
        }
    }

    private Workspace map(ResultSet result) throws SQLException {
        return new Workspace(
                WorkspaceId.parse(result.getString("ID")),
                result.getString("NAME"),
                Path.of(result.getString("ROOT_PATH")),
                WorkspaceLifecycle.valueOf(result.getString("STATE")),
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
