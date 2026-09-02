package com.javaclaw.server.mcp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.javaclaw.server.persistence.PersistenceException;

/** 含授权 URI 与 PKCE CredentialRef 的 OAuth 私有 SQL 边界。 */
final class McpOAuthRepository {
    Optional<McpOAuthSession> find(Connection connection, String id, boolean lock) throws SQLException {
        String suffix = lock ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, ENDPOINT_ID, ENDPOINT_REVISION, AUTHORIZATION_URI,
                       CREDENTIAL_NAMESPACE, CREDENTIAL_ID, STATE, EXPIRES_AT, DETAIL, UPDATED_AT
                FROM CORE.MCP_OAUTH_SESSION WHERE ID = ?
                """ + suffix)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    Optional<McpOAuthSession> findLatest(Connection connection, String endpointId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, ENDPOINT_ID, ENDPOINT_REVISION, AUTHORIZATION_URI,
                       CREDENTIAL_NAMESPACE, CREDENTIAL_ID, STATE, EXPIRES_AT, DETAIL, UPDATED_AT
                FROM CORE.MCP_OAUTH_SESSION WHERE ENDPOINT_ID = ?
                ORDER BY UPDATED_AT DESC, ID DESC LIMIT 1
                """)) {
            statement.setString(1, endpointId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    java.util.List<McpOAuthSession> pending(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, ENDPOINT_ID, ENDPOINT_REVISION, AUTHORIZATION_URI,
                       CREDENTIAL_NAMESPACE, CREDENTIAL_ID, STATE, EXPIRES_AT, DETAIL, UPDATED_AT
                FROM CORE.MCP_OAUTH_SESSION WHERE STATE = 'PENDING'
                ORDER BY UPDATED_AT, ID
                """);
                ResultSet result = statement.executeQuery()) {
            java.util.ArrayList<McpOAuthSession> values = new java.util.ArrayList<>();
            while (result.next()) {
                values.add(map(result));
            }
            return java.util.List.copyOf(values);
        }
    }

    java.util.List<McpOAuthSession> cleanupCandidates(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, ENDPOINT_ID, ENDPOINT_REVISION, AUTHORIZATION_URI,
                       CREDENTIAL_NAMESPACE, CREDENTIAL_ID, STATE, EXPIRES_AT, DETAIL, UPDATED_AT
                FROM CORE.MCP_OAUTH_SESSION
                WHERE STATE IN ('FAILED', 'EXPIRED', 'CANCELLED')
                ORDER BY UPDATED_AT, ID
                """);
                ResultSet result = statement.executeQuery()) {
            java.util.ArrayList<McpOAuthSession> values = new java.util.ArrayList<>();
            while (result.next()) {
                values.add(map(result));
            }
            return java.util.List.copyOf(values);
        }
    }

    void insert(Connection connection, McpOAuthSession authorization) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.MCP_OAUTH_SESSION (
                    ID, ENDPOINT_ID, ENDPOINT_REVISION, AUTHORIZATION_URI,
                    CREDENTIAL_NAMESPACE, CREDENTIAL_ID, STATE, EXPIRES_AT, DETAIL, UPDATED_AT
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bind(statement, authorization);
            statement.executeUpdate();
        }
    }

    void update(Connection connection, McpOAuthSession authorization) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.MCP_OAUTH_SESSION SET STATE = ?, DETAIL = ?, UPDATED_AT = ? WHERE ID = ?
                """)) {
            statement.setString(1, authorization.state().name());
            statement.setString(2, authorization.detail().orElse(null));
            statement.setObject(3, authorization.updatedAt().atOffset(ZoneOffset.UTC));
            statement.setString(4, authorization.id());
            if (statement.executeUpdate() != 1) {
                throw PersistenceException.invalidRequest("OAuth session 不存在");
            }
        }
    }

    private static void bind(PreparedStatement statement, McpOAuthSession value) throws SQLException {
        statement.setString(1, value.id());
        statement.setString(2, value.endpointId());
        statement.setLong(3, value.endpointRevision());
        statement.setString(4, value.authorizationUri().toString());
        statement.setString(5, value.credential().namespace());
        statement.setString(6, value.credential().id());
        statement.setString(7, value.state().name());
        statement.setObject(8, value.expiresAt().atOffset(ZoneOffset.UTC));
        statement.setString(9, value.detail().orElse(null));
        statement.setObject(10, value.updatedAt().atOffset(ZoneOffset.UTC));
    }

    private static McpOAuthSession map(ResultSet result) throws SQLException {
        return new McpOAuthSession(
                result.getString("ID"),
                result.getString("ENDPOINT_ID"),
                result.getLong("ENDPOINT_REVISION"),
                java.net.URI.create(result.getString("AUTHORIZATION_URI")),
                new com.javaclaw.api.CredentialRef(
                        result.getString("CREDENTIAL_NAMESPACE"), result.getString("CREDENTIAL_ID")),
                com.javaclaw.api.McpOAuthState.valueOf(result.getString("STATE")),
                result.getObject("EXPIRES_AT", OffsetDateTime.class).toInstant(),
                Optional.ofNullable(result.getString("DETAIL")),
                result.getObject("UPDATED_AT", OffsetDateTime.class).toInstant());
    }
}
