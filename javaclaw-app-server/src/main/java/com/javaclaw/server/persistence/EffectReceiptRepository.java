package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.EffectReceipt;
import com.javaclaw.api.TurnId;

/** 已提交副作用的持久化身份与结果。 */
final class EffectReceiptRepository {
    void insert(
            Connection connection,
            TurnId turnId,
            CorePayloads.ToolCall call,
            EffectReceipt receipt,
            CanonicalPayload resultPayload,
            Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.EFFECT_RECEIPT (
                    IDEMPOTENCY_KEY, TURN_ID, CALL_ID, PRODUCER_ID, TOOL_NAME, TOOL_REVISION,
                    REQUEST_DIGEST, RESULT_DIGEST, RESULT_PAYLOAD, CREATED_AT
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, receipt.idempotencyKey());
            statement.setString(2, turnId.toString());
            statement.setString(3, call.callId());
            statement.setString(4, call.producerId());
            statement.setString(5, call.toolName());
            statement.setLong(6, call.toolRevision());
            statement.setString(7, receipt.requestDigest());
            statement.setString(8, receipt.resultDigest());
            statement.setString(9, resultPayload.json());
            statement.setObject(10, now.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    Optional<StoredEffect> find(Connection connection, String idempotencyKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT TURN_ID, CALL_ID, PRODUCER_ID, TOOL_NAME, TOOL_REVISION,
                       REQUEST_DIGEST, RESULT_DIGEST, RESULT_PAYLOAD, CREATED_AT
                FROM CORE.EFFECT_RECEIPT WHERE IDEMPOTENCY_KEY = ?
                """)) {
            statement.setString(1, idempotencyKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(idempotencyKey, result)) : Optional.empty();
            }
        }
    }

    private StoredEffect map(String idempotencyKey, ResultSet result) throws SQLException {
        return new StoredEffect(
                idempotencyKey,
                TurnId.parse(result.getString("TURN_ID")),
                result.getString("CALL_ID"),
                result.getString("PRODUCER_ID"),
                result.getString("TOOL_NAME"),
                result.getLong("TOOL_REVISION"),
                result.getString("REQUEST_DIGEST"),
                result.getString("RESULT_DIGEST"),
                new CanonicalPayload(result.getString("RESULT_PAYLOAD")),
                result.getObject("CREATED_AT", OffsetDateTime.class).toInstant());
    }

    record StoredEffect(
            String idempotencyKey,
            TurnId turnId,
            String callId,
            String producerId,
            String toolName,
            long toolRevision,
            String requestDigest,
            String resultDigest,
            CanonicalPayload resultPayload,
            Instant createdAt) {}
}
