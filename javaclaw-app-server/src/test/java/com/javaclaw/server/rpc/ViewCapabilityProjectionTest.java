package com.javaclaw.server.rpc;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.extensions.BuiltinExtensions;
import com.javaclaw.extension.spi.ExtensionContext;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.GraphBrowsing;
import com.javaclaw.extension.spi.ViewArgumentBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.NegotiatedCapabilities;
import com.javaclaw.protocol.ViewSchemaWireCodec;
import com.javaclaw.server.extension.CanonicalExtensionPayloadCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewCapabilityProjectionTest {
    @Test
    void 真实记忆页面降级后仍能选择权威行并保留详情和命令绑定() throws Exception {
        ViewSchema schema = memoryGraph();
        ViewSchema legacy = legacy(schema);
        assertTrue(legacy.graphBrowsing().isEmpty());
        assertEquals(schema.dataSources(), legacy.dataSources());
        assertEquals(schema.nodes().size(), legacy.nodes().size());
        ViewSchema.ListView selection = assertInstanceOf(
                ViewSchema.ListView.class,
                legacy.nodes().stream()
                        .filter(node -> node.id().equals("memory-graph"))
                        .findFirst()
                        .orElseThrow());
        assertEquals("graphNodes", selection.sourceId());
        assertEquals("id", selection.keyField());
        assertEquals(ViewSelectionMode.SINGLE, selection.selection());
        assertEquals(
                schema.nodes().stream()
                        .filter(node -> !(node instanceof ViewSchema.Graph))
                        .toList(),
                legacy.nodes().stream()
                        .filter(node -> !node.id().equals("memory-graph"))
                        .toList());
        // 复现旧客户端的主从约束：每个动态参数源都必须由单选列表或表格提供，不能只靠 JSON 可解码。
        Set<String> selectable = legacy.nodes().stream()
                .flatMap(node -> switch (node) {
                    case ViewSchema.ListView list
                    when list.selection() == ViewSelectionMode.SINGLE -> java.util.stream.Stream.of(list.sourceId());
                    case ViewSchema.Table table
                    when table.selection() == ViewSelectionMode.SINGLE -> java.util.stream.Stream.of(table.sourceId());
                    default -> java.util.stream.Stream.empty();
                })
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(legacy.dataSources().stream()
                .flatMap(source -> source.argumentBindings().stream())
                .allMatch(binding -> selectable.contains(binding.sourceId())));
    }

    @Test
    void 已有单选列表或表格的页面保留原基础图谱() {
        var graph = new ViewSchema.Graph("graph", "图谱", "nodes", "edges", "id", "label", "kind", "from", "to");
        var sources = List.of(
                new ViewDataSource("nodes", "nodes/read", Map.of(), List.of(), 100),
                new ViewDataSource("edges", "edges/read", Map.of(), List.of(), 100),
                new ViewDataSource(
                        "detail", "detail/read", Map.of(), List.of(new ViewArgumentBinding("id", "nodes", "id")), 1));
        var list = new ViewSchema.ListView(
                "selection", "选择", "nodes", "id", "label", "kind", ViewSelectionMode.SINGLE, List.of());
        var table = new ViewSchema.Table(
                "selection",
                "选择",
                "nodes",
                "id",
                List.of(new ViewSchema.Column("label", "名称", Optional.empty())),
                ViewSelectionMode.SINGLE,
                List.of());
        for (ViewSchema.Node selection : List.of(list, table)) {
            ViewSchema schema = new ViewSchema(2, "view", "页面", sources, List.of(graph, selection));
            assertEquals(schema, legacy(schema));
        }
    }

    private static ViewSchema memoryGraph() throws Exception {
        CanonicalJson json = new CanonicalJson();
        try (var memory = BuiltinExtensions.create().stream()
                .filter(bundle -> bundle.descriptor().id().value().equals(BuiltinExtensionIds.MEMORY))
                .findFirst()
                .orElseThrow()) {
            return memory
                    .start(new ExtensionContext(Clock.systemUTC(), new CanonicalExtensionPayloadCodec(json)))
                    .stream()
                    .filter(ExtensionContributions.View.class::isInstance)
                    .map(ExtensionContributions.View.class::cast)
                    .map(ExtensionContributions.View::view)
                    .filter(view -> view.viewId().equals("javaclaw.memory.graph"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static ViewSchema legacy(ViewSchema schema) {
        CanonicalJson json = new CanonicalJson();
        var codec = new ViewSchemaWireCodec(json);
        var result =
                json.encode(new ExtensionRpcContracts.ViewListResult(List.of(new ExtensionRpcContracts.ViewDocument(
                        BuiltinExtensionIds.MEMORY, schema.viewId(), codec.encode(schema)))));
        var projected = ViewCapabilityProjection.project(
                "extension/view/list",
                result,
                new NegotiatedCapabilities(Set.of("extension.view-schema-v2"), Set.of()),
                json);
        return codec.decode(json.decode(projected, ExtensionRpcContracts.ViewListResult.class)
                .views()
                .getFirst()
                .schema());
    }

    @Test
    void 旧连接只去掉浏览元数据且新连接保留原响应() {
        CanonicalJson json = new CanonicalJson();
        ViewSchemaWireCodec codec = new ViewSchemaWireCodec(json);
        var graph = new ViewSchema.Graph("graph", "图谱", "nodes", "edges", "id", "label", "kind", "from", "to");
        var schema = new ViewSchema(
                2,
                "view",
                "页面",
                List.of(),
                List.of(graph),
                Map.of("graph", new GraphBrowsing("neighbors/query", "window", "query", "inactive", "admitted")));
        var result = json.encode(new ExtensionRpcContracts.ViewListResult(
                List.of(new ExtensionRpcContracts.ViewDocument("builtin.test", "view", codec.encode(schema)))));
        var legacy = ViewCapabilityProjection.project(
                "extension/view/list",
                result,
                new NegotiatedCapabilities(Set.of("extension.view-schema-v2"), Set.of()),
                json);
        assertFalse(legacy.json().contains("graphBrowsing"));
        var document = json.decode(legacy, ExtensionRpcContracts.ViewListResult.class)
                .views()
                .getFirst();
        assertEquals(List.of(graph), codec.decode(document.schema()).nodes());
        assertTrue(codec.decode(document.schema()).graphBrowsing().isEmpty());
        assertEquals(
                result,
                ViewCapabilityProjection.project(
                        "extension/view/list",
                        result,
                        new NegotiatedCapabilities(Set.of(ViewSchemaWireCodec.GRAPH_BROWSING_CAPABILITY), Set.of()),
                        json));
        assertEquals(
                result,
                ViewCapabilityProjection.project(
                        "extension/query", result, new NegotiatedCapabilities(Set.of(), Set.of()), json));
    }
}
