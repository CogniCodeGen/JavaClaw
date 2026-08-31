package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.javaclaw.agent.runtime.persistence.WorkspaceRepository;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.Workspace;
import com.javaclaw.core.api.WorkspaceId;
import com.javaclaw.sandbox.api.SandboxPaths;

/** Workspace projection with its own SQL and shared H2 transaction/idempotency utilities. */
public final class H2WorkspaceRepository implements WorkspaceRepository {
    private final H2Database database;
    private final Clock clock;
    private final H2IdempotencyStore idempotency = new H2IdempotencyStore();

    H2WorkspaceRepository(H2Database database, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Workspace create(String name, Path root, String key) {
        String normalizedName = ThreadId.required(name, "name");
        Path canonicalRoot = SandboxPaths.canonicalize(root);
        String normalizedKey = key == null || key.isBlank() ? null : key.strip();
        if (normalizedKey != null && normalizedKey.length() > 500) {
            throw new IllegalArgumentException("idempotencyKey exceeds 500 characters");
        }
        return database.transaction(connection -> {
            if (normalizedKey != null) {
                try (PreparedStatement query = connection.prepareStatement("""
                        SELECT * FROM workspaces WHERE idempotency_key = ?
                        """)) {
                    query.setString(1, normalizedKey);
                    try (ResultSet row = query.executeQuery()) {
                        if (row.next()) {
                            Workspace replay = read(row);
                            if (!replay.name().equals(normalizedName)
                                    || !replay.root().equals(canonicalRoot)) {
                                throw new IllegalStateException(
                                        "idempotency key was already used with another request");
                            }
                            return replay;
                        }
                    }
                }
            }
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT * FROM workspaces WHERE root_path = ?
                    """)) {
                query.setString(1, canonicalRoot.toString());
                try (ResultSet row = query.executeQuery()) {
                    if (row.next()) {
                        return read(row);
                    }
                }
            }
            WorkspaceId id =
                    new WorkspaceId("wsp_" + UUID.randomUUID().toString().replace("-", ""));
            long now = clock.millis();
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO workspaces(
                        workspace_id, name, root_path, revision, locked, lock_reason,
                        idempotency_key, created_at, updated_at)
                    VALUES (?, ?, ?, 1, FALSE, '', ?, ?, ?)
                    """)) {
                insert.setString(1, id.value());
                insert.setString(2, normalizedName);
                insert.setString(3, canonicalRoot.toString());
                insert.setString(4, normalizedKey);
                insert.setLong(5, now);
                insert.setLong(6, now);
                insert.executeUpdate();
            }
            return new Workspace(
                    id,
                    normalizedName,
                    canonicalRoot,
                    1,
                    false,
                    "",
                    Instant.ofEpochMilli(now),
                    Instant.ofEpochMilli(now));
        });
    }

    @Override
    public Optional<Workspace> find(WorkspaceId id) {
        Objects.requireNonNull(id, "id");
        return database.query(connection -> optional(connection, id, false));
    }

    @Override
    public Optional<Workspace> findByRoot(Path root) {
        Path canonical = SandboxPaths.canonicalize(root);
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM workspaces WHERE root_path = ?
                    """)) {
                statement.setString(1, canonical.toString());
                try (ResultSet row = statement.executeQuery()) {
                    return row.next() ? Optional.of(read(row)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<Workspace> list() {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM workspaces ORDER BY name, workspace_id
                    """);
                    ResultSet rows = statement.executeQuery()) {
                ArrayList<Workspace> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(read(rows));
                }
                return List.copyOf(result);
            }
        });
    }

    @Override
    public Workspace update(WorkspaceId id, String name, long revision) {
        return update(id, name, revision, null);
    }

    @Override
    public Workspace update(WorkspaceId id, String name, long revision, String key) {
        String normalizedName = ThreadId.required(name, "name");
        String hash = H2IdempotencyStore.requestHash(id, normalizedName, revision);
        return database.transaction(connection -> {
            Optional<String> replay = idempotency.replay(connection, "workspace/update", key, hash, String.class);
            if (replay.isPresent()) {
                return require(connection, new WorkspaceId(replay.get()), false);
            }
            Workspace updated = update(
                    connection, id, revision, "name = ?", statement -> statement.setString(1, normalizedName), 2);
            idempotency.record(
                    connection, "workspace/update", key, hash, updated.id().value(), clock.millis());
            return updated;
        });
    }

    @Override
    public Workspace setLocked(WorkspaceId id, boolean locked, String reason, long revision) {
        String normalizedReason = reason == null ? "" : reason.strip();
        if (normalizedReason.length() > 2_000) {
            throw new IllegalArgumentException("lock reason exceeds 2000 characters");
        }
        return database.transaction(connection -> update(
                connection,
                id,
                revision,
                "locked = ?, lock_reason = ?",
                statement -> {
                    statement.setBoolean(1, locked);
                    statement.setString(2, normalizedReason);
                },
                3));
    }

    @Override
    public void delete(WorkspaceId id, long revision) {
        delete(id, revision, null);
    }

    @Override
    public void delete(WorkspaceId id, long revision, String key) {
        Objects.requireNonNull(id, "id");
        String hash = H2IdempotencyStore.requestHash(id, revision);
        database.transaction(connection -> {
            Optional<Boolean> replay = idempotency.replay(connection, "workspace/delete", key, hash, Boolean.class);
            if (replay.isPresent()) {
                return null;
            }
            try (PreparedStatement count = connection.prepareStatement("""
                    SELECT COUNT(*) FROM threads WHERE workspace_id = ?
                    """)) {
                count.setString(1, id.value());
                try (ResultSet row = count.executeQuery()) {
                    row.next();
                    if (row.getLong(1) != 0) {
                        throw new IllegalStateException("workspace still owns threads: " + id);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM workspaces WHERE workspace_id = ? AND revision = ?
                    """)) {
                statement.setString(1, id.value());
                statement.setLong(2, revision);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("workspace revision conflict or not found: " + id);
                }
            }
            idempotency.record(connection, "workspace/delete", key, hash, Boolean.TRUE, clock.millis());
            return null;
        });
    }

    private Workspace update(
            Connection connection, WorkspaceId id, long revision, String assignments, SqlBinder binder, int nextIndex)
            throws SQLException {
        Objects.requireNonNull(id, "id");
        if (revision < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        require(connection, id, true);
        long now = clock.millis();
        String sql = "UPDATE workspaces SET " + assignments
                + ", revision = revision + 1, updated_at = ?"
                + " WHERE workspace_id = ? AND revision = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.setLong(nextIndex, now);
            statement.setString(nextIndex + 1, id.value());
            statement.setLong(nextIndex + 2, revision);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("workspace revision conflict: " + id);
            }
        }
        return require(connection, id, false);
    }

    private static Optional<Workspace> optional(Connection connection, WorkspaceId id, boolean lock)
            throws SQLException {
        String sql = "SELECT * FROM workspaces WHERE workspace_id = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.value());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        }
    }

    private static Workspace require(Connection connection, WorkspaceId id, boolean lock) throws SQLException {
        return optional(connection, id, lock)
                .orElseThrow(() -> new NoSuchElementException("workspace not found: " + id));
    }

    private static Workspace read(ResultSet row) throws SQLException {
        return new Workspace(
                new WorkspaceId(row.getString("workspace_id")),
                row.getString("name"),
                Path.of(row.getString("root_path")),
                row.getLong("revision"),
                row.getBoolean("locked"),
                row.getString("lock_reason"),
                Instant.ofEpochMilli(row.getLong("created_at")),
                Instant.ofEpochMilli(row.getLong("updated_at")));
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
