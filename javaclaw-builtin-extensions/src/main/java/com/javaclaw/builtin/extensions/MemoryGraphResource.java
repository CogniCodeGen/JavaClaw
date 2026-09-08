package com.javaclaw.builtin.extensions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.MemoryV3Contracts;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

/** 无状态图谱查询；页面自行拥有浏览窗口，服务端只验证 200/50/500/1000 容量与当前 Memory 版本。 */
final class MemoryGraphResource {
    private final ExtensionPayloadCodec payloads;
    private final MemoryStoreAccess store;
    private final MemorySemantics semantics;
    private final MemoryGraphProjection projection;

    MemoryGraphResource(ExtensionPayloadCodec payloads, MemoryStoreAccess store) {
        this.payloads = payloads;
        this.store = store;
        semantics = new MemorySemantics(payloads, store);
        projection = new MemoryGraphProjection(payloads, store);
    }

    List<ExtensionContribution> contributions() {
        return List.of(new ExtensionContributions.Query(
                "memory.graph.query", Set.of("graph/neighbors", "view.graph.filters"), this::query));
    }

    ExtensionResponse view(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ViewQueryRequest query,
            ExtensionTransaction transaction) {
        var window = window(query);
        var matching = matching(transaction, context, window);
        var ids = window.nodeIds().isEmpty()
                ? matching.stream().limit(200).toList()
                : window.nodeIds().stream().filter(matching::contains).toList();
        var graph = graph(transaction, context, ids);
        var rows = "view.graph.nodes".equals(request.operation())
                ? graph.nodes().stream().map(payloads::encode).toList()
                : graph.edges().stream().map(payloads::encode).toList();
        String identity = payloads.encode(Map.of("window", window, "head", graph.memoryRevision()))
                .sha256();
        int offset = query.cursor().isEmpty() ? 0 : cursorOffset(query.cursor(), identity);
        if (offset > rows.size()) {
            throw new IllegalArgumentException("graph page cursor is stale");
        }
        int end = Math.min(rows.size(), offset + query.limit());
        boolean more = end < rows.size();
        var result = new ViewQueryResult(
                query.dataSourceId(),
                rows.subList(offset, end),
                payloads.encode(Map.of(
                        "truncated",
                        graph.truncated() || matching.size() > ids.size(),
                        "memoryRevision",
                        graph.memoryRevision())),
                more ? identity + ":" + end : "",
                more,
                graph.memoryRevision());
        return new ExtensionResponse(payloads.encode(result), result.revision());
    }

    private MemoryV3Contracts.GraphWindow window(ViewQueryRequest query) {
        String json = query.arguments().get("graphWindow");
        return json == null
                ? new MemoryV3Contracts.GraphWindow("", false, List.of())
                : payloads.decode(new CanonicalPayload(json), MemoryV3Contracts.GraphWindow.class);
    }

    private ExtensionResponse query(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        return context.managedStore().inTransaction(MemoryStoreAccess.ID, transaction -> {
            if ("view.graph.filters".equals(request.operation())) {
                var input = payloads.decode(request.payload(), ViewQueryRequest.class);
                var window = window(input);
                var result = new ViewQueryResult(
                        input.dataSourceId(),
                        List.of(),
                        payloads.encode(window),
                        "",
                        false,
                        semantics.head(transaction, context.workspaceId()));
                return new ExtensionResponse(payloads.encode(result), result.revision());
            }
            var input = payloads.decode(request.payload(), MemoryV3Contracts.GraphNeighbors.class);
            var candidates = neighbors(transaction, context, input.id(), input.includeInactive()).stream()
                    .filter(id -> id.compareTo(input.afterId()) > 0)
                    .toList();
            var page = candidates.stream().limit(input.limit()).toList();
            boolean more = candidates.size() > page.size();
            var graph = graph(transaction, context, page);
            var result = new MemoryV3Contracts.GraphPage(graph, more ? page.getLast() : "", more);
            return new ExtensionResponse(payloads.encode(result), graph.memoryRevision());
        });
    }

