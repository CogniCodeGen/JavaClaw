package com.javaclaw.workflow.runtime;

import com.javaclaw.workflow.model.EdgeDefinition;
import com.javaclaw.workflow.model.EdgeKind;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.GraphKind;
import com.javaclaw.workflow.model.NodeDefinition;
import com.javaclaw.workflow.model.NodeType;
import com.javaclaw.workflow.model.ConditionRule;
import com.javaclaw.workflow.node.StatePathValidator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.javaclaw.workflow.runtime.ValidationIssue.Severity.ERROR;

/** 发布与运行共用的结构校验器。 */
public final class GraphValidator {
    private GraphValidator() {}

    public static List<ValidationIssue> validate(GraphDefinition graph, NodeExecutorRegistry registry) {
        List<ValidationIssue> out = new ArrayList<>();
        if (graph == null) return List.of(new ValidationIssue(ERROR, "graph", "图定义不能为空"));
        if (graph.id().isBlank()) out.add(new ValidationIssue(ERROR, "graph", "图 ID 不能为空"));
        if (graph.name().isBlank()) out.add(new ValidationIssue(ERROR, "graph", "图名称不能为空"));
        if (graph.maxSteps() < 1 || graph.maxSteps() > 10_000) {
            out.add(new ValidationIssue(ERROR, "graph", "maxSteps 必须在 1..10000 之间"));
        }

        Map<String, NodeDefinition> nodes = new HashMap<>();
        for (NodeDefinition node : graph.nodes()) {
            if (node.id().isBlank()) {
                out.add(new ValidationIssue(ERROR, "node", "节点 ID 不能为空"));
                continue;
            }
            if (!node.id().matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}")) {
                out.add(new ValidationIssue(ERROR, node.id(), "节点 ID 只能包含字母、数字、_、-、. 且不能以数字开头"));
            }
            if (nodes.putIfAbsent(node.id(), node) != null) {
                out.add(new ValidationIssue(ERROR, node.id(), "节点 ID 重复"));
            }
            if (graph.kind() == GraphKind.CUSTOM && "system.pipeline".equals(node.executorType())) {
                out.add(new ValidationIssue(ERROR, node.id(),
                        "系统流水线节点依赖内置模式上下文；复制后请替换为公共节点再发布"));
            }
            if (graph.kind() == GraphKind.CUSTOM && !matchesPublicExecutor(node)) {
                out.add(new ValidationIssue(ERROR, node.id(),
                        "自定义节点类型与执行器不匹配: " + node.type() + " / " + node.executorType()));
            }
            if (graph.kind() == GraphKind.CUSTOM && requiresConfirmedRecovery(node)
                    && node.resumeSafety() != com.javaclaw.workflow.model.ResumeSafety.CONFIRM_RETRY) {
                out.add(new ValidationIssue(ERROR, node.id(),
                        "TOOL 和带工具能力的 AGENT 必须使用 CONFIRM_RETRY 恢复策略"));
            }
            NodeExecutor executor = registry == null ? null : registry.find(node.executorType()).orElse(null);
            if (executor == null) {
                out.add(new ValidationIssue(ERROR, node.id(), "未知节点执行器: " + node.executorType()));
            } else {
                for (String message : executor.validate(node)) {
                    out.add(new ValidationIssue(ERROR, node.id(), message));
                }
            }
        }
        if (!nodes.containsKey(graph.startNodeId())) {
            out.add(new ValidationIssue(ERROR, "graph", "起始节点不存在: " + graph.startNodeId()));
        } else if (nodes.get(graph.startNodeId()).type() != NodeType.START) {
            out.add(new ValidationIssue(ERROR, graph.startNodeId(), "起始节点类型必须是 START"));
        }
        long endCount = graph.nodes().stream().filter(n -> n.type() == NodeType.END).count();
        if (endCount == 0) out.add(new ValidationIssue(ERROR, "graph", "至少需要一个 END 节点"));
        long startCount = graph.nodes().stream().filter(n -> n.type() == NodeType.START).count();
        if (startCount != 1) out.add(new ValidationIssue(ERROR, "graph", "必须且只能有一个 START 节点"));

