package com.javaclaw.server.mcp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.server.persistence.PersistenceException;

/** MCP 外部副作用 intent、明确结果与 UNKNOWN_OUTCOME 的固定 SQL。 */
final class McpInvocationRepository {
    Optional<StoredInvocation> find(Connection connection, String idempotencyKey, boolean lock) throws SQLException {
        String suffix = lock ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ENDPOINT_ID, ENDPOINT_REVISION, CATALOG_REVISION, TOOL_NAME,
                       REQUEST_DIGEST, STATE, RESULT_PAYLOAD, CREATED_AT, UPDATED_AT
                FROM CORE.MCP_TOOL_INVOCATION WHERE IDEMPOTENCY_KEY = ?
                """ + suffix)) {
            statement.setString(1, idempotencyKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(idempotencyKey, result)) : Optional.empty();
            }
        }
    }

    void insert(Connection connection, StoredInvocation invocation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.MCP_TOOL_INVOCATION (
                    IDEMPOTENCY_KEY, ENDPOINT_ID, ENDPOINT_REVISION, CATALOG_REVISION,
                    TOOL_NAME, REQUEST_DIGEST, STATE, RESULT_PAYLOAD, CREATED_AT, UPDATED_AT
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindIdentity(statement, invocation);
            statement.setString(7, invocation.state().name());
            statement.setString(
                    8, invocation.result().map(CanonicalPayload::json).orElse(null));
            statement.setObject(9, invocation.createdAt().atOffset(ZoneOffset.UTC));
            statement.setObject(10, invocation.updatedAt().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    void finish(
            Connection connection,
            String idempotencyKey,
            InvocationState expected,
            InvocationState state,
            Optional<CanonicalPayload> result,
            Instant updatedAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.MCP_TOOL_INVOCATION
                SET STATE = ?, RESULT_PAYLOAD = ?, UPDATED_AT = ?
                WHERE IDEMPOTENCY_KEY = ? AND STATE = ?
                """)) {
            statement.setString(1, state.name());
            statement.setString(2, result.map(CanonicalPayload::json).orElse(null));
            statement.setObject(3, updatedAt.atOffset(ZoneOffset.UTC));
            statement.setString(4, idempotencyKey);
            statement.setString(5, expected.name());
            if (statement.executeUpdate() != 1) {
                throw PersistenceException.revisionConflict("MCP invocation state 已变化");
            }
        }
    }

    int recoverUnknown(Connection connection, Instant updatedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.MCP_TOOL_INVOCATION
                SET STATE = 'UNKNOWN_OUTCOME', UPDATED_AT = ?
                WHERE STATE = 'IN_FLIGHT'
                """)) {
            statement.setObject(1, updatedAt.atOffset(ZoneOffset.UTC));
            return statement.executeUpdate();
        }
    }

    private static void bindIdentity(PreparedStatement statement, StoredInvocation value) throws SQLException {
        statement.setString(1, value.idempotencyKey());
        statement.setString(2, value.endpointId());
        statement.setLong(3, value.endpointRevision());
        statement.setLong(4, value.catalogRevision());
        statement.setString(5, value.toolName());
        statement.setString(6, value.requestDigest());
    }

    private static StoredInvocation map(String idempotencyKey, ResultSet result) throws SQLException {
        String payload = result.getString("RESULT_PAYLOAD");
        return new StoredInvocation(
                idempotencyKey,
                result.getString("ENDPOINT_ID"),
                result.getLong("ENDPOINT_REVISION"),
                result.getLong("CATALOG_REVISION"),
                result.getString("TOOL_NAME"),
                result.getString("REQUEST_DIGEST"),
                InvocationState.valueOf(result.getString("STATE")),
                Optional.ofNullable(payload).map(CanonicalPayload::new),
                result.getObject("CREATED_AT", OffsetDateTime.class).toInstant(),
                result.getObject("UPDATED_AT", OffsetDateTime.class).toInstant());
    }

    enum InvocationState {
        IN_FLIGHT,
        COMPLETED,
        UNKNOWN_OUTCOME
    }

    record StoredInvocation(
            String idempotencyKey,
            String endpointId,
            long endpointRevision,
            long catalogRevision,
            String toolName,
            String requestDigest,
            InvocationState state,
            Optional<CanonicalPayload> result,
            Instant createdAt,
            Instant updatedAt) {}
}
