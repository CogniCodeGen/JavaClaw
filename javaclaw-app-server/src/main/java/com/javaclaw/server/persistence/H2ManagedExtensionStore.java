package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.TurnId;
import com.javaclaw.extension.spi.DocumentRevision;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.ManagedExtensionStore;
import com.javaclaw.extension.spi.VersionedDocument;

/**
 * 内置扩展的 H2 托管事务实现。
 *
 * <p>扩展只能操作自身 schema 中的 DOCUMENT，并可与 Core Item、Event、Outbox 原子提交；事务对象不得逸出回调。
 */
public final class H2ManagedExtensionStore implements ManagedExtensionStore {
    private final H2Transactions transactions;
    private final ExtensionSchemaOwner schemas;
    private final TurnRepository turns = new TurnRepository();
    private final ItemRepository items = new ItemRepository(turns);
    private final Clock clock;

    /**
     * 创建托管扩展存储。
     *
     * @param database data-v6 数据库
     * @param clock 平台时钟
     */
    public H2ManagedExtensionStore(H2Database database, Clock clock) {
        H2Database checked = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
        transactions = new H2Transactions(checked);
        schemas = new ExtensionSchemaOwner(checked, clock);
    }

    @Override
    public <T> T inTransaction(ExtensionId extensionId, TransactionWork<T> work) throws Exception {
        Objects.requireNonNull(extensionId, "extensionId");
        Objects.requireNonNull(work, "work");
        String schema = schemas.ensureInitialized(extensionId);
        return transactions.execute(connection -> executeWork(connection, extensionId, schema, work));
    }

    @Override
    public ExtensionResponse inCommand(
            ExtensionId extensionId,
            String operation,
            String idempotencyKey,
            String requestDigest,
            TransactionWork<ExtensionResponse> work)
            throws Exception {
        Objects.requireNonNull(extensionId, "extensionId");
        Objects.requireNonNull(work, "work");
        CommandKey key = new CommandKey(operation, idempotencyKey, requestDigest);
        String schema = schemas.ensureInitialized(extensionId);
        return transactions.execute(connection -> {
            Optional<StoredCommand> stored = findCommand(connection, schema, key.idempotencyKey());
            if (stored.isPresent()) {
                return recoverCommand(key, stored.orElseThrow());
            }
            ExtensionResponse response = executeWork(connection, extensionId, schema, work);
            insertCommand(connection, schema, key, response);
            return response;
        });
    }

    @Override
    public Optional<ExtensionResponse> recoverCommand(
            ExtensionId extensionId, String operation, String idempotencyKey, String requestDigest) throws Exception {
        Objects.requireNonNull(extensionId, "extensionId");
        CommandKey key = new CommandKey(operation, idempotencyKey, requestDigest);
        String schema = schemas.ensureInitialized(extensionId);
        return transactions.execute(connection ->
                findCommand(connection, schema, key.idempotencyKey()).map(stored -> recoverCommand(key, stored)));
    }

    private <T> T executeWork(Connection connection, ExtensionId extensionId, String schema, TransactionWork<T> work)
            throws Exception {
        Transaction transaction = new Transaction(connection, extensionId, schema, items, clock);
        try {
            return work.execute(transaction);
        } finally {
            transaction.close();
        }
    }

