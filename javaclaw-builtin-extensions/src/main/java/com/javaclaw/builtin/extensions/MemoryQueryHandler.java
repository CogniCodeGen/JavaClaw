package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.extension.spi.DocumentRevision;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.VersionedDocument;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

import static com.javaclaw.builtin.extensions.MemoryCollectionNames.memories;
import static com.javaclaw.builtin.extensions.MemoryCollectionNames.proposals;

/** Memory 详情、检索、历史、统计和 ViewSchema 数据源的只读处理器。 */
final class MemoryQueryHandler {
    private static final Set<String> VIEW_OPERATIONS = Set.of(
            "view.new-memory",
            "view.memories",
            "view.memory",
            "view.history",
            "view.stats",
            "view.tombstones",
            "view.proposals",
            "view.settings");

    private final ExtensionPayloadCodec payloads;
    private final MemoryStoreAccess store;
    private final MemorySemantics semantics;

    MemoryQueryHandler(ExtensionPayloadCodec payloads, MemoryStoreAccess store) {
        this.payloads = java.util.Objects.requireNonNull(payloads, "payloads");
        this.store = java.util.Objects.requireNonNull(store, "store");
        semantics = new MemorySemantics(payloads, store);
    }

    ExtensionResponse query(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        if (VIEW_OPERATIONS.contains(request.operation())) {
            return viewQuery(request, context);
        }
        return switch (request.operation()) {
            case "read" -> read(request, context);
            case "list" -> list(request, context, memories(request));
            case "history" -> history(request, context);
            case "search" -> search(request, context);
            case "search/v2" -> searchV2(request, context);
            case "graph/read" -> graph(request, context);
            case "proposal/read" -> readProposal(request, context);
            case "proposal/list" -> list(request, context, proposals(request));
            case "settings/read" -> settings(context);
            case "stats" -> stats(context);
            default -> throw new IllegalArgumentException("unknown Memory query: " + request.operation());
        };
    }

    private ExtensionResponse viewQuery(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        return switch (request.operation()) {
            case "view.new-memory" -> viewNewMemory(request);
            case "view.memories" -> viewMemories(request, context);
            case "view.memory" -> viewMemory(request, context);
            case "view.history" -> viewHistory(request, context);
            case "view.stats" -> viewStats(request, context);
            case "view.tombstones" -> viewTombstones(request, context);
            case "view.proposals" -> viewProposals(request, context);
            case "view.settings" -> viewSettings(request, context);
            default -> throw new IllegalArgumentException("unknown Memory view query: " + request.operation());
        };
    }

    private ExtensionResponse viewNewMemory(ExtensionRequest request) {
        ViewQueryRequest query = payloads.decode(request.payload(), ViewQueryRequest.class);
        if (!"newMemory".equals(query.dataSourceId())
                || !query.arguments().isEmpty()
                || !query.cursor().isEmpty()) {
            throw new IllegalArgumentException("Memory new value view does not accept arguments");
        }
        ViewQueryResult result =
                new ViewQueryResult(query.dataSourceId(), List.of(), payloads.encode(Map.of()), "", false, 0);
        return new ExtensionResponse(payloads.encode(result), 0);
    }

    ExtensionResponse search(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        MemoryContracts.SearchRequest search = payloads.decode(request.payload(), MemoryContracts.SearchRequest.class);
        List<MemoryContracts.Memory> matches = context.managedStore()
                .inTransaction(
                        MemoryStoreAccess.ID,
                        transaction ->
                                semantics
                                        .search(
                                                transaction,
                                                request.workspaceId(),
                                                search,
                                                context.clock().instant(),
                                                true)
                                        .stream()
                                        .map(com.javaclaw.builtin.contracts.MemoryV3Contracts.SearchMatch::memory)
                                        .toList());
        return new ExtensionResponse(payloads.encode(new MemoryContracts.SearchResult(matches)), 0);
    }

    ExtensionResponse searchV2(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        MemoryContracts.SearchRequest search = payloads.decode(request.payload(), MemoryContracts.SearchRequest.class);
        var now = context.clock().instant();
        var result = context.managedStore()
                .inTransaction(
                        MemoryStoreAccess.ID,
                        transaction -> new com.javaclaw.builtin.contracts.MemoryV3Contracts.SearchResult(
                                semantics.search(transaction, request.workspaceId(), search, now, false),
                                now,
                                semantics.head(transaction, request.workspaceId())));
        return new ExtensionResponse(payloads.encode(result), result.memoryRevision());
    }

    private ExtensionResponse graph(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        var query =
                payloads.decode(request.payload(), com.javaclaw.builtin.contracts.MemoryV3Contracts.GraphRequest.class);
        var graph = context.managedStore()
                .inTransaction(
                        MemoryStoreAccess.ID,
                        transaction -> semantics.graph(
                                transaction,
                                request.workspaceId(),
                                query,
                                context.clock().instant()));
        return new ExtensionResponse(payloads.encode(graph), graph.memoryRevision());
    }

