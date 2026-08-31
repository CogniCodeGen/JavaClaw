package com.javaclaw.server.persistence;

import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.server.extension.PluginTrustStore;

/** Revisioned H2 publisher-key trust store. Trust never implies permission approval. */
public final class H2PluginTrustStore implements PluginTrustStore {
    private final H2Database database;
    private final H2IdempotencyStore idempotency = new H2IdempotencyStore();

    /** 绑定 App Server 持有的共享 H2Database；不另建连接工厂，数据库生命周期由装配层统一管理。 */
    public H2PluginTrustStore(H2Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public List<TrustedKey> list() {
        return database.query(connection -> {
            ArrayList<TrustedKey> result = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("SELECT * FROM plugin_trust ORDER BY key_id");
                    ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(read(rows));
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public Optional<TrustedKey> find(String keyId) {
        String id = required(keyId, "keyId", 160);
        return database.query(connection -> {
            try (PreparedStatement query = connection.prepareStatement("SELECT * FROM plugin_trust WHERE key_id = ?")) {
                query.setString(1, id);
                try (ResultSet row = query.executeQuery()) {
                    return row.next() ? Optional.of(read(row)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public TrustedKey add(
            String keyId, byte[] x509PublicKey, String label, long expectedRevision, String idempotencyKey) {
        String id = required(keyId, "keyId", 160);
        String safeLabel = required(label, "label", 500);
        byte[] key = Objects.requireNonNull(x509PublicKey, "x509PublicKey").clone();
        validateEd25519(key);
        String requestHash = H2IdempotencyStore.requestHash(
                id, java.util.HexFormat.of().formatHex(key), safeLabel, expectedRevision);
        return database.transaction(connection -> {
            Optional<TrustedKey> replay =
                    idempotency.replay(connection, "plugin/trust/add", idempotencyKey, requestHash, TrustedKey.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            TrustedKey current = null;
            try (PreparedStatement query =
                    connection.prepareStatement("SELECT * FROM plugin_trust WHERE key_id = ? FOR UPDATE")) {
                query.setString(1, id);
                try (ResultSet row = query.executeQuery()) {
                    if (row.next()) {
                        current = read(row);
                    }
                }
            }
            if (current == null && expectedRevision > 0) {
                throw new NoSuchElementException("plugin trust key not found: " + id);
            }
            if (current != null && current.revision() != expectedRevision) {
                throw new IllegalStateException("plugin trust revision conflict: " + id);
            }
            long revision = current == null ? 1 : current.revision() + 1;
            long now = System.currentTimeMillis();
            try (PreparedStatement update = connection.prepareStatement("""
                    MERGE INTO plugin_trust(key_id, public_key, label, revision, created_at)
                    KEY(key_id) VALUES (?, ?, ?, ?, ?)
                    """)) {
                update.setString(1, id);
                update.setBytes(2, key);
                update.setString(3, safeLabel);
                update.setLong(4, revision);
                update.setLong(5, current == null ? now : current.createdAt().toEpochMilli());
                update.executeUpdate();
            }
            TrustedKey result = new TrustedKey(
                    id, key, safeLabel, revision, current == null ? Instant.ofEpochMilli(now) : current.createdAt());
            idempotency.record(connection, "plugin/trust/add", idempotencyKey, requestHash, result, now);
            return result;
        });
    }

    @Override
    public boolean remove(String keyId, long expectedRevision, String idempotencyKey) {
        String id = required(keyId, "keyId", 160);
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        String requestHash = H2IdempotencyStore.requestHash(id, expectedRevision);
        return database.transaction(connection -> {
            Optional<Boolean> replay =
                    idempotency.replay(connection, "plugin/trust/remove", idempotencyKey, requestHash, Boolean.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            try (PreparedStatement delete =
                    connection.prepareStatement("DELETE FROM plugin_trust WHERE key_id = ? AND revision = ?")) {
                delete.setString(1, id);
                delete.setLong(2, expectedRevision);
                int count = delete.executeUpdate();
                if (count == 0 && exists(connection, id)) {
                    throw new IllegalStateException("plugin trust revision conflict: " + id);
                }
                boolean removed = count == 1;
                idempotency.record(
                        connection,
                        "plugin/trust/remove",
                        idempotencyKey,
                        requestHash,
                        removed,
                        System.currentTimeMillis());
                return removed;
            }
        });
    }

    private static boolean exists(java.sql.Connection connection, String id) throws java.sql.SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM plugin_trust WHERE key_id = ?")) {
            query.setString(1, id);
            try (ResultSet row = query.executeQuery()) {
                return row.next();
            }
        }
    }

    private static TrustedKey read(ResultSet row) throws java.sql.SQLException {
        return new TrustedKey(
                row.getString("key_id"),
                row.getBytes("public_key"),
                row.getString("label"),
                row.getLong("revision"),
                Instant.ofEpochMilli(row.getLong("created_at")));
    }

    private static void validateEd25519(byte[] value) {
        if (value.length < 32 || value.length > 1024) {
            throw new IllegalArgumentException("Ed25519 public key encoding is invalid");
        }
        try {
            var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(value));
            if (!"EdDSA".equalsIgnoreCase(key.getAlgorithm()) && !"Ed25519".equalsIgnoreCase(key.getAlgorithm())) {
                throw new IllegalArgumentException("public key is not Ed25519");
            }
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalArgumentException("Ed25519 public key encoding is invalid", failure);
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
