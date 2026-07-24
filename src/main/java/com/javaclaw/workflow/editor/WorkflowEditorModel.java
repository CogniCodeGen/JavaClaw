package com.javaclaw.workflow.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.workflow.model.EdgeDefinition;
import com.javaclaw.workflow.model.EdgeKind;
import com.javaclaw.workflow.model.ConditionRule;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.GraphKind;
import com.javaclaw.workflow.model.NodeDefinition;
import com.javaclaw.workflow.model.NodeType;
import com.javaclaw.workflow.model.ResumeSafety;
import com.javaclaw.workflow.model.RetryPolicy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** JavaFX 无关的编辑会话，保存 100 步不可变定义快照。 */
public final class WorkflowEditorModel {
    private static final int HISTORY_LIMIT = 100;
    private final ArrayDeque<GraphDefinition> undo = new ArrayDeque<>();
    private final ArrayDeque<GraphDefinition> redo = new ArrayDeque<>();
    private GraphDefinition current;

    public WorkflowEditorModel(GraphDefinition definition) { current = definition; }
    public GraphDefinition current() { return current; }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    public void replace(GraphDefinition next) {
        if (next == null || next.equals(current)) return;
        undo.addLast(current);
        while (undo.size() > HISTORY_LIMIT) undo.removeFirst();
        current = next;
        redo.clear();
    }

    public GraphDefinition undo() {
        if (undo.isEmpty()) return current;
        redo.addLast(current);
        current = undo.removeLast();
        return current;
    }

    public GraphDefinition redo() {
        if (redo.isEmpty()) return current;
        undo.addLast(current);
        current = redo.removeLast();
        return current;
    }

    public NodeDefinition addNode(NodeType type, double x, double y) {
        String id = type.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode config = defaultConfig(type);
        ResumeSafety safety = type == NodeType.AGENT || type == NodeType.TOOL
                ? ResumeSafety.CONFIRM_RETRY : ResumeSafety.SAFE;
        NodeDefinition node = new NodeDefinition(id, type, type.name().toLowerCase(),
                defaultLabel(type), config, x, y, RetryPolicy.NONE, safety);
        List<NodeDefinition> nodes = new ArrayList<>(current.nodes()); nodes.add(node);
        List<EdgeDefinition> edges = new ArrayList<>(current.edges());
        EdgeDefinition insertionEdge = initialTemplateInsertionEdge();
        if (insertionEdge != null) {
            edges.remove(insertionEdge);
            edges.add(new EdgeDefinition("edge-" + UUID.randomUUID().toString().substring(0, 8),
                    current.startNodeId(), id, EdgeKind.NORMAL, null, 0, false));
            edges.add(new EdgeDefinition("edge-" + UUID.randomUUID().toString().substring(0, 8),
                    id, insertionEdge.target(), EdgeKind.NORMAL, null, 0, false));
        }
        replace(copy(nodes, edges));
        return node;
    }

    /**
     * 空白模板首次添加业务节点时自动插入主链。
     * 同时兼容旧版 START→END 草稿与新版 START→OUTPUT→END 模板。
     */
    private EdgeDefinition initialTemplateInsertionEdge() {
        if (current.nodes().size() == 2 && current.edges().size() == 1) {
            return current.edges().stream()
                    .filter(e -> e.kind() == EdgeKind.NORMAL)
                    .filter(e -> e.source().equals(current.startNodeId()))
                    .filter(e -> current.nodes().stream().anyMatch(n ->
                            n.id().equals(e.target()) && n.type() == NodeType.END))
                    .findFirst().orElse(null);
        }
        if (current.nodes().size() != 3 || current.edges().size() != 2) return null;
        NodeDefinition output = current.nodes().stream()
                .filter(n -> n.type() == NodeType.OUTPUT).findFirst().orElse(null);
        NodeDefinition end = current.nodes().stream()
                .filter(n -> n.type() == NodeType.END).findFirst().orElse(null);
        if (output == null || end == null) return null;
        boolean outputLeadsToEnd = current.edges().stream()
                .anyMatch(e -> e.kind() == EdgeKind.NORMAL
                        && e.source().equals(output.id()) && e.target().equals(end.id()));
        if (!outputLeadsToEnd) return null;
        return current.edges().stream()
                .filter(e -> e.kind() == EdgeKind.NORMAL)
                .filter(e -> e.source().equals(current.startNodeId()) && e.target().equals(output.id()))
                .findFirst().orElse(null);
    }

    public void updateNode(NodeDefinition updated) {
        List<NodeDefinition> nodes = current.nodes().stream()
                .map(n -> n.id().equals(updated.id()) ? updated : n).toList();
        replace(copy(nodes, current.edges()));
    }

    public void moveNode(String id, double x, double y) {
        List<NodeDefinition> nodes = current.nodes().stream().map(n -> n.id().equals(id)
                ? new NodeDefinition(n.id(), n.type(), n.executorType(), n.label(), n.config(), x, y,
                n.retryPolicy(), n.resumeSafety()) : n).toList();
        replace(copy(nodes, current.edges()));
    }

