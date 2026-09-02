package com.javaclaw.server.mcp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import com.javaclaw.api.McpCatalogRefresh;
import com.javaclaw.api.McpCatalogRefreshState;

/** MCP Catalog 刷新进度的 SQL 边界。 */
final class McpCatalogRefreshRepository {
    Optional<McpCatalogRefresh> find(Connection connection, String endpointId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT * FROM CORE.MCP_CATALOG_REFRESH WHERE ENDPOINT_ID = ?")) {
            statement.setString(1, endpointId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    void save(Connection connection, McpCatalogRefresh refresh) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                MERGE INTO CORE.MCP_CATALOG_REFRESH (
                    ENDPOINT_ID, ENDPOINT_REVISION, STATE, PAGES_COMPLETED, ENTRIES_DISCOVERED,
                    COMMITTED_CATALOG_REVISION, DETAIL, STARTED_AT, UPDATED_AT)
                KEY (ENDPOINT_ID) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, refresh.endpointId());
            statement.setLong(2, refresh.endpointRevision());
            statement.setString(3, refresh.state().name());
            statement.setInt(4, refresh.pagesCompleted());
            statement.setInt(5, refresh.entriesDiscovered());
            if (refresh.committedCatalogRevision().isPresent()) {
                statement.setLong(6, refresh.committedCatalogRevision().orElseThrow());
            } else {
                statement.setNull(6, java.sql.Types.BIGINT);
            }
            statement.setString(7, refresh.detail().orElse(null));
            statement.setObject(8, refresh.startedAt());
            statement.setObject(9, refresh.updatedAt());
            statement.executeUpdate();
        }
    }

    private static McpCatalogRefresh map(ResultSet result) throws SQLException {
        Long committed = result.getObject("COMMITTED_CATALOG_REVISION", Long.class);
        return new McpCatalogRefresh(
                result.getString("ENDPOINT_ID"),
                result.getLong("ENDPOINT_REVISION"),
                McpCatalogRefreshState.valueOf(result.getString("STATE")),
                result.getInt("PAGES_COMPLETED"),
                result.getInt("ENTRIES_DISCOVERED"),
                Optional.ofNullable(committed),
                Optional.ofNullable(result.getString("DETAIL")),
                result.getObject("STARTED_AT", java.time.OffsetDateTime.class).toInstant(),
                result.getObject("UPDATED_AT", java.time.OffsetDateTime.class).toInstant());
    }
}