    private ExtensionResponse read(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        MemoryContracts.Key key = payloads.decode(request.payload(), MemoryContracts.Key.class);
        MemoryContracts.Memory memory = context.managedStore()
                .inTransaction(
                        MemoryStoreAccess.ID,
                        transaction -> store.requireMemory(transaction, memories(request), key.id()));
        return store.response(memory);
    }

    private ExtensionResponse readProposal(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        MemoryContracts.Key key = payloads.decode(request.payload(), MemoryContracts.Key.class);
        MemoryContracts.Proposal proposal = context.managedStore()
                .inTransaction(
                        MemoryStoreAccess.ID,
                        transaction -> store.requireProposal(transaction, proposals(request), key.id()));
        return new ExtensionResponse(payloads.encode(proposal), proposal.revision());
    }

    private ExtensionResponse list(ExtensionRequest request, ExtensionExecutionContext context, String collection)
            throws Exception {
        DocumentContracts.PageRequest page = payloads.decode(request.payload(), DocumentContracts.PageRequest.class);
        List<VersionedDocument> values = context.managedStore()
                .inTransaction(
                        MemoryStoreAccess.ID,
                        transaction -> transaction.list(collection, page.afterKey(), page.limit()));
        DocumentContracts.Page result = new DocumentContracts.Page(
                values.stream().map(VersionedDocument::payload).toList(),
                values.isEmpty() ? page.afterKey() : values.getLast().key());
        return new ExtensionResponse(payloads.encode(result), 0);
    }

    private ExtensionResponse history(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        MemoryContracts.HistoryRequest input = payloads.decode(request.payload(), MemoryContracts.HistoryRequest.class);
        List<DocumentRevision> fetched = context.managedStore()
                .inTransaction(
                        MemoryStoreAccess.ID,
                        transaction -> transaction.history(
                                memories(request), input.id(), input.afterRevision(), input.limit() + 1));
        boolean more = fetched.size() > input.limit();
        List<DocumentRevision> page = more ? fetched.subList(0, input.limit()) : fetched;
        List<MemoryContracts.HistoryEntry> entries =
                page.stream().map(store::historyEntry).toList();
        return new ExtensionResponse(payloads.encode(new MemoryContracts.HistoryPage(entries, more)), 0);
    }

    private ExtensionResponse settings(ExtensionExecutionContext context) throws Exception {
        MemoryContracts.LearningSettings learning = context.managedStore()
                .inTransaction(
                        MemoryStoreAccess.ID,
                        transaction -> store.readSettings(transaction, context.workspaceId(), context));
        return new ExtensionResponse(payloads.encode(learning), learning.revision());
    }

    private ExtensionResponse stats(ExtensionExecutionContext context) throws Exception {
        MemoryContracts.Stats result = context.managedStore().inTransaction(MemoryStoreAccess.ID, transaction -> {
            List<MemoryContracts.Memory> current = store.allMemories(transaction, memories(context.workspaceId()));
            List<MemoryContracts.Proposal> pending = store.allProposals(transaction, proposals(context.workspaceId()));
            return new MemoryContracts.Stats(
                    current.size(),
                    current.stream().filter(MemoryContracts.Memory::pinned).count(),
                    pending.stream()
                            .filter(value -> value.state() == MemoryContracts.ProposalState.PENDING)
                            .count(),
                    MemoryStoreAccess.countTombstones(transaction, memories(context.workspaceId())));
        });
        return new ExtensionResponse(payloads.encode(result), 0);
    }