    private Optional<StoredCommand> findCommand(Connection connection, String schema, String idempotencyKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT OPERATION, REQUEST_DIGEST, "
                + "RESPONSE_PAYLOAD, RESPONSE_REVISION FROM " + schema + ".COMMAND_RESULT WHERE IDEMPOTENCY_KEY = ?")) {
            statement.setString(1, idempotencyKey);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredCommand(
                        result.getString("OPERATION"),
                        result.getString("REQUEST_DIGEST"),
                        new ExtensionResponse(
                                new CanonicalPayload(result.getString("RESPONSE_PAYLOAD")),
                                result.getLong("RESPONSE_REVISION"))));
            }
        }
    }

    private void insertCommand(Connection connection, String schema, CommandKey key, ExtensionResponse response)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + schema
                + ".COMMAND_RESULT (IDEMPOTENCY_KEY, OPERATION, REQUEST_DIGEST, RESPONSE_PAYLOAD, "
                + "RESPONSE_REVISION, CREATED_AT) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, key.idempotencyKey());
            statement.setString(2, key.operation());
            statement.setString(3, key.requestDigest());
            statement.setString(4, response.payload().json());
            statement.setLong(5, response.revision());
            statement.setObject(6, Instant.now(clock).atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private ExtensionResponse recoverCommand(CommandKey key, StoredCommand stored) {
        if (!key.operation().equals(stored.operation()) || !key.requestDigest().equals(stored.requestDigest())) {
            throw PersistenceException.idempotencyConflict("扩展幂等键已绑定其他请求");
        }
        return stored.response();
    }

    private static final class Transaction implements ExtensionTransaction {
        private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9._-]{0,239}");

        private final Connection connection;
        private final ExtensionId extensionId;
        private final String schema;
        private final ItemRepository items;
        private final Clock clock;
        private boolean active = true;

        private Transaction(
                Connection connection, ExtensionId extensionId, String schema, ItemRepository items, Clock clock) {
            this.connection = connection;
            this.extensionId = extensionId;
            this.schema = schema;
            this.items = items;
            this.clock = clock;
        }

        @Override
        public Optional<VersionedDocument> get(String collection, String key) {
            requireActive();
            String normalizedCollection = name(collection, "collection");
            String normalizedKey = key(key);
            try (PreparedStatement statement = connection.prepareStatement("SELECT REVISION, PAYLOAD, UPDATED_AT FROM "
                    + schema + ".DOCUMENT WHERE COLLECTION_NAME = ? AND DOCUMENT_KEY = ? AND TOMBSTONED = FALSE")) {
                statement.setString(1, normalizedCollection);
                statement.setString(2, normalizedKey);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(map(normalizedKey, result)) : Optional.empty();
                }
            } catch (SQLException failure) {
                throw sqlFailure(failure);
            }
        }

        @Override
        public List<VersionedDocument> list(String collection, String afterKey, int limit) {
            requireActive();
            String normalizedCollection = name(collection, "collection");
            String normalizedAfter = Objects.requireNonNull(afterKey, "afterKey");
            if (limit < 1 || limit > 500) {
                throw new IllegalArgumentException("limit must be between 1 and 500");
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT DOCUMENT_KEY, REVISION, "
                    + "PAYLOAD, UPDATED_AT FROM " + schema
                    + ".DOCUMENT WHERE COLLECTION_NAME = ? AND DOCUMENT_KEY > ? AND TOMBSTONED = FALSE "
                    + "ORDER BY DOCUMENT_KEY LIMIT ?")) {
                statement.setString(1, normalizedCollection);
                statement.setString(2, normalizedAfter);
                statement.setInt(3, limit);
                try (ResultSet result = statement.executeQuery()) {
                    List<VersionedDocument> documents = new ArrayList<>();
                    while (result.next()) {
                        documents.add(map(result.getString("DOCUMENT_KEY"), result));
                    }
                    return List.copyOf(documents);
                }
            } catch (SQLException failure) {
                throw sqlFailure(failure);
            }
        }

        @Override
        public List<DocumentRevision> history(String collection, String key, long afterRevision, int limit) {
            requireActive();
            if (afterRevision < 0 || limit < 1 || limit > 500) {
                throw new IllegalArgumentException("history cursor or limit is invalid");
            }
            String normalizedCollection = name(collection, "collection");
            String normalizedKey = key(key);
            try (PreparedStatement statement = connection.prepareStatement("SELECT REVISION, PAYLOAD, TOMBSTONED, "
                    + "UPDATED_AT FROM " + schema + ".DOCUMENT_HISTORY WHERE COLLECTION_NAME = ? "
                    + "AND DOCUMENT_KEY = ? AND REVISION > ? ORDER BY REVISION LIMIT ?")) {
                statement.setString(1, normalizedCollection);
                statement.setString(2, normalizedKey);
                statement.setLong(3, afterRevision);
                statement.setInt(4, limit);
                try (ResultSet result = statement.executeQuery()) {
                    List<DocumentRevision> revisions = new ArrayList<>();
                    while (result.next()) {
                        revisions.add(mapRevision(normalizedKey, result));
                    }
                    return List.copyOf(revisions);
                }
            } catch (SQLException failure) {
                throw sqlFailure(failure);
            }
        }

        @Override
        public List<DocumentRevision> listTombstones(String collection, String afterKey, int limit) {
            requireActive();
            String normalizedCollection = name(collection, "collection");
            String normalizedAfter = Objects.requireNonNull(afterKey, "afterKey");
            if (limit < 1 || limit > 500) {
                throw new IllegalArgumentException("limit must be between 1 and 500");
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT DOCUMENT_KEY, REVISION, PAYLOAD, "
                    + "TOMBSTONED, UPDATED_AT FROM " + schema + ".DOCUMENT WHERE COLLECTION_NAME = ? "
                    + "AND DOCUMENT_KEY > ? AND TOMBSTONED = TRUE ORDER BY DOCUMENT_KEY LIMIT ?")) {
                statement.setString(1, normalizedCollection);
                statement.setString(2, normalizedAfter);
                statement.setInt(3, limit);
                try (ResultSet result = statement.executeQuery()) {
                    List<DocumentRevision> revisions = new ArrayList<>();
                    while (result.next()) {
                        revisions.add(mapRevision(result.getString("DOCUMENT_KEY"), result));
                    }
                    return List.copyOf(revisions);
                }
            } catch (SQLException failure) {
                throw sqlFailure(failure);
            }
        }

        @Override
        public long put(String collection, String key, long expectedRevision, CanonicalPayload payload) {
            requireActive();
            requireExpectedRevision(expectedRevision);
            String normalizedCollection = name(collection, "collection");
            String normalizedKey = key(key);
            Objects.requireNonNull(payload, "payload");
            try {
                if (expectedRevision == 0) {
                    insert(normalizedCollection, normalizedKey, payload);
                    return 1;
                }
                return update(normalizedCollection, normalizedKey, expectedRevision, payload);
            } catch (SQLException failure) {
                throw sqlFailure(failure);
            }
        }

        @Override
        public void delete(String collection, String key, long expectedRevision) {
            requireActive();
            if (expectedRevision < 1) {
                throw new IllegalArgumentException("delete expectedRevision must be positive");
            }
            String normalizedCollection = name(collection, "collection");
            String normalizedKey = key(key);
            try {
                StoredCurrent current = lockCurrent(normalizedCollection, normalizedKey, expectedRevision);
                if (current.tombstoned()) {
                    throw PersistenceException.revisionConflict("扩展记录已经删除");
                }
                long revision = Math.addExact(expectedRevision, 1);
                OffsetDateTime updatedAt = now();
                try (PreparedStatement statement = connection.prepareStatement("UPDATE " + schema
                        + ".DOCUMENT SET REVISION = ?, TOMBSTONED = TRUE, UPDATED_AT = ? "
                        + "WHERE COLLECTION_NAME = ? AND DOCUMENT_KEY = ? AND REVISION = ?")) {
                    statement.setLong(1, revision);
                    statement.setObject(2, updatedAt);
                    statement.setString(3, normalizedCollection);
                    statement.setString(4, normalizedKey);
                    statement.setLong(5, expectedRevision);
                    requireSingleChange(statement.executeUpdate());
                }
                insertHistory(normalizedCollection, normalizedKey, revision, current.payload(), true, updatedAt);
            } catch (SQLException failure) {
                throw sqlFailure(failure);
            }
        }

        @Override
        public void appendItem(
                TurnId turnId, String kind, String schemaId, CanonicalPayload payload, ItemStatus status) {
            requireActive();
            ItemRepository.ItemWrite write = new ItemRepository.ItemWrite(
                    turnId, kind, schemaId, extensionId.value(), status, payload, Instant.now(clock));
            try {
                items.append(connection, write);
            } catch (SQLException failure) {
                throw sqlFailure(failure);
            }
        }

        @Override
        public void appendEvent(String topic, CanonicalPayload payload) {
            requireActive();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO CORE.EVENT (THREAD_ID, TURN_ID, PRODUCER_ID, TOPIC, PAYLOAD, CREATED_AT)
                    VALUES (NULL, NULL, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, extensionId.value());
                statement.setString(2, name(topic, "topic"));
                statement.setString(
                        3, Objects.requireNonNull(payload, "payload").json());
                statement.setObject(4, now());
                statement.executeUpdate();
            } catch (SQLException failure) {
                throw sqlFailure(failure);
            }
        }

        @Override
        public void enqueueOutbox(String destination, String idempotencyKey, CanonicalPayload payload) {
            requireActive();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO CORE.OUTBOX (
                        ID, DESTINATION, IDEMPOTENCY_KEY, PAYLOAD, STATUS, ATTEMPTS, NEXT_ATTEMPT_AT, CREATED_AT
                    ) VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?)
                    """)) {
                OffsetDateTime now = now();
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, name(destination, "destination"));
                statement.setString(3, key(idempotencyKey));
                statement.setString(
                        4, Objects.requireNonNull(payload, "payload").json());
                statement.setObject(5, now);
                statement.setObject(6, now);
                statement.executeUpdate();
            } catch (SQLException failure) {
                throw sqlFailure(failure);
            }
        }

        private void insert(String collection, String key, CanonicalPayload payload) throws SQLException {
            OffsetDateTime updatedAt = now();
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + schema
                    + ".DOCUMENT (COLLECTION_NAME, DOCUMENT_KEY, REVISION, PAYLOAD, TOMBSTONED, UPDATED_AT) "
                    + "VALUES (?, ?, 1, ?, FALSE, ?)")) {
                statement.setString(1, collection);
                statement.setString(2, key);
                statement.setString(3, payload.json());
                statement.setObject(4, updatedAt);
                statement.executeUpdate();
            }
            insertHistory(collection, key, 1, payload, false, updatedAt);
        }

        private long update(String collection, String key, long expectedRevision, CanonicalPayload payload)
                throws SQLException {
            long revision = Math.addExact(expectedRevision, 1);
            OffsetDateTime updatedAt = now();
            try (PreparedStatement statement = connection.prepareStatement("UPDATE " + schema
                    + ".DOCUMENT SET REVISION = ?, PAYLOAD = ?, TOMBSTONED = FALSE, UPDATED_AT = ? "
                    + "WHERE COLLECTION_NAME = ? AND DOCUMENT_KEY = ? AND REVISION = ?")) {
                statement.setLong(1, revision);
                statement.setString(2, payload.json());
                statement.setObject(3, updatedAt);
                statement.setString(4, collection);
                statement.setString(5, key);
                statement.setLong(6, expectedRevision);
                requireSingleChange(statement.executeUpdate());
            }
            insertHistory(collection, key, revision, payload, false, updatedAt);
            return revision;
        }

        private StoredCurrent lockCurrent(String collection, String key, long expectedRevision) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("SELECT PAYLOAD, TOMBSTONED FROM " + schema
                    + ".DOCUMENT WHERE COLLECTION_NAME = ? AND DOCUMENT_KEY = ? AND REVISION = ? FOR UPDATE")) {
                statement.setString(1, collection);
                statement.setString(2, key);
                statement.setLong(3, expectedRevision);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw PersistenceException.revisionConflict("扩展记录 revision 已改变或记录不存在");
                    }
                    return new StoredCurrent(
                            new CanonicalPayload(result.getString("PAYLOAD")), result.getBoolean("TOMBSTONED"));
                }
            }
        }

        private void insertHistory(
                String collection,
                String key,
                long revision,
                CanonicalPayload payload,
                boolean tombstoned,
                OffsetDateTime updatedAt)
                throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + schema
                    + ".DOCUMENT_HISTORY (COLLECTION_NAME, DOCUMENT_KEY, REVISION, PAYLOAD, TOMBSTONED, UPDATED_AT) "
                    + "VALUES (?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, collection);
                statement.setString(2, key);
                statement.setLong(3, revision);
                statement.setString(4, payload.json());
                statement.setBoolean(5, tombstoned);
                statement.setObject(6, updatedAt);
                statement.executeUpdate();
            }
        }

        private VersionedDocument map(String key, ResultSet result) throws SQLException {
            return new VersionedDocument(
                    key,
                    result.getLong("REVISION"),
                    new CanonicalPayload(result.getString("PAYLOAD")),
                    result.getObject("UPDATED_AT", OffsetDateTime.class).toInstant());
        }

        private DocumentRevision mapRevision(String key, ResultSet result) throws SQLException {
            return new DocumentRevision(
                    key,
                    result.getLong("REVISION"),
                    new CanonicalPayload(result.getString("PAYLOAD")),
                    result.getBoolean("TOMBSTONED"),
                    result.getObject("UPDATED_AT", OffsetDateTime.class).toInstant());
        }

        private void close() {
            active = false;
        }

        private void requireActive() {
            if (!active) {
                throw new IllegalStateException("extension transaction is no longer active");
            }
        }

        private OffsetDateTime now() {
            return Instant.now(clock).atOffset(ZoneOffset.UTC);
        }

        private static void requireExpectedRevision(long revision) {
            if (revision < 0) {
                throw new IllegalArgumentException("expectedRevision must not be negative");
            }
        }

        private static void requireSingleChange(int changed) {
            if (changed != 1) {
                throw PersistenceException.revisionConflict("扩展记录 revision 已改变或记录不存在");
            }
        }

        private static String name(String value, String label) {
            String normalized = Objects.requireNonNull(value, label).strip();
            if (!NAME.matcher(normalized).matches()) {
                throw new IllegalArgumentException(label + " 格式无效");
            }
            return normalized;
        }

        private static String key(String value) {
            String normalized = Objects.requireNonNull(value, "key").strip();
            if (normalized.isEmpty() || normalized.length() > 500) {
                throw new IllegalArgumentException("key 长度必须在 1 到 500 之间");
            }
            return normalized;
        }

        private static PersistenceException sqlFailure(SQLException failure) {
            return new PersistenceException("扩展托管存储操作失败", failure);
        }

        private record StoredCurrent(CanonicalPayload payload, boolean tombstoned) {}
    }

    private record StoredCommand(String operation, String requestDigest, ExtensionResponse response) {}

    private record CommandKey(String operation, String idempotencyKey, String requestDigest) {
        private CommandKey {
            operation = text(operation, "operation", 240);
            idempotencyKey = text(idempotencyKey, "idempotencyKey", 200);
            requestDigest =
                    Objects.requireNonNull(requestDigest, "requestDigest").toLowerCase(java.util.Locale.ROOT);
            if (!requestDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("requestDigest must be SHA-256 hex");
            }
        }

        private static String text(String value, String name, int maximum) {
            String normalized = Objects.requireNonNull(value, name).strip();
            if (normalized.isEmpty() || normalized.length() > maximum) {
                throw new IllegalArgumentException(name + " length is invalid");
            }
            return normalized;
        }
    }
}
