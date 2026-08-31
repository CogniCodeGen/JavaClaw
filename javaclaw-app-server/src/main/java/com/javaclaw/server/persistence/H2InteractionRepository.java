package com.javaclaw.server.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.agent.runtime.persistence.InteractionRepository;
import com.javaclaw.core.api.ApprovalResolution;
import com.javaclaw.core.api.UserInputResolution;

/** Durable approval and user-input resolution adapter. */
public final class H2InteractionRepository implements InteractionRepository {
    private final H2Database database;
    private final Clock clock;
    private final ThreadJsonCodec json = new ThreadJsonCodec();
    private final H2EventWriter events;

    H2InteractionRepository(H2Database database, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
        events = new H2EventWriter(clock, json);
    }

    @Override
    public Optional<ApprovalResolution> resolveApproval(String id, boolean approved) {
        Objects.requireNonNull(id, "id");
        return database.transaction(connection -> {
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT * FROM approvals WHERE approval_id = ? FOR UPDATE
                    """)) {
                query.setString(1, id);
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next() || !"PENDING".equals(row.getString("state"))) {
                        return Optional.empty();
                    }
                    var threadId = new com.javaclaw.core.api.ThreadId(row.getString("thread_id"));
                    var turnId = new com.javaclaw.core.api.TurnId(row.getString("turn_id"));
                    long now = clock.millis();
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE approvals SET state = ?, response_json = ?, resolved_at = ?
                            WHERE approval_id = ? AND state = 'PENDING'
                            """)) {
                        update.setString(1, approved ? "APPROVED" : "DENIED");
                        update.setString(2, json.mapValue(Map.of("approved", Boolean.toString(approved))));
                        update.setLong(3, now);
                        update.setString(4, id);
                        if (update.executeUpdate() != 1) {
                            return Optional.empty();
                        }
                    }
                    events.append(
                            connection,
                            threadId,
                            turnId,
                            "approval/resolved",
                            Map.of("approvalId", id, "approved", Boolean.toString(approved)),
                            id,
                            null);
                    return Optional.of(
                            new ApprovalResolution(id, threadId, turnId, approved, Instant.ofEpochMilli(now)));
                }
            }
        });
    }

    @Override
    public Optional<UserInputResolution> resolveUserInput(String id, String value, boolean cancelled) {
        String requestId = Objects.requireNonNull(id, "id").strip();
        if (requestId.isEmpty()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        String responseValue = value == null ? "" : value;
        return database.transaction(connection -> {
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT * FROM user_input_requests WHERE request_id = ? FOR UPDATE
                    """)) {
                query.setString(1, requestId);
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next() || !"PENDING".equals(row.getString("state"))) {
                        return Optional.empty();
                    }
                    var threadId = new com.javaclaw.core.api.ThreadId(row.getString("thread_id"));
                    var turnId = new com.javaclaw.core.api.TurnId(row.getString("turn_id"));
                    long now = clock.millis();
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE user_input_requests
                            SET state = ?, response_json = ?, resolved_at = ?
                            WHERE request_id = ? AND state = 'PENDING'
                            """)) {
                        update.setString(1, cancelled ? "CANCELLED" : "RESOLVED");
                        update.setString(
                                2, json.mapValue(Map.of("accepted", "true", "cancelled", Boolean.toString(cancelled))));
                        update.setLong(3, now);
                        update.setString(4, requestId);
                        if (update.executeUpdate() != 1) {
                            return Optional.empty();
                        }
                    }
                    events.append(
                            connection,
                            threadId,
                            turnId,
                            "userInput/resolved",
                            Map.of("requestId", requestId, "cancelled", Boolean.toString(cancelled)),
                            requestId,
                            null);
                    return Optional.of(new UserInputResolution(
                            requestId, threadId, turnId, responseValue, cancelled, Instant.ofEpochMilli(now)));
                }
            }
        });
    }
}
