package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.javaclaw.api.CanonicalPayload;

/** Extension Job 推进 Outbox 的领取、确认与崩溃恢复。 */
final class ExtensionJobOutboxRepository {
    static final String DESTINATION = "extension-job.advance";

    void enqueue(Connection connection, String idempotencyKey, CanonicalPayload payload, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.OUTBOX (
                    ID, DESTINATION, IDEMPOTENCY_KEY, PAYLOAD, STATUS, ATTEMPTS, NEXT_ATTEMPT_AT, CREATED_AT
                ) VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?)
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, DESTINATION);
            statement.setString(3, idempotencyKey);
            statement.setString(4, payload.json());
            statement.setObject(5, at(now));
            statement.setObject(6, at(now));
            statement.executeUpdate();
        }
    }

    Optional<Delivery> claim(Connection connection, Instant now) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT ID, PAYLOAD FROM CORE.OUTBOX
                WHERE DESTINATION = ? AND STATUS = 'PENDING' AND NEXT_ATTEMPT_AT <= ?
                ORDER BY CREATED_AT, ID LIMIT 1 FOR UPDATE
                """)) {
            select.setString(1, DESTINATION);
            select.setObject(2, at(now));
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                Delivery delivery =
                        new Delivery(result.getString("ID"), new CanonicalPayload(result.getString("PAYLOAD")));
                markProcessing(connection, delivery.id());
                return Optional.of(delivery);
            }
        }
    }

    void delivered(Connection connection, String id) throws SQLException {
        updateStatus(connection, id, "PROCESSING", "DELIVERED", null);
    }

    void retry(Connection connection, String id, Instant nextAttemptAt) throws SQLException {
        updateStatus(connection, id, "PROCESSING", "PENDING", at(nextAttemptAt));
    }

    int recoverProcessing(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.OUTBOX SET STATUS = 'PENDING'
                WHERE DESTINATION = ? AND STATUS = 'PROCESSING'
                """)) {
            statement.setString(1, DESTINATION);
            return statement.executeUpdate();
        }
    }

    private void markProcessing(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.OUTBOX SET STATUS = 'PROCESSING', ATTEMPTS = ATTEMPTS + 1
                WHERE ID = ? AND STATUS = 'PENDING'
                """)) {
            statement.setString(1, id);
            requireOne(statement.executeUpdate());
        }
    }

    private void updateStatus(
            Connection connection, String id, String expected, String next, OffsetDateTime nextAttemptAt)
            throws SQLException {
        String sql = nextAttemptAt == null
                ? "UPDATE CORE.OUTBOX SET STATUS = ? WHERE ID = ? AND STATUS = ?"
                : "UPDATE CORE.OUTBOX SET STATUS = ?, NEXT_ATTEMPT_AT = ? WHERE ID = ? AND STATUS = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, next);
            int offset = 2;
            if (nextAttemptAt != null) {
                statement.setObject(offset++, nextAttemptAt);
            }
            statement.setString(offset++, id);
            statement.setString(offset, expected);
            requireOne(statement.executeUpdate());
        }
    }

    private static void requireOne(int changed) {
        if (changed != 1) {
            throw PersistenceException.revisionConflict("Job Outbox 状态已经改变");
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    record Delivery(String id, CanonicalPayload payload) {}
}
