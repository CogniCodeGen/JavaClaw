package com.javaclaw.server.persistence;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.server.network.NetworkGrant;
import com.javaclaw.server.network.NetworkGrantRepository;

/** 精确私网授权的 H2 实现；修改和撤销均更新版本，连接前不使用过期缓存。 */
public final class H2NetworkGrantRepository implements NetworkGrantRepository {
    private final H2Database database;
    private final H2IdempotencyStore idempotency = new H2IdempotencyStore();
    private final ObjectMapper json = new ObjectMapper();

    /** 复用 App Server 的事务执行器。 */
    public H2NetworkGrantRepository(H2Database database) {
        this.database = java.util.Objects.requireNonNull(database);
    }

    @Override
    public List<NetworkGrant> list(String workspaceId) {
        return database.query(connection -> {
            var result = new ArrayList<NetworkGrant>();
            try (var query = connection.prepareStatement(
                    "SELECT * FROM network_grants WHERE workspace_id=? ORDER BY grant_id")) {
                query.setString(1, workspaceId);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        result.add(read(rows));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public NetworkGrant put(NetworkGrant grant, long revision, String key) {
        if (revision < 0) {
            throw new IllegalArgumentException("expectedRevision must be nonnegative");
        }
        return database.transaction(connection -> {
            String addresses;
            try {
                addresses = json.writeValueAsString(
                        grant.addresses().stream().sorted().toList());
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw new SQLException(invalid);
            }
            String hash = H2IdempotencyStore.requestHash(
                    grant.id(),
                    grant.workspaceId(),
                    grant.purpose(),
                    grant.origin(),
                    addresses,
                    grant.expiresAt(),
                    grant.enabled(),
                    revision);
            var replay = idempotency.replay(connection, "network/grant/put", key, hash, NetworkGrant.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            String id = grant.id() == null || grant.id().isBlank() ? "network_" + UUID.randomUUID() : grant.id();
            long current = 0;
            try (var query = connection.prepareStatement(
                    "SELECT workspace_id,purpose,origin,revision FROM network_grants WHERE grant_id=? FOR UPDATE")) {
                query.setString(1, id);
                try (var row = query.executeQuery()) {
                    if (row.next()) {
                        current = row.getLong(4);
                        if (!row.getString(1).equals(grant.workspaceId())
                                || !row.getString(2).equals(grant.purpose())
                                || !row.getString(3).equals(grant.origin().toString())) {
                            throw new IllegalArgumentException("network authorization scope is immutable");
                        }
                    }
                }
            }
            if (current != revision) {
                throw new IllegalStateException("network grant revision conflict");
            }
            var result = new NetworkGrant(
                    id,
                    grant.workspaceId(),
                    grant.purpose(),
                    grant.origin(),
                    grant.addresses(),
                    grant.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                    grant.enabled(),
                    current + 1,
                    Instant.ofEpochMilli(System.currentTimeMillis()));
            try (var update = connection.prepareStatement("""
                    MERGE INTO network_grants(grant_id,workspace_id,purpose,origin,addresses_json,expires_at,enabled,revision,updated_at)
                    KEY(grant_id) VALUES(?,?,?,?,?,?,?,?,?)
                    """)) {
                update.setString(1, id);
                update.setString(2, grant.workspaceId());
                update.setString(3, grant.purpose());
                update.setString(4, grant.origin().toString());
                update.setString(5, addresses);
                update.setLong(6, grant.expiresAt().toEpochMilli());
                update.setBoolean(7, grant.enabled());
                update.setLong(8, current + 1);
                update.setLong(9, result.updatedAt().toEpochMilli());
                update.executeUpdate();
            }
            idempotency.record(
                    connection,
                    "network/grant/put",
                    key,
                    hash,
                    result,
                    result.updatedAt().toEpochMilli());
            return result;
        });
    }

    @Override
    public boolean disable(String id, long revision, String key) {
        return database.transaction(connection -> {
            String hash = H2IdempotencyStore.requestHash(id, revision);
            var replay = idempotency.replay(connection, "network/grant/delete", key, hash, Boolean.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            try (var update = connection.prepareStatement(
                    "UPDATE network_grants SET enabled=FALSE,revision=revision+1,updated_at=? WHERE grant_id=? AND revision=?")) {
                update.setLong(1, System.currentTimeMillis());
                update.setString(2, id);
                update.setLong(3, revision);
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("network grant revision conflict");
                }
            }
            idempotency.record(connection, "network/grant/delete", key, hash, true, System.currentTimeMillis());
            return true;
        });
    }

    private NetworkGrant read(ResultSet row) throws SQLException {
        try {
            var addresses = new java.util.LinkedHashSet<String>();
            json.readTree(row.getString("addresses_json")).forEach(value -> addresses.add(value.asText()));
            return new NetworkGrant(
                    row.getString("grant_id"),
                    row.getString("workspace_id"),
                    row.getString("purpose"),
                    URI.create(row.getString("origin")),
                    addresses,
                    Instant.ofEpochMilli(row.getLong("expires_at")),
                    row.getBoolean("enabled"),
                    row.getLong("revision"),
                    Instant.ofEpochMilli(row.getLong("updated_at")));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new SQLException("invalid network grant", invalid);
        }
    }
}
