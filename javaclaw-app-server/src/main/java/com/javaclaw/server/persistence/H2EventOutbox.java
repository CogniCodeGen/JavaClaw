package com.javaclaw.server.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.javaclaw.agent.runtime.persistence.EventOutbox;
import com.javaclaw.core.api.ThreadEvent;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.TurnId;

/** Read/ack view of events atomically produced by H2ThreadJournal. */
public final class H2EventOutbox implements EventOutbox {
    private final H2Database database;
    private final Clock clock;
    private final ThreadJsonCodec json = new ThreadJsonCodec();

    H2EventOutbox(H2Database database, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<ThreadEvent> unpublishedEvents(int limit) {
        int boundedLimit = Math.max(1, Math.min(10_000, limit));
        return database.query(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT e.* FROM event_outbox o
                    JOIN thread_events e
                      ON e.thread_id = o.thread_id
                     AND e.event_sequence = o.event_sequence
                    WHERE o.published_at IS NULL
                    ORDER BY o.created_at, o.thread_id, o.event_sequence
                    LIMIT ?
                    """)) {
                statement.setInt(1, boundedLimit);
                try (ResultSet rows = statement.executeQuery()) {
                    ArrayList<ThreadEvent> result = new ArrayList<>();
                    while (rows.next()) {
                        result.add(readEvent(rows));
                    }
                    return List.copyOf(result);
                }
            }
        });
    }

    @Override
    public boolean markEventPublished(ThreadId id, long sequence) {
        Objects.requireNonNull(id, "id");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        return database.transaction(connection -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE event_outbox SET published_at = ?
                    WHERE thread_id = ? AND event_sequence = ? AND published_at IS NULL
                    """)) {
                statement.setLong(1, clock.millis());
                statement.setString(2, id.value());
                statement.setLong(3, sequence);
                return statement.executeUpdate() == 1;
            }
        });
    }

    private ThreadEvent readEvent(ResultSet row) throws SQLException {
        String turnId = row.getString("turn_id");
        return new ThreadEvent(
                row.getString("event_id"),
                new ThreadId(row.getString("thread_id")),
                turnId == null ? null : new TurnId(turnId),
                row.getLong("event_sequence"),
                row.getString("event_type"),
                row.getInt("schema_version"),
                row.getString("correlation_id"),
                row.getString("causation_id"),
                json.mapValue(row.getString("payload_json")),
                Instant.ofEpochMilli(row.getLong("timestamp_ms")));
    }
}
