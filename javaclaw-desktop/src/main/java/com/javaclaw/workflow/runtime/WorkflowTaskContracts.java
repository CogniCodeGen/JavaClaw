package com.javaclaw.workflow.runtime;

import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.api.TaskCriterionV3;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.CapabilityMetadata;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.util.ProjectAccessPolicy;
import com.javaclaw.workflow.model.EdgeKind;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.GraphKind;
import com.javaclaw.workflow.model.NodeDefinition;
import com.javaclaw.workflow.model.NodeType;
import com.javaclaw.workflow.node.TemplateRenderer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Compiles graph definitions into conservative receipt-matchable task contracts. */
final class WorkflowTaskContracts {
    private WorkflowTaskContracts() {}
    static TaskContractV3 from(GraphRun run, AgentClient capabilities) {
        GraphDefinition graph = run.definition();
        List<TaskCriterionV3> criteria = new ArrayList<>();
        List<NodeDefinition> path = linearPath(graph);
        boolean reliable = path != null;
        for (NodeDefinition node : reliable ? path : graph.nodes()) {
            if (node.type() != NodeType.TOOL) continue;
            TaskCriterionV3 criterion = criterion(node, capabilities);
            if (criterion == null) reliable = false;
            else criteria.add(criterion);
        }
        if (criteria.isEmpty()) reliable = false;
        return new TaskContractV3(3, run.state().get("input").asText(""),
                criteria, true, reliable, "workflow-definition");
    }

    private static List<NodeDefinition> linearPath(GraphDefinition graph) {
        if (graph.kind() != GraphKind.CUSTOM || graph.edges().size() != graph.nodes().size() - 1) {
            return null;
        }
        List<NodeDefinition> path = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String cursor = graph.startNodeId();
        while (visited.add(cursor)) {
            String current = cursor;
            NodeDefinition node = graph.nodes().stream().filter(value -> value.id().equals(current))
                    .findFirst().orElse(null);
            if (node == null || (node.type() != NodeType.START && node.type() != NodeType.END
                    && node.type() != NodeType.TOOL && node.type() != NodeType.OUTPUT)) return null;
            path.add(node);
            var outgoing = graph.edges().stream().filter(edge -> edge.source().equals(current)).toList();
            if (node.type() == NodeType.END) {
                return outgoing.isEmpty() && visited.size() == graph.nodes().size() ? path : null;
            }
            if (outgoing.size() != 1 || outgoing.getFirst().kind() != EdgeKind.NORMAL) return null;
            cursor = outgoing.getFirst().target();
        }
        return null;
    }

    private static TaskCriterionV3 criterion(NodeDefinition node, AgentClient capabilities) {
        String tool = node.config().path("toolName").asText("");
        var args = node.config().path("arguments");
        if (!args.isObject()) return null;
        CapabilityMetadata capability = capabilities.capabilityForTool(tool).orElse(null);
        if (capability == null || capability.operation().isBlank()) return null;
        String target = switch (capability.targetSource()) {
            case ARGUMENT -> literal(args.path(capability.targetArgument()).asText(""));
            case FIXED -> capability.fixedTarget();
            case DECLARED -> literal(node.config().path("expectedTarget").asText(""));
        };
        if (target == null) return null;
        if (capability.targetKind() == CapabilityMetadata.TargetKind.FILE) {
            try {
                target = ProjectAccessPolicy.resolveProjectPath(target)
                        .toAbsolutePath().normalize().toString();
            } catch (RuntimeException invalid) {
                return null;
            }
        }
        EffectReceiptV1.Status required = capability.evidenceCeiling();
        return new TaskCriterionV3("node:" + node.id(), node.label(), capability.id(),
                capability.targetKind(), target, required, "");
    }

    private static String literal(String value) {
        if (!TemplateRenderer.isStaticLiteral(value)) return null;
        return value.strip();
    }
}
