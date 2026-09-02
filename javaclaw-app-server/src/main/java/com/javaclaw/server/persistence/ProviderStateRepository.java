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
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.runtime.ProviderState;

/** Provider opaque state 的行映射和完整性校验。 */
final class ProviderStateRepository {
    void save(
            Connection connection,
            TurnId turnId,
            String modelId,
            ProviderState state,
            long estimatedInputTokens,
            Instant now)
            throws SQLException {
        StatePosition position = position(connection, turnId);
        try (PreparedStatement statement = connection.prepareStatement("""
                MERGE INTO CORE.PROVIDER_STATE (
                    TURN_ID, THREAD_ID, MODEL_ID, PROVIDER_ID, FORMAT, PAYLOAD, PAYLOAD_DIGEST,
                    THROUGH_SEQUENCE, ESTIMATED_INPUT_TOKENS, UPDATED_AT
                ) KEY (TURN_ID) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, turnId.toString());
            statement.setString(2, position.threadId().toString());
            statement.setString(3, modelId);
            statement.setString(4, state.providerId());
            statement.setString(5, state.format());
            statement.setString(6, state.payload().json());
            statement.setString(7, state.digest());
            statement.setLong(8, position.sequence());
            statement.setLong(9, estimatedInputTokens);
            statement.setObject(10, now.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    Optional<StoredProviderState> latest(Connection connection, ThreadId threadId, String modelId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT TURN_ID, MODEL_ID, PROVIDER_ID, FORMAT, PAYLOAD, PAYLOAD_DIGEST,
                       THROUGH_SEQUENCE, ESTIMATED_INPUT_TOKENS, UPDATED_AT
                FROM CORE.PROVIDER_STATE
                WHERE THREAD_ID = ? AND MODEL_ID = ?
                ORDER BY THROUGH_SEQUENCE DESC LIMIT 1
                """)) {
            statement.setString(1, threadId.toString());
            statement.setString(2, modelId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    private StatePosition position(Connection connection, TurnId turnId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT T.THREAD_ID, MAX(I.SEQUENCE) AS THROUGH_SEQUENCE
                FROM CORE.AGENT_TURN T
                JOIN CORE.ITEM I ON I.TURN_ID = T.ID
                WHERE T.ID = ? GROUP BY T.THREAD_ID
                """)) {
            statement.setString(1, turnId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new PersistenceException("Provider state 缺少已提交 Item");
                }
                return new StatePosition(ThreadId.parse(result.getString(1)), result.getLong(2));
            }
        }
    }

    private StoredProviderState map(ResultSet result) throws SQLException {
        CanonicalPayload payload = new CanonicalPayload(result.getString("PAYLOAD"));
        if (!payload.sha256().equals(result.getString("PAYLOAD_DIGEST"))) {
            throw new PersistenceException("Provider state 摘要校验失败");
        }
        ProviderState state = new ProviderState(result.getString("PROVIDER_ID"), result.getString("FORMAT"), payload);
        return new StoredProviderState(
                TurnId.parse(result.getString("TURN_ID")),
                result.getString("MODEL_ID"),
                state,
                result.getLong("THROUGH_SEQUENCE"),
                result.getLong("ESTIMATED_INPUT_TOKENS"),
                result.getObject("UPDATED_AT", OffsetDateTime.class).toInstant());
    }

    record StoredProviderState(
            TurnId turnId,
            String modelId,
            ProviderState state,
            long throughSequence,
            long estimatedInputTokens,
            Instant updatedAt) {}

    private record StatePosition(ThreadId threadId, long sequence) {}
}