    private List<String> matching(
            ExtensionTransaction transaction, ExtensionExecutionContext context, MemoryV3Contracts.GraphWindow window) {
        return store.allMemories(transaction, MemoryCollectionNames.memories(context.workspaceId())).stream()
                .filter(memory -> ExtensionSearch.contains(window.query(), memory.content(), memory.scope()))
                .filter(memory -> window.includeInactive()
                        || semantics
                                .effectivity(transaction, context.workspaceId(), memory.id())
                                .effectiveAt(context.clock().instant()))
                .map(MemoryContracts.Memory::id)
                .sorted()
                .toList();
    }

    private List<String> neighbors(
            ExtensionTransaction transaction, ExtensionExecutionContext context, String id, boolean includeInactive) {
        store.requireMemory(transaction, MemoryCollectionNames.memories(context.workspaceId()), id);
        Set<String> adjacent = new LinkedHashSet<>(
                semantics.effectivity(transaction, context.workspaceId(), id).replacements());
        var memories = store.allMemories(transaction, MemoryCollectionNames.memories(context.workspaceId()));
        for (var memory : memories) {
            if (semantics
                    .effectivity(transaction, context.workspaceId(), memory.id())
                    .replacements()
                    .contains(id)) {
                adjacent.add(memory.id());
            }
        }
        projection
                .currentAssertions(
                        transaction, context.workspaceId(), context.clock().instant())
                .stream()
                .filter(value -> !"REMEMBERS".equals(value.predicate()))
                .forEach(value -> {
                    if (value.subject().id().equals(id)) {
                        adjacent.add(value.object().id());
                    }
                    if (value.object().id().equals(id)) {
                        adjacent.add(value.subject().id());
                    }
                });
        return matching(transaction, context, new MemoryV3Contracts.GraphWindow("", includeInactive, List.of()))
                .stream()
                .filter(adjacent::contains)
                .toList();
    }

    private MemoryV3Contracts.Graph graph(
            ExtensionTransaction transaction, ExtensionExecutionContext context, List<String> ids) {
        var memories = store.allMemories(transaction, MemoryCollectionNames.memories(context.workspaceId())).stream()
                .filter(memory -> ids.contains(memory.id()))
                .sorted(java.util.Comparator.comparing(MemoryContracts.Memory::id))
                .limit(500)
                .toList();
        Set<String> visible = memories.stream().map(MemoryContracts.Memory::id).collect(Collectors.toSet());
        var nodes = new ArrayList<MemoryV3Contracts.GraphNode>();
        var edges = new ArrayList<MemoryV3Contracts.GraphEdge>();
        for (var memory : memories) {
            var effect = semantics.effectivity(transaction, context.workspaceId(), memory.id());
            nodes.add(node(memory, effect, context));
            effect.replacements().stream()
                    .sorted()
                    .filter(visible::contains)
                    .forEach(target -> edges.add(new MemoryV3Contracts.GraphEdge(memory.id(), target, "REPLACED_BY")));
        }
        projection
                .currentAssertions(
                        transaction, context.workspaceId(), context.clock().instant())
                .stream()
                .filter(value -> !"REMEMBERS".equals(value.predicate()))
                .filter(value -> visible.contains(value.subject().id())
                        && visible.contains(value.object().id()))
                .forEach(value -> edges.add(new MemoryV3Contracts.GraphEdge(
                        value.subject().id(), value.object().id(), value.predicate())));
        return new MemoryV3Contracts.Graph(
                nodes,
                edges.stream().limit(1000).toList(),
                semantics.head(transaction, context.workspaceId()),
                edges.size() > 1000);
    }

    private static MemoryV3Contracts.GraphNode node(
            MemoryContracts.Memory memory, MemoryV3Contracts.Effectivity effect, ExtensionExecutionContext context) {
        String label = MemoryGraphLabels.label(memory.content());
        String condition = effect.condition()
                + effect.validFrom().map(value -> " 起始 " + value).orElse("")
                + effect.validUntil().map(value -> " 截止（不含） " + value).orElse("");
        return new MemoryV3Contracts.GraphNode(
                memory.id(),
                label,
                memory.revision(),
                effect.state(),
                effect.effectiveAt(context.clock().instant()),
                condition);
    }

    private static int cursorOffset(String cursor, String identity) {
        String[] parts = cursor.split(":", -1);
        if (parts.length != 2 || !parts[0].equals(identity) || Integer.parseInt(parts[1]) < 0) {
            throw new IllegalArgumentException("graph window or Memory revision changed; restart pagination");
        }
        return Integer.parseInt(parts[1]);
    }
}
