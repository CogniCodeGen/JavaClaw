package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

import com.javaclaw.core.api.ThreadEvent;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.TurnId;

/** Transaction-local Event + Outbox writer shared by journal and interaction repositories. */
final class H2EventWriter {
    private static final int SCHEMA_VERSION = 1;
    private final Clock clock;
    private final ThreadJsonCodec json;

    H2EventWriter(Clock clock, ThreadJsonCodec json) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = Objects.requireNonNull(json, "json");
    }

    ThreadEvent append(
            Connection connection,
            ThreadId threadId,
            TurnId turnId,
            String type,
            Map<String, String> payload,
            String correlationId,
            String causationId)
            throws SQLException {
        // 借用投影更新所在的同一事务连接；Thread 行锁串行分配 sequence，失败时投影、事件和 Outbox 一起回滚。
        long previous;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT last_sequence FROM threads WHERE thread_id = ? FOR UPDATE
                """)) {
            query.setString(1, threadId.value());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    throw new NoSuchElementException("thread not found: " + threadId);
                }
                previous = row.getLong(1);
            }
        }
        long sequence = previous + 1;
        long now = clock.millis();
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE threads SET last_sequence = ?, updated_at = ?
                WHERE thread_id = ? AND last_sequence = ?
                """)) {
            update.setLong(1, sequence);
            update.setLong(2, now);
            update.setString(3, threadId.value());
            update.setLong(4, previous);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("concurrent thread event append: " + threadId);
            }
        }
        ThreadEvent event = new ThreadEvent(
                "evt_" + UUID.randomUUID().toString().replace("-", ""),
                threadId,
                turnId,
                sequence,
                type,
                SCHEMA_VERSION,
                correlationId,
                causationId,
                payload,
                Instant.ofEpochMilli(now));
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO thread_events(
                    thread_id, event_sequence, event_id, turn_id, event_type,
                    schema_version, correlation_id, causation_id, payload_json, timestamp_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, threadId.value());
            insert.setLong(2, sequence);
            insert.setString(3, event.eventId());
            insert.setString(4, turnId == null ? null : turnId.value());
            insert.setString(5, type);
            insert.setInt(6, SCHEMA_VERSION);
            insert.setString(7, correlationId);
            insert.setString(8, causationId);
            insert.setString(9, json.mapValue(payload));
            insert.setLong(10, now);
            insert.executeUpdate();
        }
        try (PreparedStatement outbox = connection.prepareStatement("""
                INSERT INTO event_outbox(
                    thread_id, event_sequence, event_id, envelope_json, created_at, published_at)
                VALUES (?, ?, ?, ?, ?, NULL)
                """)) {
            outbox.setString(1, threadId.value());
            outbox.setLong(2, sequence);
            outbox.setString(3, event.eventId());
            outbox.setString(4, json.event(event));
            outbox.setLong(5, now);
            outbox.executeUpdate();
        }
        return event;
    }
}
