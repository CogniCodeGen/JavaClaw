package com.javaclaw.builtin.extensions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.GraphBrowsing;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewArgumentBinding;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCommandBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewSchema;

/** 图谱页面只声明平台浏览能力；业务表单仅持久化有效性和经用户明确确认的关系。 */
final class MemoryGraphView {
    private MemoryGraphView() {}

    static ViewSchema create() {
        var graph = graph();
        var conflicts = MemoryV3Management.conflictView();
        var sources = new ArrayList<>(graph.dataSources());
        sources.addAll(conflicts.dataSources());
        var nodes = new ArrayList<ViewSchema.Node>();
        nodes.add(new ViewSchema.Markdown("graph-conflict-notice", "记忆冲突提示", new ViewBinding("conflicts", "notice")));
        nodes.addAll(graph.nodes());
        nodes.addAll(conflicts.nodes());
        return new ViewSchema(
                graph.schemaVersion(), graph.viewId(), graph.title(), sources, nodes, graph.graphBrowsing());
    }

    private static ViewSchema graph() {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "javaclaw.memory.graph",
                "记忆图谱",
                sources(),
                List.of(
                        new ViewSchema.Card(
                                "graph-bound",
                                "已确认关系",
                                "初始 200 节点，每次扩展最多 50 个邻居，累计最多 500 节点及 1000 关系。浏览状态只保留在当前页面。",
                                List.of()),
                        new ViewSchema.Graph(
                                "memory-graph",
                                "记忆关系",
                                "graphNodes",
                                "graphEdges",
                                "id",
                                "label",
                                "state",
                                "source",
                                "target"),
                        new ViewSchema.Markdown(
                                "graph-memory-content", "记忆正文", new ViewBinding("graphMemory", "content")),
                        new ViewSchema.Markdown(
                                "graph-assertions", "已确认断言与原文证据", new ViewBinding("graphProjection", "detail")),
                        effectivity(),
                        relation()),
                Map.of(
                        "memory-graph",
                        new GraphBrowsing("graph/neighbors", "graphWindow", "query", "includeInactive", "nodeIds")));
    }

    private static List<ViewDataSource> sources() {
        return List.of(
                new ViewDataSource("graphNodes", "view.graph.nodes", Map.of(), List.of(), 200),
                new ViewDataSource("graphEdges", "view.graph.edges", Map.of(), List.of(), 200),
                selected("graphMemory", "view.graph.memory"),
                selected("graphProjection", "view.graph.projection"));
    }

    private static ViewDataSource selected(String id, String operation) {
        return new ViewDataSource(
                id,
                operation,
                Map.of(),
                List.of(
                        new ViewArgumentBinding("id", "graphNodes", "id"),
                        new ViewArgumentBinding("revision", "graphNodes", "revision")),
                1);
    }

    private static ViewSchema.Form effectivity() {
        return new ViewSchema.Form(
                "memory-effectivity",
                "有效性（自然语言条件仅供人工判断）",
                List.of(
                        field("validFrom", "起始时间（含，可空）", ViewFieldType.TEXT, false),
                        field("validUntil", "结束时间（不含，可空）", ViewFieldType.TEXT, false),
                        field("condition", "人工条件", ViewFieldType.MULTILINE, false)),
                new ViewAction(
                        "保存有效性",
                        "effectivity/form/update",
                        Map.of(),
                        Map.of(),
                        new ExpectedRevisionBinding.SourceRevision("graphMemory"),
                        false,
                        new ViewCommandBinding("id", new ViewBinding("graphMemory", "id"))));
    }

    private static ViewSchema.Form relation() {
        return new ViewSchema.Form(
                "memory-relation",
                "人工确认关系（两个记忆必须已确认；编辑后须重新确认）",
                List.of(
                        field("target", "目标记忆 ID", ViewFieldType.TEXT, true),
                        field("predicate", "确认的关系名称", ViewFieldType.TEXT, true)),
                new ViewAction(
                        "确认关系",
                        "graph/relation/confirm",
                        Map.of(),
                        Map.of(),
                        new ExpectedRevisionBinding.SourceRevision("graphProjection"),
                        true,
                        new ViewCommandBinding("source", new ViewBinding("graphMemory", "id"))));
    }

    private static ViewField field(String name, String label, ViewFieldType type, boolean required) {
        return new ViewField(
                name,
                label,
                type,
                new ViewBinding("graphMemory", name),
                Optional.empty(),
                ViewFieldValidation.required(required),
                List.of(),
                Optional.empty(),
                Optional.empty());
    }
}
