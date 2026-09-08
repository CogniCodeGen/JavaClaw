package com.javaclaw.builtin.extensions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.MemoryGraphContracts;
import com.javaclaw.builtin.contracts.MemoryV3Contracts;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.VersionedDocument;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

/** 与 Memory 写入同事务更新的结构化投影；头版本先锁定，人工关系不经模型生成路径写入。 */
final class MemoryGraphProjection {
    private final ExtensionPayloadCodec payloads;
    private final MemoryStoreAccess store;
    private final MemorySemantics semantics;

    MemoryGraphProjection(ExtensionPayloadCodec payloads, MemoryStoreAccess store) {
        this.payloads = payloads;
        this.store = store;
        semantics = new MemorySemantics(payloads, store);
    }

    List<ExtensionContribution> contributions() {
        return List.of(
                new ExtensionContributions.Query(
                        "memory.graph.projection", Set.of("graph/entity", "view.graph.projection"), this::query),
                new ExtensionContributions.Command(
                        "memory.graph.relation", Set.of("graph/relation/confirm"), this::confirm));
    }

    void project(ExtensionTransaction transaction, WorkspaceId workspaceId, MemoryContracts.Memory memory) {
        var entity = entity(transaction, workspaceId, memory);
        put(transaction, entities(workspaceId), memory.id(), entity);
        var version = new MemoryGraphContracts.EntityVersion(memory.id(), memory.revision());
        String statementId = "statement-" + memory.id();
        long revision = revision(transaction, assertions(workspaceId), statementId);
        put(
                transaction,
                assertions(workspaceId),
                statementId,
                new MemoryGraphContracts.Assertion(
                        statementId, revision + 1, version, "REMEMBERS", version, memory.content()));
        if (memory.source().isPresent()) {
            var source = memory.source().orElseThrow();
            put(
                    transaction,
                    evidence(workspaceId),
                    memory.id(),
                    new MemoryGraphContracts.EvidenceReference(
                            memory.id(),
                            memory.id(),
                            memory.revision(),
                            source.workspaceId(),
                            source.threadId(),
                            source.itemId(),
                            digest(source.verbatim())));
        }
    }

    private ExtensionResponse query(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        if ("view.graph.projection".equals(request.operation())) {
            return view(request, context);
        }
        var key = payloads.decode(request.payload(), MemoryContracts.Key.class);
        return context.managedStore().inTransaction(MemoryStoreAccess.ID, transaction -> {
            var memory =
                    store.requireMemory(transaction, MemoryCollectionNames.memories(request.workspaceId()), key.id());
            var entity = transaction
                    .get(entities(request.workspaceId()), memory.id())
                    .map(value -> payloads.decode(value.payload(), MemoryGraphContracts.Entity.class))
                    .orElseGet(() -> entity(transaction, request.workspaceId(), memory));
            var references = transaction.get(evidence(request.workspaceId()), memory.id()).stream()
                    .map(value -> payloads.decode(value.payload(), MemoryGraphContracts.EvidenceReference.class))
                    .filter(value -> value.memoryRevision() == memory.revision())
                    .toList();
            var statements = currentAssertions(
                            transaction, request.workspaceId(), context.clock().instant())
                    .stream()
                    .filter(value -> value.subject().id().equals(key.id())
                            || value.object().id().equals(key.id()))
                    .toList();
            long head = semantics.head(transaction, request.workspaceId());
            return new ExtensionResponse(
                    payloads.encode(new MemoryGraphContracts.Projection(entity, statements, references, head)), head);
        });
    }

    private ExtensionResponse view(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        var query = payloads.decode(request.payload(), ViewQueryRequest.class);
        if (!query.arguments().keySet().equals(Set.of("id", "revision"))
                || !query.cursor().isEmpty()) {
            throw new IllegalArgumentException("graph projection requires exact selected Memory");
        }
        return context.managedStore().inTransaction(MemoryStoreAccess.ID, transaction -> {
            var memory = store.requireMemory(
                    transaction,
                    MemoryCollectionNames.memories(request.workspaceId()),
                    query.arguments().get("id"));
            if (memory.revision() != Long.parseLong(query.arguments().get("revision"))) {
                throw new IllegalArgumentException("graph selection changed");
            }
            String detail = detail(
                    transaction, request.workspaceId(), memory, context.clock().instant());
            long head = semantics.head(transaction, request.workspaceId());
            var result = new ViewQueryResult(
                    query.dataSourceId(), List.of(), payloads.encode(Map.of("detail", detail)), "", false, head);
            return new ExtensionResponse(payloads.encode(result), head);
        });
    }

    private String detail(
            ExtensionTransaction transaction, WorkspaceId workspaceId, MemoryContracts.Memory memory, Instant now) {
        StringBuilder detail = new StringBuilder("实体 ID：").append(memory.id()).append("\n\n");
        for (var assertion : currentAssertions(transaction, workspaceId, now)) {
            if (!"REMEMBERS".equals(assertion.predicate())
                    && (assertion.subject().id().equals(memory.id())
                            || assertion.object().id().equals(memory.id()))) {
                detail.append(assertion.subject().id())
                        .append(" → ")
                        .append(assertion.predicate())
                        .append(" → ")
                        .append(assertion.object().id())
                        .append("\n\n");
            }
        }
        memory.source()
                .ifPresent(source -> detail.append("原文证据 Item：")
                        .append(source.itemId())
                        .append("\n\n")
                        .append(source.verbatim()));
        return detail.toString();
    }

