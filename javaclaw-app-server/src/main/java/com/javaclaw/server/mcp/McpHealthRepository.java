package com.javaclaw.server.mcp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpHealth;
import com.javaclaw.protocol.CanonicalJson;

/** MCP 最新脱敏健康投影的 SQL 边界。 */
final class McpHealthRepository {
    private final CanonicalJson json;

    McpHealthRepository(CanonicalJson json) {
        this.json = json;
    }

    void save(Connection connection, McpHealth health) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                MERGE INTO CORE.MCP_HEALTH (
                    ENDPOINT_ID, ENDPOINT_REVISION, PAYLOAD, CHECKED_AT
                ) KEY (ENDPOINT_ID) VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, health.endpointId());
            statement.setLong(2, health.endpointRevision());
            statement.setString(3, json.encode(health).json());
            statement.setObject(4, health.checkedAt().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    Optional<McpHealth> find(Connection connection, String endpointId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT PAYLOAD FROM CORE.MCP_HEALTH WHERE ENDPOINT_ID = ?")) {
            statement.setString(1, endpointId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(json.decode(new CanonicalPayload(result.getString(1)), McpHealth.class))
                        : Optional.empty();
            }
        }
    }
}
