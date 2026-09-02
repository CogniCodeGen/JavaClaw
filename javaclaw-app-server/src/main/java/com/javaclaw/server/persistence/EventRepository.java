package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;

/** Core Event 的只追加写入 SQL。 */
final class EventRepository {
    void append(
            Connection connection,
            ThreadId threadId,
            TurnId turnId,
            String producerId,
            String topic,
            CanonicalPayload payload,
            Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.EVENT (THREAD_ID, TURN_ID, PRODUCER_ID, TOPIC, PAYLOAD, CREATED_AT)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, threadId.toString());
            statement.setString(2, turnId.toString());
            statement.setString(3, producerId);
            statement.setString(4, topic);
            statement.setString(5, payload.json());
            statement.setObject(6, now.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }
}
