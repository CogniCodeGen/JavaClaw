package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.WorkflowContracts;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowViewBindingTest {
    @Test
    void graphViewDeclaresDefinitionMasterAndServesSelectedRevision() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        WorkflowExtension extension = new WorkflowExtension();
        BuiltinExtensionTestSupport.Started started = support.start(extension);
        WorkflowContracts.Definition definition = definition();
        started.command(support.request(
                WorkflowManagement.SAVE,
                new WorkflowDefinitionMapper(support.payloads).management(definition),
                Optional.of("workflow-put"),
                0));
        ViewSchema view = started.contributions().stream()
                .filter(ExtensionContributions.View.class::isInstance)
                .map(ExtensionContributions.View.class::cast)
                .filter(candidate -> candidate.contributionId().equals("graph.view"))
                .findFirst()
                .orElseThrow()
                .view();

        assertEquals(
                List.of("documents", "graphNodes", "graphEdges"),
                view.dataSources().stream().map(source -> source.id()).toList());
        assertEquals(2, view.dataSources().get(1).argumentBindings().size());
        ViewQueryRequest nodes = new ViewQueryRequest(
                "graphNodes",
                Map.of("definitionId", definition.id(), "definitionRevision", "1"),
                "",
                200,
                Optional.empty());
        ViewQueryResult result = support.decode(
                started.query(support.request("graph/node/view.list", nodes, Optional.empty(), 0)),
                ViewQueryResult.class);

        assertEquals(3, result.rows().size());
        assertEquals(1, result.revision());
    }

    private static WorkflowContracts.Definition definition() {
        WorkflowContracts.Node start = new WorkflowContracts.Node(
                "start",
                WorkflowContracts.NodeKind.START,
                "开始",
                Optional.empty(),
                WorkflowContracts.NodeConfig.empty());
        WorkflowContracts.Node turn = new WorkflowContracts.Node(
                "turn",
                WorkflowContracts.NodeKind.TURN,
                "执行",
                Optional.of("执行发布检查"),
                WorkflowContracts.NodeConfig.empty());
        WorkflowContracts.Node end = new WorkflowContracts.Node(
                "end", WorkflowContracts.NodeKind.END, "结束", Optional.empty(), WorkflowContracts.NodeConfig.empty());
        return new WorkflowContracts.Definition(
                "workflow",
                1,
                "发布流程",
                List.of(start, turn, end),
                List.of(
                        new WorkflowContracts.Edge("start", "turn", Optional.empty()),
                        new WorkflowContracts.Edge("turn", "end", Optional.empty())),
                10,
                NOW);
    }
}
