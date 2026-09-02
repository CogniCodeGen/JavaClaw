package com.javaclaw.server.mcp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpCatalogEntry;
import com.javaclaw.api.McpCatalogKind;
import com.javaclaw.protocol.CanonicalJson;

/** MCP Catalog 不可变 revision 的 SQL 边界。 */
final class McpCatalogRepository {
    private final CanonicalJson json;

    McpCatalogRepository(CanonicalJson json) {
        this.json = json;
    }

    void insert(Connection connection, String endpointId, long revision, List<McpCatalogEntry> entries)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.MCP_CATALOG_ENTRY (
                    ENDPOINT_ID, CATALOG_REVISION, KIND, ENTRY_NAME, PAYLOAD
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
            for (McpCatalogEntry entry : entries) {
                statement.setString(1, endpointId);
                statement.setLong(2, revision);
                statement.setString(3, entry.kind().name());
                statement.setString(4, entry.name());
                statement.setString(5, json.encode(entry).json());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    List<McpCatalogEntry> list(
            Connection connection,
            String endpointId,
            long revision,
            Optional<McpCatalogKind> kind,
            int offset,
            int limit)
            throws SQLException {
        String filter = kind.isPresent() ? " AND KIND = ?" : "";
        String sql = "SELECT PAYLOAD FROM CORE.MCP_CATALOG_ENTRY"
                + " WHERE ENDPOINT_ID = ? AND CATALOG_REVISION = ?"
                + filter
                + " ORDER BY KIND, ENTRY_NAME OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            statement.setString(parameter++, endpointId);
            statement.setLong(parameter++, revision);
            if (kind.isPresent()) {
                statement.setString(parameter++, kind.orElseThrow().name());
            }
            statement.setInt(parameter++, offset);
            statement.setInt(parameter, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<McpCatalogEntry> entries = new ArrayList<>();
                while (result.next()) {
                    entries.add(json.decode(new CanonicalPayload(result.getString(1)), McpCatalogEntry.class));
                }
                return List.copyOf(entries);
            }
        }
    }

    Optional<McpCatalogEntry> findTool(Connection connection, String endpointId, long revision, String name)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT PAYLOAD FROM CORE.MCP_CATALOG_ENTRY
                WHERE ENDPOINT_ID = ? AND CATALOG_REVISION = ? AND KIND = 'TOOL' AND ENTRY_NAME = ?
                """)) {
            statement.setString(1, endpointId);
            statement.setLong(2, revision);
            statement.setString(3, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(json.decode(new CanonicalPayload(result.getString(1)), McpCatalogEntry.class))
                        : Optional.empty();
            }
        }
    }
}
