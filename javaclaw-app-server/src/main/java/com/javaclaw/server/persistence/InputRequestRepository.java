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
import com.javaclaw.api.InputRequest;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.api.InputRequestState;
import com.javaclaw.api.TurnId;

/** InputRequest 的 SQL、乐观锁更新与行映射。 */
final class InputRequestRepository {
    void insert(Connection connection, InputRequest request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.INPUT_REQUEST (
                    ID, TURN_ID, PRODUCER_ID, PROMPT, RESPONSE_SCHEMA, STATE, REVISION,
                    RESPONSE_PAYLOAD, RESOLUTION_REASON, CREATED_AT, EXPIRES_AT, UPDATED_AT
                ) VALUES (?, ?, ?, ?, ?, 'PENDING', 1, NULL, NULL, ?, ?, ?)
                """)) {
            statement.setString(1, request.id());
            statement.setString(2, request.turnId().toString());
            statement.setString(3, request.producerId());
            statement.setString(4, request.prompt());
            statement.setString(5, request.responseSchema().json());
            statement.setObject(6, at(request.createdAt()));
            statement.setObject(7, at(request.expiresAt()));
            statement.setObject(8, at(request.createdAt()));
            statement.executeUpdate();
        }
    }

    Optional<InputRequestRecord> find(Connection connection, String id) throws SQLException {
        return find(connection, id, false);
    }

    Optional<InputRequestRecord> lock(Connection connection, String id) throws SQLException {
        return find(connection, id, true);
    }

    List<InputRequestRecord> list(Connection connection, Optional<TurnId> turnId, boolean includeResolved)
            throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM CORE.INPUT_REQUEST WHERE 1 = 1");
        turnId.ifPresent(ignored -> sql.append(" AND TURN_ID = ?"));
        if (!includeResolved) {
            sql.append(" AND STATE = 'PENDING'");
        }
        sql.append(" ORDER BY CREATED_AT, ID");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            if (turnId.isPresent()) {
                statement.setString(1, turnId.orElseThrow().toString());
            }
            try (ResultSet result = statement.executeQuery()) {
                List<InputRequestRecord> records = new ArrayList<>();
                while (result.next()) {
                    records.add(map(result));
                }
                return List.copyOf(records);
            }
        }
    }

    InputRequestRecord resolve(
            Connection connection,
            String id,
            long expectedRevision,
            InputRequestState state,
            Optional<CanonicalPayload> response,
            Optional<String> reason,
            Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.INPUT_REQUEST
                SET STATE = ?, REVISION = REVISION + 1, RESPONSE_PAYLOAD = ?, RESOLUTION_REASON = ?, UPDATED_AT = ?
                WHERE ID = ? AND REVISION = ? AND STATE = 'PENDING'
                """)) {
            statement.setString(1, state.name());
            statement.setString(2, response.map(CanonicalPayload::json).orElse(null));
            statement.setString(3, reason.orElse(null));
            statement.setObject(4, at(now));
            statement.setString(5, id);
            statement.setLong(6, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw PersistenceException.revisionConflict("输入请求 revision 或状态已经改变");
            }
        }
        return find(connection, id).orElseThrow();
    }

    private Optional<InputRequestRecord> find(Connection connection, String id, boolean lock) throws SQLException {
        String suffix = lock ? " FOR UPDATE" : "";
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT * FROM CORE.INPUT_REQUEST WHERE ID = ?" + suffix)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    private InputRequestRecord map(ResultSet result) throws SQLException {
        String response = result.getString("RESPONSE_PAYLOAD");
        String reason = result.getString("RESOLUTION_REASON");
        InputRequest request = new InputRequest(
                result.getString("ID"),
                TurnId.parse(result.getString("TURN_ID")),
                result.getString("PRODUCER_ID"),
                result.getString("PROMPT"),
                new CanonicalPayload(result.getString("RESPONSE_SCHEMA")),
                instant(result, "CREATED_AT"),
                instant(result, "EXPIRES_AT"));
        return new InputRequestRecord(
                request,
                InputRequestState.valueOf(result.getString("STATE")),
                result.getLong("REVISION"),
                response == null ? Optional.empty() : Optional.of(new CanonicalPayload(response)),
                Optional.ofNullable(reason),
                instant(result, "UPDATED_AT"));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toInstant();
    }
}