        Set<String> edgeIds = new HashSet<>();
        Map<String, List<EdgeDefinition>> outgoing = new HashMap<>();
        for (EdgeDefinition edge : graph.edges()) {
            if (edge.id().isBlank() || !edgeIds.add(edge.id())) {
                out.add(new ValidationIssue(ERROR, edge.id(), "边 ID 为空或重复"));
            }
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                out.add(new ValidationIssue(ERROR, edge.id(), "边连接了不存在的节点"));
                continue;
            }
            if (edge.source().equals(edge.target())) {
                out.add(new ValidationIssue(ERROR, edge.id(), "禁止直接自环；循环必须经过至少两个节点"));
            }
            if (edge.kind() == EdgeKind.CONDITIONAL && !edge.defaultEdge() && edge.condition() == null) {
                out.add(new ValidationIssue(ERROR, edge.id(), "非默认条件边必须配置条件"));
            }
            if (edge.kind() == EdgeKind.CONDITIONAL && edge.defaultEdge() && edge.condition() != null) {
                out.add(new ValidationIssue(ERROR, edge.id(), "条件默认出口不能再配置条件"));
            }
            if (edge.kind() == EdgeKind.CONDITIONAL && !edge.defaultEdge() && edge.condition() != null) {
                validateCondition(edge.id(), edge.condition(), out);
            }
            if (nodes.get(edge.source()).type() == NodeType.END) {
                out.add(new ValidationIssue(ERROR, edge.id(), "END 节点不能有出边"));
            }
            outgoing.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
        }

        for (NodeDefinition node : graph.nodes()) {
            List<EdgeDefinition> edges = outgoing.getOrDefault(node.id(), List.of());
            if (node.type() != NodeType.END && edges.stream().noneMatch(e -> e.kind() != EdgeKind.ERROR)) {
                out.add(new ValidationIssue(ERROR, node.id(), "非 END 节点必须有成功出口"));
            }
            List<EdgeDefinition> conditional = edges.stream()
                    .filter(e -> e.kind() == EdgeKind.CONDITIONAL).toList();
            if (!conditional.isEmpty() && conditional.stream().filter(EdgeDefinition::defaultEdge).count() != 1) {
                out.add(new ValidationIssue(ERROR, node.id(), "条件分支必须且只能有一个默认出口"));
            }
            long normals = edges.stream().filter(e -> e.kind() == EdgeKind.NORMAL).count();
            if (normals > 1) out.add(new ValidationIssue(ERROR, node.id(), "同一节点只能有一条普通成功边"));
            long errors = edges.stream().filter(e -> e.kind() == EdgeKind.ERROR).count();
            if (errors > 1) out.add(new ValidationIssue(ERROR, node.id(), "同一节点只能有一条错误出口"));
            if (normals > 0 && !conditional.isEmpty()) {
                out.add(new ValidationIssue(ERROR, node.id(), "普通成功边与条件边不能混用"));
            }
            long priorityCount = conditional.stream().map(EdgeDefinition::priority).distinct().count();
            if (priorityCount != conditional.size()) {
                out.add(new ValidationIssue(ERROR, node.id(), "同一条件分支的优先级不能重复"));
            }
        }

        if (nodes.containsKey(graph.startNodeId())) {
            Set<String> reachable = new HashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(graph.startNodeId());
            while (!queue.isEmpty()) {
                String id = queue.removeFirst();
                if (!reachable.add(id)) continue;
                for (EdgeDefinition edge : outgoing.getOrDefault(id, List.of())) queue.add(edge.target());
            }
            for (String id : nodes.keySet()) {
                if (!reachable.contains(id)) out.add(new ValidationIssue(ERROR, id, "节点从 START 不可达"));
            }
        }
        if (graph.kind() == GraphKind.CUSTOM && hasTerminalPathWithoutOutput(graph, nodes, outgoing)) {
            out.add(new ValidationIssue(ERROR, "graph",
                    "自定义会话工作流的每条终态路径都必须经过 OUTPUT 节点"));
        }
        return List.copyOf(out);
    }

    private static void validateCondition(
            String edgeId, ConditionRule condition, List<ValidationIssue> out) {
        if (!StatePathValidator.isValid(condition.path())) {
            out.add(new ValidationIssue(ERROR, edgeId, "条件状态路径不是合法的点分路径"));
        }
        switch (condition.operator()) {
            case EXISTS -> {
                // EXISTS 只检查状态路径是否存在，不需要比较值。
            }
            case GT, GTE, LT, LTE -> {
                if (condition.value() == null || !condition.value().isNumber()) {
                    out.add(new ValidationIssue(ERROR, edgeId, "数值比较运算符的比较值必须是数字"));
                }
            }
            case EQUAL, NOT_EQUAL, CONTAINS -> {
                if (condition.value() == null) {
                    out.add(new ValidationIssue(ERROR, edgeId, "条件比较值不能为空"));
                }
            }
        }
    }

    private static boolean hasTerminalPathWithoutOutput(
            GraphDefinition graph, Map<String, NodeDefinition> nodes,
            Map<String, List<EdgeDefinition>> outgoing) {
        record Visit(String nodeId, boolean hasOutput) {}
        Set<Visit> visited = new HashSet<>();
        ArrayDeque<Visit> queue = new ArrayDeque<>();
        queue.add(new Visit(graph.startNodeId(), false));
        while (!queue.isEmpty()) {
            Visit visit = queue.removeFirst();
            if (!visited.add(visit)) continue;
            NodeDefinition node = nodes.get(visit.nodeId());
            if (node == null) continue;
            boolean hasOutput = visit.hasOutput() || node.type() == NodeType.OUTPUT;
            if (node.type() == NodeType.END && !hasOutput) return true;
            for (EdgeDefinition edge : outgoing.getOrDefault(node.id(), List.of())) {
                queue.add(new Visit(edge.target(), hasOutput));
            }
        }
        return false;
    }

    private static boolean requiresConfirmedRecovery(NodeDefinition node) {
        if ("tool".equals(node.executorType())) return true;
        return "agent".equals(node.executorType())
                && node.config().path("toolGroups").isArray()
                && !node.config().path("toolGroups").isEmpty();
    }

    private static boolean matchesPublicExecutor(NodeDefinition node) {
        String expected = switch (node.type()) {
            case START -> "start";
            case END -> "end";
            case AGENT -> "agent";
            case TOOL -> "tool";
            case CONDITION -> "condition";
            case TRANSFORM -> "transform";
            case HUMAN_INPUT -> "human_input";
            case OUTPUT -> "output";
            case SYSTEM -> null;
        };
        Set<String> publicExecutors = Set.of(
                "start", "end", "agent", "tool", "condition", "transform", "human_input", "output");
        if (publicExecutors.contains(node.executorType())) {
            return expected != null && expected.equals(node.executorType());
        }
        // 非公共执行器只能挂在显式 SYSTEM 节点上，避免 START/END 等节点绕过类型语义。
        return node.type() == NodeType.SYSTEM;
    }

    public static void requireValid(GraphDefinition graph, NodeExecutorRegistry registry) {
        List<ValidationIssue> errors = validate(graph, registry).stream()
                .filter(i -> i.severity() == ERROR).toList();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("图定义无效: " + errors.getFirst().message());
        }
    }
}
