package com.javaclaw.server.mcp;

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

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;

/** MCP Endpoint 不可变版本的 SQL row mapper。 */
final class McpEndpointRepository {
    private final CanonicalJson json;

    McpEndpointRepository(CanonicalJson json) {
        this.json = json;
    }

    List<McpEndpoint> listLatest(Connection connection, WorkspaceId workspaceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT E.ID, E.REVISION, E.STATE, E.CATALOG_REVISION, E.PAYLOAD, E.CREATED_AT, E.UPDATED_AT
                FROM CORE.MCP_ENDPOINT E
                JOIN (
                    SELECT ID, MAX(REVISION) REVISION FROM CORE.MCP_ENDPOINT
                    WHERE WORKSPACE_ID = ? GROUP BY ID
                ) L ON L.ID = E.ID AND L.REVISION = E.REVISION
                ORDER BY E.ID
                """)) {
            statement.setString(1, workspaceId.toString());
            try (ResultSet result = statement.executeQuery()) {
                List<McpEndpoint> endpoints = new ArrayList<>();
                while (result.next()) {
                    endpoints.add(map(result));
                }
                return List.copyOf(endpoints);
            }
        }
    }

    List<McpEndpoint> history(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, REVISION, STATE, CATALOG_REVISION, PAYLOAD, CREATED_AT, UPDATED_AT
                FROM CORE.MCP_ENDPOINT WHERE ID = ? ORDER BY REVISION
                """)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                List<McpEndpoint> endpoints = new ArrayList<>();
                while (result.next()) {
                    endpoints.add(map(result));
                }
                return List.copyOf(endpoints);
            }
        }
    }

    Optional<McpEndpoint> latest(Connection connection, String id, boolean lock) throws SQLException {
        String suffix = lock ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, REVISION, STATE, CATALOG_REVISION, PAYLOAD, CREATED_AT, UPDATED_AT
                FROM CORE.MCP_ENDPOINT WHERE ID = ? ORDER BY REVISION DESC LIMIT 1
                """ + suffix)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    Optional<McpEndpoint> find(Connection connection, String id, long revision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, REVISION, STATE, CATALOG_REVISION, PAYLOAD, CREATED_AT, UPDATED_AT
                FROM CORE.MCP_ENDPOINT WHERE ID = ? AND REVISION = ?
                """)) {
            statement.setString(1, id);
            statement.setLong(2, revision);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    void insert(Connection connection, McpEndpoint endpoint) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.MCP_ENDPOINT (
                    ID, REVISION, STATE, CATALOG_REVISION, WORKSPACE_ID,
                    PAYLOAD, CREATED_AT, UPDATED_AT
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, endpoint.id());
            statement.setLong(2, endpoint.revision());
            statement.setString(3, endpoint.state().name());
            statement.setLong(4, endpoint.catalogRevision());
            statement.setString(5, endpoint.spec().workspaceId().toString());
            statement.setString(6, json.encode(endpoint.spec()).json());
            statement.setObject(7, endpoint.createdAt().atOffset(ZoneOffset.UTC));
            statement.setObject(8, endpoint.updatedAt().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private McpEndpoint map(ResultSet result) throws SQLException {
        McpEndpointSpec spec = json.decode(new CanonicalPayload(result.getString("PAYLOAD")), McpEndpointSpec.class);
        return new McpEndpoint(
                result.getString("ID"),
                result.getLong("REVISION"),
                McpEndpointState.valueOf(result.getString("STATE")),
                result.getLong("CATALOG_REVISION"),
                spec,
                instant(result, "CREATED_AT"),
                instant(result, "UPDATED_AT"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toInstant();
    }
}
