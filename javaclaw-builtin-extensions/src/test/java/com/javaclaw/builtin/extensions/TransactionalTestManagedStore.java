package com.javaclaw.builtin.extensions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.TurnId;
import com.javaclaw.extension.spi.DocumentRevision;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.ManagedExtensionStore;
import com.javaclaw.extension.spi.VersionedDocument;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;

/** 测试托管存储保留与真实端口相同的失败原子性，避免异常路径残留 head 或半批次。 */
class TransactionalTestManagedStore implements ManagedExtensionStore, ExtensionTransaction {
    private final Map<String, NavigableMap<String, VersionedDocument>> collections = new HashMap<>();
    private final Map<String, VersionedDocument> heads = new HashMap<>();
    private final Map<String, List<DocumentRevision>> histories = new HashMap<>();
    private final Map<String, CommandRecord> commands = new HashMap<>();
    private Instant now = NOW;

    @Override
    public synchronized <T> T inTransaction(ExtensionId extensionId, TransactionWork<T> work) throws Exception {
        var snapshot = snapshot();
        try {
            return work.execute(this);
        } catch (Exception | Error failure) {
            restore(snapshot);
            throw failure;
        }
    }

    @Override
    public synchronized ExtensionResponse inCommand(
            ExtensionId extensionId,
            String operation,
            String idempotencyKey,
            String requestDigest,
            TransactionWork<ExtensionResponse> work)
            throws Exception {
        String key = extensionId.value() + ":" + idempotencyKey;
        CommandRecord existing = commands.get(key);
        if (existing != null) {
            existing.require(operation, requestDigest);
            return existing.response();
        }
        return inTransaction(extensionId, transaction -> {
            ExtensionResponse response = work.execute(transaction);
            commands.put(key, new CommandRecord(operation, requestDigest, response));
            return response;
        });
    }

    @Override
    public Optional<ExtensionResponse> recoverCommand(
            ExtensionId extensionId, String operation, String idempotencyKey, String requestDigest) {
        CommandRecord existing = commands.get(extensionId.value() + ":" + idempotencyKey);
        if (existing == null) {
            return Optional.empty();
        }
        existing.require(operation, requestDigest);
        return Optional.of(existing.response());
    }

    @Override
    public Optional<VersionedDocument> get(String collection, String key) {
        return Optional.ofNullable(collection(collection).get(key));
    }

    @Override
    public List<VersionedDocument> list(String collection, String afterKey, int limit) {
        return collection(collection).tailMap(afterKey, false).values().stream()
                .limit(limit)
                .toList();
    }

    @Override
    public List<DocumentRevision> history(String collection, String key, long afterRevision, int limit) {
        return histories.getOrDefault(documentKey(collection, key), List.of()).stream()
                .filter(revision -> revision.revision() > afterRevision)
                .limit(limit)
                .toList();
    }

    @Override
    public List<DocumentRevision> listTombstones(String collection, String afterKey, int limit) {
        return heads.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(collection + "\u0000"))
                .filter(entry -> entry.getValue().key().compareTo(afterKey) > 0)
                .filter(entry ->
                        !collection(collection).containsKey(entry.getValue().key()))
                .sorted(java.util.Map.Entry.comparingByKey())
                .limit(limit)
                .map(entry -> new DocumentRevision(
                        entry.getValue().key(),
                        entry.getValue().revision(),
                        entry.getValue().payload(),
                        true,
                        entry.getValue().updatedAt()))
                .toList();
    }

    @Override
    public long put(String collection, String key, long expectedRevision, CanonicalPayload payload) {
        NavigableMap<String, VersionedDocument> documents = collection(collection);
        long actual = Optional.ofNullable(heads.get(documentKey(collection, key)))
                .map(VersionedDocument::revision)
                .orElse(0L);
        if (actual != expectedRevision) {
            throw new IllegalArgumentException("document revision changed");
        }
        long revision = Math.addExact(actual, 1);
        VersionedDocument document = new VersionedDocument(key, revision, payload, now);
        documents.put(key, document);
        heads.put(documentKey(collection, key), document);
        appendHistory(collection, key, revision, payload, false);
        now = now.plusMillis(1);
        return revision;
    }

    @Override
    public void delete(String collection, String key, long expectedRevision) {
        NavigableMap<String, VersionedDocument> documents = collection(collection);
        VersionedDocument document = heads.get(documentKey(collection, key));
        if (document == null || document.revision() != expectedRevision) {
            throw new IllegalArgumentException("document revision changed");
        }
        documents.remove(key);
        long revision = Math.addExact(expectedRevision, 1);
        heads.put(documentKey(collection, key), new VersionedDocument(key, revision, document.payload(), now));
        appendHistory(collection, key, revision, document.payload(), true);
        now = now.plusMillis(1);
    }

    @Override
    public void appendItem(TurnId turnId, String kind, String schemaId, CanonicalPayload payload, ItemStatus status) {}

    @Override
    public void appendEvent(String topic, CanonicalPayload payload) {}

    @Override
    public void enqueueOutbox(String destination, String idempotencyKey, CanonicalPayload payload) {}

    private NavigableMap<String, VersionedDocument> collection(String name) {
        return collections.computeIfAbsent(name, ignored -> new TreeMap<>());
    }

    private void appendHistory(
            String collection, String key, long revision, CanonicalPayload payload, boolean tombstone) {
        histories
                .computeIfAbsent(documentKey(collection, key), ignored -> new ArrayList<>())
                .add(new DocumentRevision(key, revision, payload, tombstone, now));
    }

    private static String documentKey(String collection, String key) {
        return collection + "\u0000" + key;
    }

    private StoreSnapshot snapshot() {
        Map<String, NavigableMap<String, VersionedDocument>> documents = new HashMap<>();
        collections.forEach((name, values) -> documents.put(name, new TreeMap<>(values)));
        Map<String, List<DocumentRevision>> history = new HashMap<>();
        histories.forEach((name, values) -> history.put(name, new ArrayList<>(values)));
        return new StoreSnapshot(documents, new HashMap<>(heads), history, new HashMap<>(commands), now);
    }

    private void restore(StoreSnapshot snapshot) {
        collections.clear();
        collections.putAll(snapshot.collections());
        heads.clear();
        heads.putAll(snapshot.heads());
        histories.clear();
        histories.putAll(snapshot.histories());
        commands.clear();
        commands.putAll(snapshot.commands());
        now = snapshot.now();
    }

    private record StoreSnapshot(
            Map<String, NavigableMap<String, VersionedDocument>> collections,
            Map<String, VersionedDocument> heads,
            Map<String, List<DocumentRevision>> histories,
            Map<String, CommandRecord> commands,
            Instant now) {}

    private record CommandRecord(String operation, String digest, ExtensionResponse response) {
        private void require(String actualOperation, String actualDigest) {
            if (!operation.equals(actualOperation) || !digest.equals(actualDigest)) {
                throw new IllegalArgumentException("idempotency identity changed");
            }
        }
    }
}
