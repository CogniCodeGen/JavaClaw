package com.javaclaw.server.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.server.extension.McpRepository;

/** H2 MCP authority used by App Server process orchestration. */
public final class H2McpRepository implements McpRepository {
    private final H2Database database;
    private final H2IdempotencyStore idempotency = new H2IdempotencyStore();

    /** 绑定 App Server 持有的共享 H2Database；不另建连接工厂，数据库生命周期由装配层统一管理。 */
    public H2McpRepository(H2Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public List<McpRecord> list() {
        return database.query(connection -> {
            ArrayList<McpRecord> result = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("SELECT * FROM mcp_servers ORDER BY mcp_id");
                    ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(read(rows));
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public Optional<McpRecord> find(String id) {
        String mcpId = required(id, "mcpId", 160);
        return database.query(connection -> {
            try (PreparedStatement query = connection.prepareStatement("SELECT * FROM mcp_servers WHERE mcp_id = ?")) {
                query.setString(1, mcpId);
                try (ResultSet row = query.executeQuery()) {
                    return row.next() ? Optional.of(read(row)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public McpRecord put(McpDraft draft, long expectedRevision, String idempotencyKey) {
        Objects.requireNonNull(draft, "draft");
        String id = required(draft.id(), "mcpId", 160);
        String requestHash = H2IdempotencyStore.requestHash(draft, expectedRevision);
        return database.transaction(connection -> {
            Optional<McpRecord> replay =
                    idempotency.replay(connection, "mcp/configure", idempotencyKey, requestHash, McpRecord.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            McpRecord current = find(connection, id, true).orElse(null);
            if (current == null && expectedRevision > 0) {
                throw new NoSuchElementException("MCP server not found: " + id);
            }
            if (current != null && current.revision() != expectedRevision) {
                throw new IllegalStateException("MCP revision conflict: " + id);
            }
            long revision = current == null ? 1 : current.revision() + 1;
            long now = System.currentTimeMillis();
            try (PreparedStatement merge = connection.prepareStatement("""
                    MERGE INTO mcp_servers(mcp_id, plugin_id, name, config_json,
                        enabled, state, revision, updated_at) KEY(mcp_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                merge.setString(1, id);
                merge.setString(2, draft.pluginId());
                merge.setString(3, required(draft.name(), "name", 500));
                merge.setString(4, required(draft.configJson(), "configJson", 1_000_000));
                merge.setBoolean(5, draft.enabled());
                merge.setString(6, draft.enabled() ? "CONFIGURED" : "DISABLED");
                merge.setLong(7, revision);
                merge.setLong(8, now);
                merge.executeUpdate();
            }
            McpRecord result = find(connection, id, false).orElseThrow();
            idempotency.record(connection, "mcp/configure", idempotencyKey, requestHash, result, now);
            return result;
        });
    }

    @Override
    public McpRecord setState(String id, String state, long expectedRevision) {
        String mcpId = required(id, "mcpId", 160);
        String normalizedState = required(state, "state", 40);
        return database.transaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE mcp_servers SET state = ?, revision = revision + 1,
                        updated_at = ? WHERE mcp_id = ? AND revision = ?
                    """)) {
                update.setString(1, normalizedState);
                update.setLong(2, System.currentTimeMillis());
                update.setString(3, mcpId);
                update.setLong(4, expectedRevision);
                if (update.executeUpdate() != 1) {
                    if (find(connection, mcpId, false).isEmpty()) {
                        throw new NoSuchElementException("MCP server not found: " + mcpId);
                    }
                    throw new IllegalStateException("MCP revision conflict: " + mcpId);
                }
            }
            return find(connection, mcpId, false).orElseThrow();
        });
    }

    @Override
    public int deleteForPlugin(String pluginId) {
        String owner = required(pluginId, "pluginId", 160);
        return database.transaction(connection -> {
            try (PreparedStatement delete =
                    connection.prepareStatement("DELETE FROM mcp_servers WHERE plugin_id = ?")) {
                delete.setString(1, owner);
                return delete.executeUpdate();
            }
        });
    }

    private static Optional<McpRecord> find(java.sql.Connection connection, String id, boolean lock)
            throws java.sql.SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM mcp_servers WHERE mcp_id = ?" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, id);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        }
    }

    private static McpRecord read(ResultSet row) throws java.sql.SQLException {
        return new McpRecord(
                row.getString("mcp_id"),
                row.getString("plugin_id"),
                row.getString("name"),
                row.getString("config_json"),
                row.getBoolean("enabled"),
                row.getString("state"),
                row.getLong("revision"),
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