    public void deleteNode(String id) {
        if (id.equals(current.startNodeId())) throw new IllegalArgumentException("不能删除 START 节点");
        NodeDefinition target = current.nodes().stream()
                .filter(n -> n.id().equals(id)).findFirst().orElse(null);
        if (target != null && target.type() == NodeType.END
                && current.nodes().stream().filter(n -> n.type() == NodeType.END).count() == 1) {
            throw new IllegalArgumentException("不能删除唯一的 END 节点");
        }
        List<NodeDefinition> nodes = current.nodes().stream().filter(n -> !n.id().equals(id)).toList();
        List<EdgeDefinition> edges = current.edges().stream()
                .filter(e -> !e.source().equals(id) && !e.target().equals(id)).toList();
        replace(copy(nodes, edges));
    }

    public EdgeDefinition connect(String source, String target) {
        return connect(source, target, EdgeKind.NORMAL, null, 0, false);
    }

    public EdgeDefinition connect(String source, String target, EdgeKind kind,
                                  ConditionRule condition, int priority, boolean defaultEdge) {
        if (source.equals(target)) throw new IllegalArgumentException("不能创建直接自环");
        boolean exists = current.edges().stream().anyMatch(e -> e.source().equals(source)
                && e.target().equals(target) && e.kind() == kind);
        if (exists) throw new IllegalArgumentException("连接已存在");
        EdgeDefinition edge = new EdgeDefinition("edge-" + UUID.randomUUID().toString().substring(0, 8),
                source, target, kind, condition, priority, defaultEdge);
        List<EdgeDefinition> edges = new ArrayList<>(current.edges());
        // 顺序图的普通出口唯一：重新连线即替换旧出口，避免制造无法发布的双普通边。
        if (kind == EdgeKind.NORMAL) edges.removeIf(e -> e.source().equals(source)
                && (e.kind() == EdgeKind.NORMAL || e.kind() == EdgeKind.CONDITIONAL));
        if (kind == EdgeKind.CONDITIONAL) {
            edges.removeIf(e -> e.source().equals(source) && e.kind() == EdgeKind.NORMAL);
        }
        if (kind == EdgeKind.CONDITIONAL && defaultEdge) {
            edges.removeIf(e -> e.source().equals(source) && e.kind() == EdgeKind.CONDITIONAL && e.defaultEdge());
        }
        if (kind == EdgeKind.ERROR) {
            edges.removeIf(e -> e.source().equals(source) && e.kind() == EdgeKind.ERROR);
        }
        edges.add(edge);
        replace(copy(current.nodes(), edges));
        return edge;
    }

    public void deleteEdge(String id) {
        replace(copy(current.nodes(), current.edges().stream().filter(e -> !e.id().equals(id)).toList()));
    }

    private GraphDefinition copy(List<NodeDefinition> nodes, List<EdgeDefinition> edges) {
        return new GraphDefinition(current.schemaVersion(), current.id(), current.name(), current.description(),
                current.version(), current.kind(), current.startNodeId(), nodes, edges, current.maxSteps());
    }

    public static GraphDefinition blank(String name) {
        String id = "wf-" + UUID.randomUUID();
        NodeDefinition start = new NodeDefinition("start", NodeType.START, "start", "开始",
                JsonNodeFactory.instance.objectNode(), 80, 120, RetryPolicy.NONE, ResumeSafety.SAFE);
        var outputConfig = JsonNodeFactory.instance.objectNode();
        outputConfig.put("template", "{{input}}");
        outputConfig.put("outputKey", "output");
        NodeDefinition output = new NodeDefinition("output", NodeType.OUTPUT, "output", "输出",
                outputConfig, 320, 120, RetryPolicy.NONE, ResumeSafety.SAFE);
        NodeDefinition end = new NodeDefinition("end", NodeType.END, "end", "结束",
                JsonNodeFactory.instance.objectNode(), 560, 120, RetryPolicy.NONE, ResumeSafety.SAFE);
        EdgeDefinition startOutput = new EdgeDefinition("edge-start-output", "start", "output",
                EdgeKind.NORMAL, null, 0, false);
        EdgeDefinition outputEnd = new EdgeDefinition("edge-output-end", "output", "end",
                EdgeKind.NORMAL, null, 0, false);
        return new GraphDefinition(GraphDefinition.CURRENT_SCHEMA, id,
                name == null || name.isBlank() ? "新工作流" : name, "", 1, GraphKind.CUSTOM,
                "start", List.of(start, output, end), List.of(startOutput, outputEnd), 200);
    }

    private static String defaultLabel(NodeType type) {
        return switch (type) {
            case AGENT -> "智能体"; case TOOL -> "工具"; case CONDITION -> "条件";
            case TRANSFORM -> "转换"; case HUMAN_INPUT -> "人工输入"; case OUTPUT -> "输出";
            case START -> "开始"; case END -> "结束"; case SYSTEM -> "系统阶段";
        };
    }

    private static JsonNode defaultConfig(NodeType type) {
        var o = JsonNodeFactory.instance.objectNode();
        switch (type) {
            case AGENT -> { o.put("prompt", "你是一个严谨的助手。"); o.put("inputTemplate", "{{input}}");
                o.put("outputKey", "agent.output"); o.put("maxIters", 8); o.putArray("toolGroups"); }
            case TOOL -> { o.put("toolName", ""); o.putObject("arguments"); o.putArray("toolGroups");
                o.put("outputKey", "tool.output"); }
            case TRANSFORM -> o.putArray("operations");
            case HUMAN_INPUT -> { o.put("prompt", "请补充信息"); o.put("responseKey", "human.response"); }
            case OUTPUT -> { o.put("template", "{{agent.output}}"); o.put("outputKey", "output"); }
            default -> { }
        }
        return o;
    }
}
