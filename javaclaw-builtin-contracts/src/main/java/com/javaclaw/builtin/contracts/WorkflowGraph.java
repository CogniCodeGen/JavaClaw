package com.javaclaw.builtin.contracts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Workflow Graph 的结构和确定性分支校验。 */
final class WorkflowGraph {
    private WorkflowGraph() {}

    static void validate(List<WorkflowContracts.Node> nodes, List<WorkflowContracts.Edge> edges) {
        Map<String, WorkflowContracts.Node> indexed = index(nodes);
        WorkflowContracts.Node start = requireStartAndEnd(nodes);
        Map<String, List<WorkflowContracts.Edge>> outgoing = outgoing(edges, indexed.keySet());
        indexed.values().forEach(node -> requireEdgeShape(node, outgoing.getOrDefault(node.id(), List.of())));
        Set<String> reached = reachable(start.id(), outgoing);
        if (reached.size() != nodes.size()) {
            throw new IllegalArgumentException("Workflow contains unreachable nodes");
        }
    }

    private static WorkflowContracts.Node requireStartAndEnd(List<WorkflowContracts.Node> nodes) {
        List<WorkflowContracts.Node> starts = nodes.stream()
                .filter(node -> node.kind() == WorkflowContracts.NodeKind.START)
                .toList();
        boolean hasEnd = nodes.stream().anyMatch(node -> node.kind() == WorkflowContracts.NodeKind.END);
        if (starts.size() != 1 || !hasEnd) {
            throw new IllegalArgumentException("Workflow requires one START and at least one END");
        }
        return starts.getFirst();
    }

    private static Map<String, WorkflowContracts.Node> index(List<WorkflowContracts.Node> nodes) {
        Map<String, WorkflowContracts.Node> indexed = new HashMap<>();
        for (WorkflowContracts.Node node : nodes) {
            if (indexed.putIfAbsent(node.id(), node) != null) {
                throw new IllegalArgumentException("duplicate Workflow node id");
            }
        }
        return indexed;
    }

    private static Map<String, List<WorkflowContracts.Edge>> outgoing(
            List<WorkflowContracts.Edge> edges, Set<String> nodeIds) {
        Map<String, List<WorkflowContracts.Edge>> outgoing = new HashMap<>();
        Set<String> identities = new HashSet<>();
        for (WorkflowContracts.Edge edge : edges) {
            if (!nodeIds.contains(edge.from()) || !nodeIds.contains(edge.to())) {
                throw new IllegalArgumentException("Workflow edge references unknown node");
            }
            String identity = edge.from() + "\u0000" + edge.to() + "\u0000"
                    + edge.branch().orElse("");
            if (!identities.add(identity)) {
                throw new IllegalArgumentException("duplicate Workflow edge");
            }
            outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
        }
        return outgoing;
    }

    private static void requireEdgeShape(WorkflowContracts.Node node, List<WorkflowContracts.Edge> outgoing) {
        if (node.kind() == WorkflowContracts.NodeKind.END) {
            if (!outgoing.isEmpty()) {
                throw new IllegalArgumentException("END node must not have outgoing edges");
            }
            return;
        }
        if (node.kind() == WorkflowContracts.NodeKind.CONDITION) {
            requireConditionEdges(outgoing);
            return;
        }
        if (outgoing.size() != 1 || outgoing.getFirst().branch().isPresent()) {
            throw new IllegalArgumentException("non-CONDITION node requires one unlabelled outgoing edge");
        }
    }

    private static void requireConditionEdges(List<WorkflowContracts.Edge> outgoing) {
        Set<String> branches =
                outgoing.stream().map(edge -> edge.branch().orElse("")).collect(java.util.stream.Collectors.toSet());
        if (outgoing.size() != 2 || !branches.equals(Set.of("TRUE", "FALSE"))) {
            throw new IllegalArgumentException("CONDITION requires exactly TRUE and FALSE edges");
        }
    }

    private static Set<String> reachable(String start, Map<String, List<WorkflowContracts.Edge>> outgoing) {
        Set<String> reached = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (reached.add(current)) {
                outgoing.getOrDefault(current, List.of()).stream()
                        .map(WorkflowContracts.Edge::to)
                        .forEach(queue::addLast);
            }
        }
        return reached;
    }
}
