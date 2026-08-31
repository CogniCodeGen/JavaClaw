package com.javaclaw.server.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.server.extension.PluginRepository;

/** H2 authority for Plugin 4.0 installation and health state. */
public final class H2PluginRepository implements PluginRepository {
    private final H2Database database;
    private final H2IdempotencyStore idempotency = new H2IdempotencyStore();

    /** 绑定 App Server 持有的共享 H2Database；不另建连接工厂，数据库生命周期由装配层统一管理。 */
    public H2PluginRepository(H2Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public List<PluginRecord> list() {
        return database.query(connection -> {
            ArrayList<PluginRecord> result = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("SELECT * FROM plugins ORDER BY plugin_id");
                    ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(read(rows));
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public Optional<PluginRecord> find(String pluginId) {
        return findBy("plugin_id", required(pluginId, "pluginId", 160));
    }

    @Override
    public Optional<PluginRecord> findByBundleSha256(String bundleSha256) {
        String hash = required(bundleSha256, "bundleSha256", 64).toLowerCase(java.util.Locale.ROOT);
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("bundleSha256 is invalid");
        }
        return findBy("bundle_sha256", hash);
    }

    private Optional<PluginRecord> findBy(String column, String value) {
        return database.query(connection -> {
            try (PreparedStatement query =
                    connection.prepareStatement("SELECT * FROM plugins WHERE " + column + " = ?")) {
                query.setString(1, value);
                try (ResultSet row = query.executeQuery()) {
                    return row.next() ? Optional.of(read(row)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public PluginRecord install(PluginRecordDraft draft, String idempotencyKey) {
        Objects.requireNonNull(draft, "draft");
        String requestHash = H2IdempotencyStore.requestHash(draft);
        return database.transaction(connection -> {
            Optional<PluginRecord> replay =
                    idempotency.replay(connection, "plugin/install", idempotencyKey, requestHash, PluginRecord.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            if (exists(connection, draft.id())) {
                throw new IllegalStateException("plugin is already installed: " + draft.id());
            }
            long now = System.currentTimeMillis();
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO plugins(plugin_id, version, install_path, manifest_json,
                        bundle_sha256, signer_key_id, signature_verified, source_confirmed,
                        permissions_approved, enabled, state, restart_count, last_error,
                        revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, '', 1, ?, ?)
                    """)) {
                insert.setString(1, required(draft.id(), "pluginId", 160));
                insert.setString(2, required(draft.version(), "version", 100));
                insert.setString(3, required(draft.installPath(), "installPath", 4000));
                insert.setString(4, required(draft.manifestJson(), "manifestJson", 2_000_000));
                insert.setString(5, required(draft.bundleSha256(), "bundleSha256", 64));
                insert.setString(6, draft.signerKeyId());
                insert.setBoolean(7, draft.signatureVerified());
                insert.setBoolean(8, draft.sourceConfirmed());
                insert.setBoolean(9, draft.permissionsApproved());
                insert.setBoolean(10, draft.enabled());
                insert.setString(11, draft.enabled() ? State.INSTALLED.name() : State.DISABLED.name());
                insert.setLong(12, now);
                insert.setLong(13, now);
                insert.executeUpdate();
            }
            PluginRecord result = require(connection, draft.id(), false);
            idempotency.record(connection, "plugin/install", idempotencyKey, requestHash, result, now);
            return result;
        });
    }

    @Override
    public PluginRecord setEnabled(String pluginId, boolean enabled, long expectedRevision, String idempotencyKey) {
        String id = required(pluginId, "pluginId", 160);
        String method = enabled ? "plugin/enable" : "plugin/disable";
        String requestHash = H2IdempotencyStore.requestHash(id, expectedRevision);
        return database.transaction(connection -> {
            Optional<PluginRecord> replay =
                    idempotency.replay(connection, method, idempotencyKey, requestHash, PluginRecord.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            long now = System.currentTimeMillis();
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE plugins SET enabled = ?, state = ?, revision = revision + 1,
                        updated_at = ? WHERE plugin_id = ? AND revision = ?
                    """)) {
                update.setBoolean(1, enabled);
                update.setString(2, enabled ? State.INSTALLED.name() : State.DISABLED.name());
                update.setLong(3, now);
                update.setString(4, id);
                update.setLong(5, expectedRevision);
                if (update.executeUpdate() != 1) {
                    conflict(connection, id);
                }
            }
            PluginRecord result = require(connection, id, false);
            idempotency.record(connection, method, idempotencyKey, requestHash, result, now);
            return result;
        });
    }

    @Override
    public PluginRecord setState(
            String pluginId, State state, String lastError, int restartCount, long expectedRevision) {
        String id = required(pluginId, "pluginId", 160);
        if (restartCount < 0) {
            throw new IllegalArgumentException("restartCount is negative");
        }
        String error = lastError == null ? "" : lastError.strip();
        if (error.length() > 100_000) {
            error = error.substring(0, 100_000);
        }
        String finalError = error;
        return database.transaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE plugins SET state = ?, restart_count = ?, last_error = ?,
                        revision = revision + 1, updated_at = ?
                    WHERE plugin_id = ? AND revision = ?
                    """)) {
                update.setString(1, Objects.requireNonNull(state, "state").name());
                update.setInt(2, restartCount);
                update.setString(3, finalError);
                update.setLong(4, System.currentTimeMillis());
                update.setString(5, id);
                update.setLong(6, expectedRevision);
                if (update.executeUpdate() != 1) {
                    conflict(connection, id);
                }
            }
            return require(connection, id, false);
        });
    }

    @Override
    public boolean delete(String pluginId, long expectedRevision, String idempotencyKey) {
        String id = required(pluginId, "pluginId", 160);
        String requestHash = H2IdempotencyStore.requestHash(id, expectedRevision);
        return database.transaction(connection -> {
            Optional<Boolean> replay =
                    idempotency.replay(connection, "plugin/uninstall", idempotencyKey, requestHash, Boolean.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            try (PreparedStatement delete =
                    connection.prepareStatement("DELETE FROM plugins WHERE plugin_id = ? AND revision = ?")) {
                delete.setString(1, id);
                delete.setLong(2, expectedRevision);
                int count = delete.executeUpdate();
                if (count == 0 && exists(connection, id)) {
                    conflict(connection, id);
                }
                boolean removed = count == 1;
                idempotency.record(
                        connection,
                        "plugin/uninstall",
                        idempotencyKey,
                        requestHash,
                        removed,
                        System.currentTimeMillis());
                return removed;
            }
        });
    }

    private static PluginRecord require(java.sql.Connection connection, String id, boolean lock)
            throws java.sql.SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM plugins WHERE plugin_id = ?" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, id);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    throw new NoSuchElementException("plugin not found: " + id);
                }
                return read(row);
            }
        }
    }

    private static boolean exists(java.sql.Connection connection, String id) throws java.sql.SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM plugins WHERE plugin_id = ?")) {
            query.setString(1, id);
            try (ResultSet row = query.executeQuery()) {
                return row.next();
            }
        }
    }

    private static void conflict(java.sql.Connection connection, String id) throws java.sql.SQLException {
        if (!exists(connection, id)) {
            throw new NoSuchElementException("plugin not found: " + id);
        }
        throw new IllegalStateException("plugin revision conflict: " + id);
    }

    private static PluginRecord read(ResultSet row) throws java.sql.SQLException {
        return new PluginRecord(
                row.getString("plugin_id"),
                row.getString("version"),
                row.getString("install_path"),
                row.getString("manifest_json"),
                row.getString("bundle_sha256"),
                row.getString("signer_key_id"),
                row.getBoolean("signature_verified"),
                row.getBoolean("source_confirmed"),
                row.getBoolean("permissions_approved"),
                row.getBoolean("enabled"),
                State.valueOf(row.getString("state")),
                row.getInt("restart_count"),
                row.getString("last_error"),
                row.getLong("revision"),
                Instant.ofEpochMilli(row.getLong("created_at")),
                Instant.ofEpochMilli(row.getLong("updated_at")));
    }

    private static String required(String value, String name, int maximum) {
        String result = Objects.requireNonNull(value, name).strip();
        if (result.isEmpty() || result.length() > maximum) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return result;
    }
}
