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

import com.javaclaw.api.TurnId;
import com.javaclaw.extension.spi.ExtensionJobInputWait;

/** Extension Job 与权威 InputRequest 等待关系的 SQL 边界。 */
final class ExtensionJobInputWaitRepository {
    void bind(Connection connection, String jobId, ExtensionJobInputWait wait, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.EXTENSION_JOB_INPUT_WAIT (JOB_ID, REQUEST_ID, TURN_ID, CREATED_AT)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, jobId);
            statement.setString(2, wait.requestId());
            statement.setString(3, wait.turnId().toString());
            statement.setObject(4, at(now));
            statement.executeUpdate();
        }
    }

    Optional<Link> lock(Connection connection, String jobId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT JOB_ID, REQUEST_ID, TURN_ID
                FROM CORE.EXTENSION_JOB_INPUT_WAIT
                WHERE JOB_ID = ?
                FOR UPDATE
                """)) {
            statement.setString(1, jobId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    List<String> listActionable(Connection connection, Instant now, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT W.JOB_ID
                FROM CORE.EXTENSION_JOB_INPUT_WAIT W
                JOIN CORE.INPUT_REQUEST I ON I.ID = W.REQUEST_ID
                JOIN CORE.EXTENSION_JOB J ON J.ID = W.JOB_ID
                WHERE I.STATE IN ('EXPIRED', 'CANCELLED')
                   OR (I.STATE = 'PENDING' AND I.EXPIRES_AT <= ?)
                   OR J.STATE IN ('COMPLETED', 'FAILED', 'CANCELLED')
                ORDER BY W.CREATED_AT, W.JOB_ID
                LIMIT ?
                """)) {
            statement.setObject(1, at(now));
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (result.next()) {
                    ids.add(result.getString("JOB_ID"));
                }
                return List.copyOf(ids);
            }
        }
    }

    void delete(Connection connection, String jobId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM CORE.EXTENSION_JOB_INPUT_WAIT WHERE JOB_ID = ?
                """)) {
            statement.setString(1, jobId);
            statement.executeUpdate();
        }
    }

    private static Link map(ResultSet result) throws SQLException {
        return new Link(
                result.getString("JOB_ID"), result.getString("REQUEST_ID"), TurnId.parse(result.getString("TURN_ID")));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    record Link(String jobId, String requestId, TurnId turnId) {}
}
