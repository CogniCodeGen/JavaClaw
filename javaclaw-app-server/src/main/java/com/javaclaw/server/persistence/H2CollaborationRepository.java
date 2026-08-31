package com.javaclaw.server.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.core.api.ThreadId;
import com.javaclaw.server.collaboration.CollaborationRepository;

/** H2-backed idempotent parent/child spawn registry. */
public final class H2CollaborationRepository implements CollaborationRepository {
    private final H2Database database;

    /** 绑定 App Server 持有的共享 H2Database；不另建连接工厂，数据库生命周期由装配层统一管理。 */
    public H2CollaborationRepository(H2Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public Optional<SpawnRecord> findSpawn(ThreadId parentThreadId, String idempotencyKey) {
        Objects.requireNonNull(parentThreadId, "parentThreadId");
        String key = required(idempotencyKey, "idempotencyKey", 500);
        return database.query(connection -> find(connection, parentThreadId, key));
    }

    @Override
    public SpawnRecord recordSpawn(
            ThreadId parentThreadId,
            ThreadId childThreadId,
            boolean writable,
            String profileId,
            String task,
            String idempotencyKey) {
        Objects.requireNonNull(parentThreadId, "parentThreadId");
        Objects.requireNonNull(childThreadId, "childThreadId");
        String profile = required(profileId, "profileId", 80);
        String safeTask = required(task, "task", 100_000);
        String key = required(idempotencyKey, "idempotencyKey", 500);
        return database.transaction(connection -> {
            Optional<SpawnRecord> existing = find(connection, parentThreadId, key);
            if (existing.isPresent()) {
                SpawnRecord value = existing.get();
                if (!value.childThreadId().equals(childThreadId)
                        || value.writable() != writable
                        || !value.profileId().equals(profile)
                        || !value.task().equals(safeTask)) {
                    throw new IllegalStateException(
                            "collaboration idempotency key was reused with a different request");
                }
                return value;
            }
            long now = System.currentTimeMillis();
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO collaboration_spawns(parent_thread_id, idempotency_key,
                        child_thread_id, writable, profile_id, task, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, parentThreadId.value());
                insert.setString(2, key);
                insert.setString(3, childThreadId.value());
                insert.setBoolean(4, writable);
                insert.setString(5, profile);
                insert.setString(6, safeTask);
                insert.setLong(7, now);
                insert.executeUpdate();
            }
            return find(connection, parentThreadId, key).orElseThrow();
        });
    }

    private static Optional<SpawnRecord> find(java.sql.Connection connection, ThreadId parent, String key)
            throws java.sql.SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM collaboration_spawns
                WHERE parent_thread_id = ? AND idempotency_key = ?
                """)) {
            query.setString(1, parent.value());
            query.setString(2, key);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SpawnRecord(
                        parent,
                        new ThreadId(row.getString("child_thread_id")),
                        row.getBoolean("writable"),
                        row.getString("profile_id"),
                        row.getString("task"),
                        row.getString("idempotency_key"),
                        Instant.ofEpochMilli(row.getLong("created_at"))));
            }
        }
    }

    private static String required(String value, String name, int maximum) {
        String result = Objects.requireNonNull(value, name).strip();
        if (result.isEmpty() || result.length() > maximum) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return result;
    }
}
