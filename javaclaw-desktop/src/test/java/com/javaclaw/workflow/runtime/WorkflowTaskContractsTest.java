package com.javaclaw.workflow.runtime;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.workflow.model.EdgeDefinition;
import com.javaclaw.workflow.model.EdgeKind;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.GraphKind;
import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.model.NodeDefinition;
import com.javaclaw.workflow.model.NodeType;
import com.javaclaw.workflow.model.ResumeSafety;
import com.javaclaw.workflow.model.RetryPolicy;
import com.javaclaw.workflow.model.StatePatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowTaskContractsTest {
    private static final com.javaclaw.framework.api.AgentClient CAPABILITIES =
            new com.javaclaw.framework.api.AgentClient() {
                private final com.javaclaw.framework.core.TrustedCapabilityRegistry registry =
                        com.javaclaw.framework.core.TrustedCapabilityRegistry.builtins();

                @Override public java.util.Optional<com.javaclaw.framework.api.CapabilityMetadata>
                capabilityForReceipt(String tool, String operation) {
                    return registry.forReceipt(tool, operation).map(value ->
                            new com.javaclaw.framework.api.CapabilityMetadata(
                                    value.id(), value.evidenceCeiling()));
                }

                @Override public java.util.Optional<com.javaclaw.framework.api.CapabilityMetadata>
                capabilityForTool(String tool) {
                    return registry.metadataForTool(tool);
                }

                @Override public com.javaclaw.framework.api.RunHandle start(
                        com.javaclaw.framework.api.RunRequest request) {
                    throw new UnsupportedOperationException();
                }
                @Override public com.javaclaw.framework.api.RunHandle resume(
                        com.javaclaw.framework.api.RunId runId,
                        com.javaclaw.framework.api.ResumeCommand command) {
                    throw new UnsupportedOperationException();
                }
                @Override public boolean cancel(com.javaclaw.framework.api.RunId runId,
                        com.javaclaw.framework.api.CancelReason reason) {
                    throw new UnsupportedOperationException();
                }
                @Override public com.javaclaw.framework.api.RunSnapshot get(
                        com.javaclaw.framework.api.RunId runId) {
                    throw new UnsupportedOperationException();
                }
            };

    @Test
    void staticLinearEmailToolDefinesSmtpAcceptanceCondition() {
        var contract = WorkflowTaskContracts.from(run(EdgeKind.NORMAL,
                JsonNodeFactory.instance.objectNode().put("toolName", "email_send")
                        .set("arguments", JsonNodeFactory.instance.objectNode()
                                .put("to", "person@example.com"))), CAPABILITIES);
        assertTrue(contract.reliable());
        assertEquals("email.send", contract.criteria().getFirst().capabilityId());
        assertEquals("person@example.com", contract.criteria().getFirst().target());
        assertEquals(com.javaclaw.framework.spi.EffectReceiptV1.Status.ACCEPTED,
                contract.criteria().getFirst().requiredEvidence());
    }

    @Test
    void exactHostMetadataCompilesFileAndBrowserTargetsWithoutNamePrefixes() {
        var file = WorkflowTaskContracts.from(run(EdgeKind.NORMAL,
                JsonNodeFactory.instance.objectNode().put("toolName", "sys_file_copy")
                        .set("arguments", JsonNodeFactory.instance.objectNode()
                                .put("source", "input.txt").put("target", "output.txt"))), CAPABILITIES);
        assertTrue(file.reliable());
        assertEquals("file.copy", file.criteria().getFirst().capabilityId());
        assertEquals(com.javaclaw.util.ProjectAccessPolicy.resolveProjectPath("output.txt")
                .toAbsolutePath().normalize().toString(), file.criteria().getFirst().target());

        var browser = WorkflowTaskContracts.from(run(EdgeKind.NORMAL,
                JsonNodeFactory.instance.objectNode().put("toolName", "web_navigate")
                        .set("arguments", JsonNodeFactory.instance.objectNode()
                                .put("url", "https://example.com/a"))), CAPABILITIES);
        assertTrue(browser.reliable());
        assertEquals("browser.navigate", browser.criteria().getFirst().capabilityId());
        assertEquals("https://example.com/a", browser.criteria().getFirst().target());
    }

    @Test
    void runtimeOwnedTargetNeedsAnExplicitStaticExpectation() {
        var absent = WorkflowTaskContracts.from(run(EdgeKind.NORMAL,
                JsonNodeFactory.instance.objectNode().put("toolName", "desktop_session_observe")
                        .set("arguments", JsonNodeFactory.instance.objectNode()
                                .put("sessionId", "s"))), CAPABILITIES);
        var declared = WorkflowTaskContracts.from(run(EdgeKind.NORMAL,
                JsonNodeFactory.instance.objectNode().put("toolName", "desktop_session_observe")
                        .put("expectedTarget", "Notes")
                        .set("arguments", JsonNodeFactory.instance.objectNode()
                                .put("sessionId", "s"))), CAPABILITIES);
        assertFalse(absent.reliable());
        assertTrue(declared.reliable());
        assertEquals("desktop.observe", declared.criteria().getFirst().capabilityId());
        assertEquals("Notes", declared.criteria().getFirst().target());
    }

    @Test
    void branchingOrDynamicTargetCannotClaimVerifiedCompletion() {
        var dynamic = WorkflowTaskContracts.from(run(EdgeKind.NORMAL,
                JsonNodeFactory.instance.objectNode().put("toolName", "email_send")
                        .set("arguments", JsonNodeFactory.instance.objectNode()
                                .put("to", "{{recipient}}"))), CAPABILITIES);
        var conditional = WorkflowTaskContracts.from(run(EdgeKind.CONDITIONAL,
                JsonNodeFactory.instance.objectNode().put("toolName", "email_send")
                        .set("arguments", JsonNodeFactory.instance.objectNode()
                                .put("to", "person@example.com"))), CAPABILITIES);
        assertFalse(dynamic.reliable());
        assertFalse(conditional.reliable());
    }

    @Test
    void criteriaFollowExecutionPathWhenNodesAreStoredOutOfOrder() {
        var empty = JsonNodeFactory.instance.objectNode();
        var start = new NodeDefinition("start", NodeType.START, "start", "start", empty,
                0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        var first = new NodeDefinition("first", NodeType.TOOL, "first", "first email",
                emailConfig("first@example.com"), 0, 0, RetryPolicy.NONE, ResumeSafety.CONFIRM_RETRY);
        var second = new NodeDefinition("second", NodeType.TOOL, "second", "second email",
                emailConfig("second@example.com"), 0, 0, RetryPolicy.NONE, ResumeSafety.CONFIRM_RETRY);
        var end = new NodeDefinition("end", NodeType.END, "end", "end", empty,
                0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        var graph = new GraphDefinition(1, "two-emails", "two-emails", "", 1,
                GraphKind.CUSTOM, "start", List.of(second, end, first, start),
                List.of(new EdgeDefinition("a", "start", "first", EdgeKind.NORMAL, null, 0, false),
                        new EdgeDefinition("b", "first", "second", EdgeKind.NORMAL, null, 0, false),
                        new EdgeDefinition("c", "second", "end", EdgeKind.NORMAL, null, 0, false)), 8);

        var contract = WorkflowTaskContracts.from(new GraphRun(graph, "thread", new GraphState()),
                CAPABILITIES);

        assertTrue(contract.reliable());
        assertEquals(List.of("node:first", "node:second"),
                contract.criteria().stream().map(criterion -> criterion.id()).toList());
    }

    private static com.fasterxml.jackson.databind.JsonNode emailConfig(String address) {
        return JsonNodeFactory.instance.objectNode().put("toolName", "email_send")
                .set("arguments", JsonNodeFactory.instance.objectNode().put("to", address));
    }

    private static GraphRun run(EdgeKind firstEdgeKind, com.fasterxml.jackson.databind.JsonNode config) {
        var empty = JsonNodeFactory.instance.objectNode();
        var start = new NodeDefinition("start", NodeType.START, "start", "start", empty,
                0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        var tool = new NodeDefinition("send", NodeType.TOOL, "tool", "send email", config,
                0, 0, RetryPolicy.NONE, ResumeSafety.CONFIRM_RETRY);
        var end = new NodeDefinition("end", NodeType.END, "end", "end", empty,
                0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        var graph = new GraphDefinition(1, "email-flow", "email-flow", "", 1,
                GraphKind.CUSTOM, "start", List.of(start, tool, end),
                List.of(new EdgeDefinition("a", "start", "send", firstEdgeKind, null, 0, false),
                        new EdgeDefinition("b", "send", "end", EdgeKind.NORMAL, null, 0, false)), 8);
        return new GraphRun(graph, "thread", new GraphState().apply(
                StatePatch.builder().set("input", "send email").build()));
    }
}