    private ExtensionResponse confirm(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        var input = payloads.decode(request.payload(), MemoryGraphContracts.ConfirmRelation.class);
        return context.managedStore()
                .inCommand(
                        MemoryStoreAccess.ID,
                        request.operation(),
                        request.idempotencyKey().orElseThrow(),
                        payloads.encode(Map.of("input", input, "revision", request.expectedRevision()))
                                .sha256(),
                        transaction -> {
                            long head = semantics.lock(transaction, request.workspaceId(), request.expectedRevision());
                            var source = requireActive(transaction, context, input.source());
                            var target = requireActive(transaction, context, input.target());
                            String id = "relation-" + payloads.encode(input).sha256();
                            long revision = revision(transaction, assertions(request.workspaceId()), id);
                            var assertion = new MemoryGraphContracts.Assertion(
                                    id,
                                    revision + 1,
                                    new MemoryGraphContracts.EntityVersion(source.id(), source.revision()),
                                    input.predicate(),
                                    new MemoryGraphContracts.EntityVersion(target.id(), target.revision()),
                                    "");
                            put(transaction, assertions(request.workspaceId()), id, assertion);
                            return new ExtensionResponse(payloads.encode(assertion), head);
                        });
    }

    private MemoryContracts.Memory requireActive(
            ExtensionTransaction transaction, ExtensionExecutionContext context, String id) {
        var memory = store.requireMemory(transaction, MemoryCollectionNames.memories(context.workspaceId()), id);
        if (semantics.effectivity(transaction, context.workspaceId(), id).state()
                != MemoryV3Contracts.SemanticState.ACTIVE) {
            throw new IllegalArgumentException("superseded records cannot receive new confirmed relations");
        }
        return memory;
    }

    List<MemoryGraphContracts.Assertion> currentAssertions(
            ExtensionTransaction transaction, WorkspaceId workspaceId, Instant now) {
        return MemoryStoreAccess.all(transaction, assertions(workspaceId)).stream()
                .map(value -> payloads.decode(value.payload(), MemoryGraphContracts.Assertion.class))
                .filter(value -> current(transaction, workspaceId, value.subject(), now)
                        && current(transaction, workspaceId, value.object(), now))
                .toList();
    }

    private boolean current(
            ExtensionTransaction transaction,
            WorkspaceId workspaceId,
            MemoryGraphContracts.EntityVersion version,
            Instant now) {
        return transaction
                        .get(MemoryCollectionNames.memories(workspaceId), version.id())
                        .map(value -> value.revision() == version.memoryRevision())
                        .orElse(false)
                && semantics.effectivity(transaction, workspaceId, version.id()).effectiveAt(now);
    }

    void restore(ExtensionExecutionContext context) throws Exception {
        String after = "";
        while (true) {
            String cursor = after;
            var page = context.managedStore().inTransaction(MemoryStoreAccess.ID, transaction -> {
                semantics.lock(transaction, context.workspaceId());
                var documents = transaction.list(MemoryCollectionNames.memories(context.workspaceId()), cursor, 200);
                var stale = documents.stream()
                        .map(value -> payloads.decode(value.payload(), MemoryContracts.Memory.class))
                        .filter(memory -> transaction
                                .get(entities(context.workspaceId()), memory.id())
                                .map(value -> payloads.decode(value.payload(), MemoryGraphContracts.Entity.class)
                                                .memoryRevision()
                                        != memory.revision())
                                .orElse(true))
                        .toList();
                if (!stale.isEmpty()) {
                    stale.forEach(memory -> project(transaction, context.workspaceId(), memory));
                }
                return documents;
            });
            if (page.size() < 200) {
                return;
            }
            after = page.getLast().key();
        }
    }

    private MemoryGraphContracts.Entity entity(
            ExtensionTransaction transaction, WorkspaceId workspaceId, MemoryContracts.Memory memory) {
        return new MemoryGraphContracts.Entity(
                memory.id(),
                revision(transaction, entities(workspaceId), memory.id()) + 1,
                memory.revision(),
                MemoryGraphLabels.label(memory.content()));
    }

    private void put(ExtensionTransaction transaction, String collection, String id, Object value) {
        transaction.put(collection, id, revision(transaction, collection, id), payloads.encode(value));
    }

    private static long revision(ExtensionTransaction transaction, String collection, String id) {
        return transaction.get(collection, id).map(VersionedDocument::revision).orElse(0L);
    }

    private static String entities(WorkspaceId workspaceId) {
        return "graph-entities." + workspaceId;
    }

    private static String assertions(WorkspaceId workspaceId) {
        return "graph-assertions." + workspaceId;
    }

    private static String evidence(WorkspaceId workspaceId) {
        return "graph-evidence." + workspaceId;
    }

    private static String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
