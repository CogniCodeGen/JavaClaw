package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.TurnId;

/** Turn system prompt 冻结快照的行级存取。 */
final class TurnPromptRepository {
    void insert(Connection connection, TurnId turnId, CanonicalPayload payload) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.TURN_PROMPT_SNAPSHOT (TURN_ID, PAYLOAD, DIGEST)
                VALUES (?, ?, ?)
                """)) {
            statement.setString(1, turnId.toString());
            statement.setString(2, payload.json());
            statement.setString(3, payload.sha256());
            statement.executeUpdate();
        }
    }

    Optional<CanonicalPayload> find(Connection connection, TurnId turnId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT PAYLOAD, DIGEST FROM CORE.TURN_PROMPT_SNAPSHOT WHERE TURN_ID = ?
                """)) {
            statement.setString(1, turnId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                CanonicalPayload payload = new CanonicalPayload(result.getString("PAYLOAD"));
                if (!payload.sha256().equals(result.getString("DIGEST"))) {
                    throw new PersistenceException("Turn Prompt snapshot digest 不匹配");
                }
                return Optional.of(payload);
            }
        }
    }
}
