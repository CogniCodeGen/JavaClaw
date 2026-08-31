package com.javaclaw.server.persistence;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.server.browser.BrowserSite;
import com.javaclaw.server.browser.BrowserSiteRepository;

/** 站点配置的事务实现；版本与幂等结果同事务提交，不保存浏览器秘密。 */
public final class H2BrowserSiteRepository implements BrowserSiteRepository {
    private final H2Database database;
    private final H2IdempotencyStore idempotency = new H2IdempotencyStore();
    private final ObjectMapper json = new ObjectMapper();

    /** 共享 App Server 唯一数据库，不创建额外写连接池。 */
    public H2BrowserSiteRepository(H2Database database) {
        this.database = java.util.Objects.requireNonNull(database);
    }

    @Override
    public List<BrowserSite> list(String workspaceId) {
        return database.query(connection -> {
            var result = new ArrayList<BrowserSite>();
            try (var query = connection.prepareStatement(
                    "SELECT * FROM browser_sites WHERE workspace_id=? ORDER BY name,site_id")) {
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
    public Optional<BrowserSite> find(String id) {
        return database.query(connection -> {
            try (var query = connection.prepareStatement("SELECT * FROM browser_sites WHERE site_id=?")) {
                query.setString(1, id);
                try (var row = query.executeQuery()) {
                    return row.next() ? Optional.of(read(row)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public BrowserSite put(BrowserSite site, long expectedRevision, String key) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be nonnegative");
        }
        return database.transaction(connection -> {
            String origins = encode(
                    site.allowedOrigins().stream().map(URI::toString).sorted().toList());
            String hash = H2IdempotencyStore.requestHash(
                    site.id(),
                    site.workspaceId(),
                    site.name(),
                    site.origin(),
                    origins,
                    site.enabled(),
                    expectedRevision);
            var replay = idempotency.replay(connection, "site/put", key, hash, BrowserSite.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            String id = site.id() == null || site.id().isBlank() ? "site_" + UUID.randomUUID() : site.id();
            long current = 0;
            try (var query = connection.prepareStatement(
                    "SELECT workspace_id,revision FROM browser_sites WHERE site_id=? FOR UPDATE")) {
                query.setString(1, id);
                try (var row = query.executeQuery()) {
                    if (row.next()) {
                        current = row.getLong(2);
                        if (!site.workspaceId().equals(row.getString(1))) {
                            throw new IllegalArgumentException("site workspace is immutable");
                        }
                    }
                }
            }
            if (current != expectedRevision) {
                throw new IllegalStateException("site revision conflict");
            }
            // 存储精度为毫秒；首个响应、幂等重放和后续查询必须是同一快照。
            Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
            BrowserSite result = new BrowserSite(
                    id,
                    site.workspaceId(),
                    site.name(),
                    site.origin(),
                    site.allowedOrigins(),
                    site.enabled(),
                    current + 1,
                    now);
            try (var update = connection.prepareStatement("""
                    MERGE INTO browser_sites(site_id,workspace_id,name,origin,origins_json,enabled,revision,updated_at)
                    KEY(site_id) VALUES(?,?,?,?,?,?,?,?)
                    """)) {
                update.setString(1, id);
                update.setString(2, site.workspaceId());
                update.setString(3, site.name());
                update.setString(4, site.origin().toString());
                update.setString(5, origins);
                update.setBoolean(6, site.enabled());
                update.setLong(7, current + 1);
                update.setLong(8, now.toEpochMilli());
                update.executeUpdate();
            }
            idempotency.record(connection, "site/put", key, hash, result, now.toEpochMilli());
            return result;
        });
    }

    @Override
    public boolean disable(String id, long revision, String key) {
        return database.transaction(connection -> {
            String hash = H2IdempotencyStore.requestHash(id, revision);
            var replay = idempotency.replay(connection, "site/delete", key, hash, Boolean.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            try (var update = connection.prepareStatement(
                    "UPDATE browser_sites SET enabled=FALSE,revision=revision+1,updated_at=? WHERE site_id=? AND revision=?")) {
                update.setLong(1, System.currentTimeMillis());
                update.setString(2, id);
                update.setLong(3, revision);
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("site revision conflict");
                }
            }
            idempotency.record(connection, "site/delete", key, hash, true, System.currentTimeMillis());
            return true;
        });
    }

    private BrowserSite read(ResultSet row) throws SQLException {
        try {
            var origins = new java.util.LinkedHashSet<URI>();
            json.readTree(row.getString("origins_json")).forEach(value -> origins.add(URI.create(value.asText())));
            return new BrowserSite(
                    row.getString("site_id"),
                    row.getString("workspace_id"),
                    row.getString("name"),
                    URI.create(row.getString("origin")),
                    Set.copyOf(origins),
                    row.getBoolean("enabled"),
                    row.getLong("revision"),
                    Instant.ofEpochMilli(row.getLong("updated_at")));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new SQLException("invalid stored site origins", invalid);
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("invalid site origins", invalid);
        }
    }
}