    private ExtensionResponse viewMemories(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        ViewQueryRequest query = payloads.decode(request.payload(), ViewQueryRequest.class);
        if (!query.arguments().isEmpty()) {
            throw new IllegalArgumentException("Memory list view arguments must be empty");
        }
        List<VersionedDocument> fetched = context.managedStore()
                .inTransaction(
                        MemoryStoreAccess.ID,
                        transaction ->
                                transaction.list(memories(request), query.cursor(), Math.addExact(query.limit(), 1)));
        boolean more = fetched.size() > query.limit();
        List<VersionedDocument> page = more ? fetched.subList(0, query.limit()) : fetched;
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                page.stream().map(this::memoryRow).toList(),
                payloads.encode(Map.of()),
                more && !page.isEmpty() ? page.getLast().key() : "",
                more,
                page.stream().mapToLong(VersionedDocument::revision).max().orElse(0));
        return new ExtensionResponse(payloads.encode(result), result.revision());
    }

    private ExtensionResponse viewProposals(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        ViewQueryRequest query = payloads.decode(request.payload(), ViewQueryRequest.class);
        if (!query.arguments().isEmpty()) {
            throw new IllegalArgumentException("Memory Proposal view arguments must be empty");
        }
        List<VersionedDocument> fetched = context.managedStore()
                .inTransaction(
                        MemoryStoreAccess.ID,
                        transaction ->
                                transaction.list(proposals(request), query.cursor(), Math.addExact(query.limit(), 1)));
        boolean more = fetched.size() > query.limit();
        List<VersionedDocument> page = more ? fetched.subList(0, query.limit()) : fetched;
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                page.stream().map(this::proposalRow).toList(),
                payloads.encode(Map.of()),
                more && !page.isEmpty() ? page.getLast().key() : "",
                more,
                page.stream().mapToLong(VersionedDocument::revision).max().orElse(0));
        return new ExtensionResponse(payloads.encode(result), result.revision());
    }

    private ExtensionResponse viewHistory(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        ViewQueryRequest query = payloads.decode(request.payload(), ViewQueryRequest.class);
        if (!query.arguments().keySet().equals(Set.of("id"))) {
            throw new IllegalArgumentException("Memory history view requires one selected id");
        }
        long afterRevision = historyCursor(query.cursor());
        String id = query.arguments().get("id");
        HistoryViewPage page = context.managedStore().inTransaction(MemoryStoreAccess.ID, transaction -> {
            MemoryContracts.Memory current = store.requireMemory(transaction, memories(request), id);
            List<DocumentRevision> fetched =
                    transaction.history(memories(request), id, afterRevision, Math.addExact(query.limit(), 1));
            boolean more = fetched.size() > query.limit();
            List<DocumentRevision> values = more ? fetched.subList(0, query.limit()) : fetched;
            return new HistoryViewPage(current.revision(), values, more);
        });
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                page.values().stream()
                        .map(value -> historyRow(value, page.currentRevision()))
                        .toList(),
                payloads.encode(Map.of()),
                page.more() && !page.values().isEmpty()
                        ? Long.toString(page.values().getLast().revision())
                        : "",
                page.more(),
                page.currentRevision());
        return new ExtensionResponse(payloads.encode(result), result.revision());
    }

    private ExtensionResponse viewStats(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        ViewQueryRequest query = payloads.decode(request.payload(), ViewQueryRequest.class);
        if (!query.arguments().isEmpty() || !query.cursor().isEmpty()) {
            throw new IllegalArgumentException("Memory stats view does not accept arguments or cursor");
        }
        MemoryContracts.Stats current = payloads.decode(stats(context).payload(), MemoryContracts.Stats.class);
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                List.of(payloads.encode(new StatsViewRow(
                        "workspace",
                        current.active(),
                        current.pinned(),
                        current.pendingProposals(),
                        current.tombstones()))),
                payloads.encode(Map.of()),
                "",
                false,
                0);
        return new ExtensionResponse(payloads.encode(result), 0);
    }

    private ExtensionResponse viewSettings(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        ViewQueryRequest query = payloads.decode(request.payload(), ViewQueryRequest.class);
        if (!query.arguments().isEmpty() || !query.cursor().isEmpty()) {
            throw new IllegalArgumentException("Memory settings view does not accept arguments or cursor");
        }
        MemoryContracts.LearningSettings current = context.managedStore()
                .inTransaction(
                        MemoryStoreAccess.ID,
                        transaction -> store.readSettings(transaction, context.workspaceId(), context));
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(), List.of(), payloads.encode(current), "", false, current.revision());
        return new ExtensionResponse(payloads.encode(result), current.revision());
    }

    private ExtensionResponse viewMemory(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        ViewQueryRequest query = payloads.decode(request.payload(), ViewQueryRequest.class);
        if (!query.cursor().isEmpty() || !query.arguments().keySet().equals(java.util.Set.of("id", "revision"))) {
            throw new IllegalArgumentException("Memory editor requires one exact selected revision");
        }
        long selectedRevision = selectedRevision(query.arguments().get("revision"));
        MemoryContracts.Memory current = context.managedStore()
                .inTransaction(
                        MemoryStoreAccess.ID,
                        transaction -> store.requireMemory(
                                transaction,
                                memories(request),
                                query.arguments().get("id")));
        if (current.revision() != selectedRevision) {
            throw new IllegalArgumentException("Memory editor selection is stale");
        }
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(), List.of(), payloads.encode(current), "", false, current.revision());
        return new ExtensionResponse(payloads.encode(result), current.revision());
    }

    private static long selectedRevision(String value) {
        try {
            long revision = Long.parseLong(value);
            if (revision < 1) {
                throw new IllegalArgumentException("Memory selection revision must be positive");
            }
            return revision;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Memory selection revision is invalid", failure);
        }
    }

    private ExtensionResponse viewTombstones(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        ViewQueryRequest query = payloads.decode(request.payload(), ViewQueryRequest.class);
        if (!query.arguments().isEmpty()) {
            throw new IllegalArgumentException("Memory tombstone view arguments must be empty");
        }
        List<DocumentRevision> fetched = context.managedStore()
                .inTransaction(
                        MemoryStoreAccess.ID,
                        transaction -> transaction.listTombstones(
                                memories(request), query.cursor(), Math.addExact(query.limit(), 1)));
        boolean more = fetched.size() > query.limit();
        List<DocumentRevision> page = more ? fetched.subList(0, query.limit()) : fetched;
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(),
                page.stream().map(this::tombstoneRow).toList(),
                payloads.encode(Map.of()),
                more && !page.isEmpty() ? page.getLast().key() : "",
                more,
                0);
        return new ExtensionResponse(payloads.encode(result), 0);
    }

    private com.javaclaw.api.CanonicalPayload tombstoneRow(DocumentRevision tombstone) {
        MemoryContracts.Memory previous = payloads.decode(tombstone.payload(), MemoryContracts.Memory.class);
        return payloads.encode(Map.of(
                "id", tombstone.key(),
                "revision", tombstone.revision(),
                "sourceRevision", Math.subtractExact(tombstone.revision(), 1),
                "content", previous.content()));
    }

    private com.javaclaw.api.CanonicalPayload memoryRow(VersionedDocument document) {
        MemoryContracts.Memory memory = payloads.decode(document.payload(), MemoryContracts.Memory.class);
        if (memory.revision() != document.revision()) {
            throw new IllegalStateException("Memory payload revision differs from managed store");
        }
        String sourceItemId = memory.source()
                .map(MemoryContracts.Source::itemId)
                .map(Object::toString)
                .orElse("");
        String sourceThreadId = memory.source()
                .map(MemoryContracts.Source::threadId)
                .map(Object::toString)
                .orElse("");
        return payloads.encode(new MemoryViewRow(
                memory.id(),
                memory.revision(),
                memory.kind(),
                memory.scope(),
                memory.content(),
                memory.tags(),
                memory.pinned(),
                sourceThreadId,
                sourceItemId,
                memory.updatedAt()));
    }

    private com.javaclaw.api.CanonicalPayload proposalRow(VersionedDocument document) {
        MemoryContracts.Proposal proposal = payloads.decode(document.payload(), MemoryContracts.Proposal.class);
        if (proposal.revision() != document.revision()) {
            throw new IllegalStateException("Memory Proposal payload revision differs from managed store");
        }
        return payloads.encode(new ProposalViewRow(
                proposal.id(),
                proposal.revision(),
                proposal.candidate().kind(),
                proposal.candidate().scope(),
                proposal.candidate().content(),
                proposal.concerns(),
                proposal.state(),
                proposal.memoryId().orElse(""),
                proposal.updatedAt()));
    }

    private com.javaclaw.api.CanonicalPayload historyRow(DocumentRevision revision, long currentRevision) {
        MemoryContracts.HistoryEntry entry = store.historyEntry(revision);
        MemoryContracts.Memory memory = entry.memory();
        return payloads.encode(new HistoryViewRow(
                memory.id(),
                entry.revision(),
                entry.tombstone() ? Math.subtractExact(entry.revision(), 1) : entry.revision(),
                currentRevision,
                entry.tombstone(),
                memory.kind(),
                memory.scope(),
                memory.content(),
                entry.updatedAt()));
    }

    private static long historyCursor(String cursor) {
        if (cursor.isEmpty()) {
            return 0;
        }
        try {
            long revision = Long.parseLong(cursor);
            if (revision < 0) {
                throw new IllegalArgumentException("Memory history cursor must not be negative");
            }
            return revision;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Memory history cursor is invalid", failure);
        }
    }

    private record MemoryViewRow(
            String id,
            long revision,
            MemoryContracts.MemoryKind kind,
            String scope,
            String content,
            Set<String> tags,
            boolean pinned,
            String sourceThreadId,
            String sourceItemId,
            java.time.Instant updatedAt) {}

    private record HistoryViewRow(
            String id,
            long revision,
            long sourceRevision,
            long currentRevision,
            boolean tombstone,
            MemoryContracts.MemoryKind kind,
            String scope,
            String content,
            java.time.Instant updatedAt) {}

    private record HistoryViewPage(long currentRevision, List<DocumentRevision> values, boolean more) {}

    private record StatsViewRow(String id, long active, long pinned, long pendingProposals, long tombstones) {}

    private record ProposalViewRow(
            String id,
            long revision,
            MemoryContracts.MemoryKind kind,
            String scope,
            String content,
            Set<MemoryContracts.ProposalConcern> concerns,
            MemoryContracts.ProposalState state,
            String memoryId,
            java.time.Instant updatedAt) {}
}
