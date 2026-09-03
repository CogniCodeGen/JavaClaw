package com.javaclaw.server.persistence;

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
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;

/** Provider 外部验证意图与脱敏终态的 SQL 边界。 */
final class ProviderVerificationRepository {
    Optional<StoredVerification> find(Connection connection, String idempotencyKey, boolean forUpdate)
            throws SQLException {
        String lock = forUpdate ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT METHOD_NAME, REQUEST_DIGEST, PROVIDER_ID, PROVIDER_REVISION, MODEL, PURPOSE,
                       STATE, RESULT_PAYLOAD, CREATED_AT, UPDATED_AT
                FROM CORE.PROVIDER_VERIFICATION WHERE IDEMPOTENCY_KEY = ?
                """ + lock)) {
            statement.setString(1, idempotencyKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(idempotencyKey, result)) : Optional.empty();
            }
        }
    }

    void insertRunning(
            Connection connection,
            CommandIdentity identity,
            ProviderRef provider,
            ProviderModelPurpose purpose,
            Instant createdAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.PROVIDER_VERIFICATION (
                    IDEMPOTENCY_KEY, METHOD_NAME, REQUEST_DIGEST, PROVIDER_ID, PROVIDER_REVISION,
                    MODEL, PURPOSE, STATE, RESULT_PAYLOAD, CREATED_AT, UPDATED_AT
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'RUNNING', NULL, ?, ?)
                """)) {
            statement.setString(1, identity.idempotencyKey());
            statement.setString(2, identity.method());
            statement.setString(3, identity.requestDigest());
            statement.setString(4, provider.endpointId());
            statement.setLong(5, provider.endpointRevision());
            statement.setString(6, provider.model());
            statement.setString(7, purpose.name());
            statement.setObject(8, createdAt.atOffset(ZoneOffset.UTC));
            statement.setObject(9, createdAt.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    void complete(
            Connection connection, String idempotencyKey, String state, CanonicalPayload result, Instant completedAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.PROVIDER_VERIFICATION
                SET STATE = ?, RESULT_PAYLOAD = ?, UPDATED_AT = ?
                WHERE IDEMPOTENCY_KEY = ? AND STATE = 'RUNNING'
                """)) {
            statement.setString(1, state);
            statement.setString(2, result.json());
            statement.setObject(3, completedAt.atOffset(ZoneOffset.UTC));
            statement.setString(4, idempotencyKey);
            if (statement.executeUpdate() != 1) {
                throw PersistenceException.idempotencyConflict("Provider 验证意图已被其他终态占用");
            }
        }
    }

    List<StoredVerification> listRunning(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT IDEMPOTENCY_KEY, METHOD_NAME, REQUEST_DIGEST, PROVIDER_ID, PROVIDER_REVISION,
                       MODEL, PURPOSE, STATE, RESULT_PAYLOAD, CREATED_AT, UPDATED_AT
                FROM CORE.PROVIDER_VERIFICATION WHERE STATE = 'RUNNING'
                ORDER BY CREATED_AT, IDEMPOTENCY_KEY
                FOR UPDATE
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                List<StoredVerification> running = new ArrayList<>();
                while (result.next()) {
                    running.add(map(result.getString("IDEMPOTENCY_KEY"), result));
                }
                return List.copyOf(running);
            }
        }
    }

    private static StoredVerification map(String key, ResultSet result) throws SQLException {
        String payload = result.getString("RESULT_PAYLOAD");
        return new StoredVerification(
                key,
                result.getString("METHOD_NAME"),
                result.getString("REQUEST_DIGEST"),
                new ProviderRef(
                        result.getString("PROVIDER_ID"),
                        result.getLong("PROVIDER_REVISION"),
                        result.getString("MODEL")),
                ProviderModelPurpose.valueOf(result.getString("PURPOSE")),
                result.getString("STATE"),
                Optional.ofNullable(payload).map(CanonicalPayload::new),
                result.getObject("CREATED_AT", OffsetDateTime.class).toInstant(),
                result.getObject("UPDATED_AT", OffsetDateTime.class).toInstant());
    }

    record StoredVerification(
            String idempotencyKey,
            String method,
            String requestDigest,
            ProviderRef provider,
            ProviderModelPurpose purpose,
            String state,
            Optional<CanonicalPayload> result,
            Instant createdAt,
            Instant updatedAt) {}
}
