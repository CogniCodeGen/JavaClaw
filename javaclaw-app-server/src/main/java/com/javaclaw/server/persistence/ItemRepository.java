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
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;

/** Item 的 sequence 分配、持久化和行映射。 */
final class ItemRepository {
    private final TurnRepository turns;

    ItemRepository(TurnRepository turns) {
        this.turns = turns;
    }

    Optional<ItemEnvelope> find(Connection connection, ItemId id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, TURN_ID, SEQUENCE, KIND, SCHEMA_ID, PRODUCER_ID, STATUS, PAYLOAD, CREATED_AT, COMPLETED_AT
                FROM CORE.ITEM WHERE ID = ?
                """)) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    ItemEnvelope append(Connection connection, ItemWrite write) throws SQLException {
        return append(connection, write, ItemId.random());
    }

    ItemEnvelope append(Connection connection, ItemWrite write, ItemId itemId) throws SQLException {
        ThreadId threadId = turns.threadId(connection, write.turnId());
        long sequence = reserveSequence(connection, threadId);
        Optional<Instant> completedAt =
                write.status() == ItemStatus.IN_PROGRESS ? Optional.empty() : Optional.of(write.createdAt());
        ItemEnvelope item = new ItemEnvelope(
                itemId,
                write.turnId(),
                sequence,
                write.kind(),
                write.schemaId(),
                write.producerId(),
                write.status(),
                write.payload(),
                write.createdAt(),
                completedAt);
        insert(connection, threadId, item);
        return item;
    }

    List<ItemEnvelope> listByThread(Connection connection, ThreadId threadId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, TURN_ID, SEQUENCE, KIND, SCHEMA_ID, PRODUCER_ID, STATUS, PAYLOAD, CREATED_AT, COMPLETED_AT
                FROM CORE.ITEM WHERE THREAD_ID = ? ORDER BY SEQUENCE
                """)) {
            statement.setString(1, threadId.toString());
            try (ResultSet result = statement.executeQuery()) {
                List<ItemEnvelope> items = new ArrayList<>();
                while (result.next()) {
                    items.add(map(result));
                }
                return List.copyOf(items);
            }
        }
    }

    List<ItemEnvelope> listPage(Connection connection, ThreadId threadId, long afterSequence, int limit)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, TURN_ID, SEQUENCE, KIND, SCHEMA_ID, PRODUCER_ID, STATUS, PAYLOAD, CREATED_AT, COMPLETED_AT
                FROM CORE.ITEM WHERE THREAD_ID = ? AND SEQUENCE > ? ORDER BY SEQUENCE LIMIT ?
                """)) {
            statement.setString(1, threadId.toString());
            statement.setLong(2, afterSequence);
            statement.setInt(3, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<ItemEnvelope> values = new ArrayList<>();
                while (result.next()) {
                    values.add(map(result));
                }
                return List.copyOf(values);
            }
        }
    }

    com.javaclaw.api.ItemHistoryResult history(
            Connection connection, com.javaclaw.protocol.TurnStreamRpcContracts.ItemHistoryRequest request)
            throws SQLException {
        return new ItemHistoryRepository().history(connection, request);
    }

    List<ItemEnvelope> listByTurnAndSchema(Connection connection, TurnId turnId, String schemaId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, TURN_ID, SEQUENCE, KIND, SCHEMA_ID, PRODUCER_ID, STATUS, PAYLOAD, CREATED_AT, COMPLETED_AT
                FROM CORE.ITEM WHERE TURN_ID = ? AND SCHEMA_ID = ? ORDER BY SEQUENCE
                """)) {
            statement.setString(1, turnId.toString());
            statement.setString(2, schemaId);
            try (ResultSet result = statement.executeQuery()) {
                List<ItemEnvelope> items = new ArrayList<>();
                while (result.next()) {
                    items.add(map(result));
                }
                return List.copyOf(items);
            }
        }
    }

    private long reserveSequence(Connection connection, ThreadId threadId) throws SQLException {
        long sequence;
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT NEXT_SEQUENCE FROM CORE.AGENT_THREAD WHERE ID = ? FOR UPDATE")) {
            statement.setString(1, threadId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new PersistenceException("Thread 不存在");
                }
                sequence = result.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.AGENT_THREAD SET NEXT_SEQUENCE = NEXT_SEQUENCE + 1, REVISION = REVISION + 1
                WHERE ID = ? AND NEXT_SEQUENCE = ?
                """)) {
            statement.setString(1, threadId.toString());
            statement.setLong(2, sequence);
            if (statement.executeUpdate() != 1) {
                throw new PersistenceException("Thread sequence 已被并发修改");
            }
        }
        return sequence;
    }

    private void insert(Connection connection, ThreadId threadId, ItemEnvelope item) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.ITEM (
                    ID, THREAD_ID, TURN_ID, SEQUENCE, KIND, SCHEMA_ID, PRODUCER_ID, STATUS,
                    PAYLOAD, CREATED_AT, COMPLETED_AT
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, item.id().toString());
            statement.setString(2, threadId.toString());
            statement.setString(3, item.turnId().toString());
            statement.setLong(4, item.sequence());
            statement.setString(5, item.kind());
            statement.setString(6, item.schemaId());
            statement.setString(7, item.producerId());
            statement.setString(8, item.status().name());
            statement.setString(9, item.payload().json());
            statement.setObject(10, at(item.createdAt()));
            statement.setObject(11, item.completedAt().map(ItemRepository::at).orElse(null));
            statement.executeUpdate();
        }
    }

    private ItemEnvelope map(ResultSet result) throws SQLException {
        OffsetDateTime completedAt = result.getObject("COMPLETED_AT", OffsetDateTime.class);
        return new ItemEnvelope(
                ItemId.parse(result.getString("ID")),
                TurnId.parse(result.getString("TURN_ID")),
                result.getLong("SEQUENCE"),
                result.getString("KIND"),
                result.getString("SCHEMA_ID"),
                result.getString("PRODUCER_ID"),
                ItemStatus.valueOf(result.getString("STATUS")),
                new CanonicalPayload(result.getString("PAYLOAD")),
                instant(result, "CREATED_AT"),
                completedAt == null ? Optional.empty() : Optional.of(completedAt.toInstant()));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toInstant();
    }

    record ItemWrite(
            TurnId turnId,
            String kind,
            String schemaId,
            String producerId,
            ItemStatus status,
            CanonicalPayload payload,
            Instant createdAt) {}
}
