package com.javaclaw.framework.extension;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.*;
import com.javaclaw.workflow.model.*;
import com.javaclaw.workflow.runtime.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class FrameworkWorkflowExtensionProviderTest {

    @Test
    void workflowRunsKeepExactNodeVersionWhileNewRunsUseTheUpgrade() throws Exception {
        try (ExtensionManager manager = manager()) {
            ExtensionArtifact v1 = ExtensionArtifact.builtin(new NodeExtension("1.0.0", "v1"));
            ExtensionArtifact v2 = ExtensionArtifact.builtin(new NodeExtension("2.0.0", "v2"));
            manager.publish(List.of(v1));
            FrameworkWorkflowExtensionProvider provider = new FrameworkWorkflowExtensionProvider(
                    manager, agents(), request -> CompletableFuture.failedFuture(
                    new UnsupportedOperationException()));

            WorkflowExtensionPlan runA = provider.compile(graph());
            assertEquals("v1", execute(runA));
            assertEquals("1.0.0", runA.locks().getFirst().version().toString());

            manager.publish(List.of(v1, v2));
            WorkflowExtensionPlan runB = provider.compile(graph());
            assertEquals("v1", execute(runA), "an in-flight run must not switch handlers");
            assertEquals("v2", execute(runB));
            assertEquals("v2", provider.currentTemplates().getFirst()
                    .definition().path("marker").asText());

            runA.close();
            runB.close();
        }
    }

    @Test
    void restoreNeverSilentlySubstitutesAnotherVersion() {
        try (ExtensionManager manager = manager()) {
            ExtensionArtifact v1 = ExtensionArtifact.builtin(new NodeExtension("1.0.0", "v1"));
            ExtensionArtifact v2 = ExtensionArtifact.builtin(new NodeExtension("2.0.0", "v2"));
            manager.publish(List.of(v1));
            FrameworkWorkflowExtensionProvider provider = new FrameworkWorkflowExtensionProvider(
                    manager, agents(), request -> CompletableFuture.failedFuture(
                    new UnsupportedOperationException()));
            WorkflowExtensionPlan old = provider.compile(graph());
            List<ExtensionLock> locks = old.locks();

            manager.publish(List.of(v2));
            assertTrue(provider.restore(locks).isEmpty());
            old.close();
        }
    }

    private static String execute(WorkflowExtensionPlan plan) throws Exception {
        NodeDefinition node = graph().nodes().stream()
                .filter(value -> value.executorType().equals("test.node")).findFirst().orElseThrow();
        NodeResult result = plan.find("test.node").orElseThrow().execute(
                new NodeExecutionContext("run", "thread", node, new GraphState(),
                        new com.javaclaw.workflow.runtime.CancellationToken(),
                        GraphListener.NOOP, WorkflowExecutionServices.EMPTY));
        return new GraphState().apply(result.patch()).get("extension.marker").asText();
    }

    private static GraphDefinition graph() {
        ObjectNode empty = JsonNodeFactory.instance.objectNode();
        List<NodeDefinition> nodes = List.of(
                new NodeDefinition("start", NodeType.START, "start", "Start", empty,
                        0, 0, com.javaclaw.workflow.model.RetryPolicy.NONE, ResumeSafety.SAFE),
                new NodeDefinition("extension", NodeType.SYSTEM, "test.node", "Extension", empty,
                        0, 0, com.javaclaw.workflow.model.RetryPolicy.NONE, ResumeSafety.SAFE),
                new NodeDefinition("output", NodeType.OUTPUT, "output", "Output",
                        JsonNodeFactory.instance.objectNode().put("template", "{{extension.marker}}"),
                        0, 0, com.javaclaw.workflow.model.RetryPolicy.NONE, ResumeSafety.SAFE),
                new NodeDefinition("end", NodeType.END, "end", "End", empty,
                        0, 0, com.javaclaw.workflow.model.RetryPolicy.NONE, ResumeSafety.SAFE));
        return new GraphDefinition(1, "test", "Test", "", 1, GraphKind.CUSTOM,
                "start", nodes, List.of(
                edge("a", "start", "extension"),
                edge("b", "extension", "output"),
                edge("c", "output", "end")), 20);
    }

    private static EdgeDefinition edge(String id, String source, String target) {
        return new EdgeDefinition(id, source, target, EdgeKind.NORMAL, null, 0, false);
    }

    private static ExtensionManager manager() {
        return new ExtensionManager(new ExtensionContext(
                Clock.systemUTC(), Runnable::run,
                request -> CompletableFuture.failedFuture(new UnsupportedOperationException())));
    }

    private static AgentClient agents() {
        return new AgentClient() {
            @Override public RunHandle start(RunRequest request) { throw new UnsupportedOperationException(); }
            @Override public RunHandle resume(RunId runId, ResumeCommand command) {
                throw new UnsupportedOperationException();
            }
            @Override public boolean cancel(RunId runId, CancelReason reason) { return false; }
            @Override public RunSnapshot get(RunId runId) { throw new UnsupportedOperationException(); }
        };
    }

    private record NodeExtension(String version, String marker)
            implements AgentFrameworkExtension {
        @Override
        public ExtensionDescriptor descriptor() {
            return new ExtensionDescriptor(
                    "test.workflow", SemanticVersion.parse(version), ">=2.0.0 <3.0.0",
                    ">=2.0.0 <3.0.0", List.of(), Set.of(), ExtensionScope.PLAN_SCOPED,
                    HotUpdateCompatibility.PLAN_ISOLATED, 1, Map.of());
        }

        @Override
        public void register(ExtensionRegistrar registrar) {
            ObjectNode schema = JsonNodeFactory.instance.objectNode();
            schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
            schema.put("type", "object");
            schema.put("additionalProperties", false);
            registrar.workflowNode(new WorkflowNodeContribution(
                    "test.node", schema, JsonNodeFactory.instance.objectNode(), invocation -> {
                ObjectNode values = JsonNodeFactory.instance.objectNode();
                values.put("extension.marker", marker);
                return WorkflowNodeResult.next(values);
            }));
            registrar.workflowTemplate(new WorkflowTemplateContribution(
                    "test.template", "Test", JsonNodeFactory.instance.objectNode()
                    .put("marker", marker)));
        }
    }
}
