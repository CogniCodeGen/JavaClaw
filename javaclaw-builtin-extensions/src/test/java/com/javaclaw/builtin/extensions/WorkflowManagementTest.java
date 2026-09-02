package com.javaclaw.builtin.extensions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.WorkflowContracts;
import com.javaclaw.builtin.contracts.WorkflowManagementContracts;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewStructuredListField;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowManagementTest {
    @Test
    void safeManagementRoundTripBuildsTypedToolAndInputConfiguration() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        WorkflowExtension extension = new WorkflowExtension();
        var started = support.start(extension);

        WorkflowContracts.Definition created = support.decode(
                started.command(support.request(
                        WorkflowManagement.SAVE, definition("workflow", "发布流程"), Optional.of("create"), 0)),
                WorkflowContracts.Definition.class);

        assertEquals(1, created.revision());
        assertEquals(4, created.nodes().size());
        WorkflowContracts.ToolConfig tool =
                created.nodes().get(1).config().tool().orElseThrow();
        assertEquals(Map.of("limit", 2), support.payloads.decode(tool.arguments(), Map.class));
        assertTrue(created.nodes().get(2).config().input().isPresent());

        WorkflowManagementContracts.SaveRequest editor = editor(started, support, "workflow", 1);
        assertEquals("workflow", editor.id());
        assertEquals(1, editor.toolArguments().size());
        assertEquals(1, editor.inputFields().size());

        WorkflowManagementContracts.SaveRequest update = new WorkflowManagementContracts.SaveRequest(
                editor.id(),
                "发布流程 2",
                editor.nodes(),
                editor.toolArguments(),
                editor.inputFields(),
                editor.edges(),
                editor.maxVisits());
        WorkflowContracts.Definition updated = support.decode(
                started.command(support.request(WorkflowManagement.SAVE, update, Optional.of("update"), 1)),
                WorkflowContracts.Definition.class);

        assertEquals(2, updated.revision());
        assertEquals("发布流程 2", updated.name());
        assertThrows(IllegalArgumentException.class, () -> editor(started, support, "workflow", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(WorkflowManagement.SAVE, update, Optional.of("stale"), 1)));
    }

    @Test
    void managementViewUsesStructuredListsAndAuthoritativeEditIdentity() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new WorkflowExtension());
        ViewSchema view = started.contributions().stream()
                .filter(ExtensionContributions.View.class::isInstance)
                .map(ExtensionContributions.View.class::cast)
                .filter(candidate -> candidate.contributionId().equals("definition.management.view"))
                .findFirst()
                .orElseThrow()
                .view();
        ViewSchema.Form create =
                assertInstanceOf(ViewSchema.Form.class, view.nodes().get(1));
        ViewSchema.Form edit =
                assertInstanceOf(ViewSchema.Form.class, view.nodes().get(2));

        assertTrue(create.fields().stream().anyMatch(field -> field.name().equals("id")));
        assertFalse(edit.fields().stream().anyMatch(field -> field.name().equals("id")));
        assertEquals("id", edit.submit().commandBindings().getFirst().argumentName());
        assertEquals(
                "definitionEditor",
                edit.submit().commandBindings().getFirst().binding().sourceId());
        assertEquals(
                List.of("id", "revision"),
                view.dataSources().stream()
                        .filter(source -> source.id().equals("definitionEditor"))
                        .findFirst()
                        .orElseThrow()
                        .argumentBindings()
                        .stream()
                        .map(com.javaclaw.extension.spi.ViewArgumentBinding::argument)
                        .toList());
        assertEquals(
                4,
                edit.fields().stream()
                        .filter(ViewStructuredListField.class::isInstance)
                        .count());
    }

    @Test
    void wrongChildOwnerAndHiddenInactiveFieldsAreRejected() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new WorkflowExtension());
        WorkflowManagementContracts.SaveRequest valid = definition("workflow", "流程");
        WorkflowManagementContracts.ToolArgumentRow wrong = new WorkflowManagementContracts.ToolArgumentRow(
                "wrong", "start", "limit", WorkflowManagementContracts.ScalarKind.NUMBER, "2");
        WorkflowManagementContracts.SaveRequest invalidOwner = new WorkflowManagementContracts.SaveRequest(
                valid.id(),
                valid.name(),
                valid.nodes(),
                List.of(wrong),
                valid.inputFields(),
                valid.edges(),
                valid.maxVisits());

        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(
                        support.request(WorkflowManagement.SAVE, invalidOwner, Optional.of("wrong-owner"), 0)));

        List<WorkflowManagementContracts.NodeRow> hidden = new java.util.ArrayList<>(valid.nodes());
        WorkflowManagementContracts.NodeRow end = hidden.getLast();
        hidden.set(hidden.size() - 1, node(end.id(), end.kind(), end.name(), "hidden instruction", "", "", 0));
        WorkflowManagementContracts.SaveRequest hiddenField = new WorkflowManagementContracts.SaveRequest(
                valid.id(),
                valid.name(),
                hidden,
                valid.toolArguments(),
                valid.inputFields(),
                valid.edges(),
                valid.maxVisits());
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(WorkflowManagement.SAVE, hiddenField, Optional.of("hidden"), 0)));
    }

    @Test
    void duplicateRowsSecretArgumentsAndHugeNumbersAreRejected() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new WorkflowExtension());
        WorkflowManagementContracts.SaveRequest valid = definition("workflow", "流程");
        WorkflowManagementContracts.ToolArgumentRow argument =
                valid.toolArguments().getFirst();

        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowManagementContracts.SaveRequest(
                        valid.id(),
                        valid.name(),
                        valid.nodes(),
                        List.of(argument, argument),
                        valid.inputFields(),
                        valid.edges(),
                        valid.maxVisits()));
        assertRejectedToolArgument(started, support, valid, toolArgument("authorization_token", "abc"), "secret");
        assertRejectedToolArgument(started, support, valid, toolArgument("limit", "1e100000"), "huge-number");
    }

    @Test
    void projectionRejectsRequiredInputFieldMissingFromProperties() {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        WorkflowContracts.Definition definition = workflowWithInvalidRequiredField(support);

        assertThrows(
                IllegalStateException.class,
                () -> new WorkflowManagementProjection(support.payloads).management(definition));
    }

    private static WorkflowManagementContracts.SaveRequest editor(
            BuiltinExtensionTestSupport.Started started, BuiltinExtensionTestSupport support, String id, long revision)
            throws Exception {
        ViewQueryRequest query = new ViewQueryRequest(
                "definitionEditor", Map.of("id", id, "revision", Long.toString(revision)), "", 1, Optional.empty());
        ViewQueryResult result = support.decode(
                started.query(support.request(WorkflowManagement.VIEW_SELECTED, query, Optional.empty(), 0)),
                ViewQueryResult.class);
        return support.payloads.decode(result.values(), WorkflowManagementContracts.SaveRequest.class);
    }

    private static void assertRejectedToolArgument(
            BuiltinExtensionTestSupport.Started started,
            BuiltinExtensionTestSupport support,
            WorkflowManagementContracts.SaveRequest valid,
            WorkflowManagementContracts.ToolArgumentRow argument,
            String key) {
        WorkflowManagementContracts.SaveRequest candidate = new WorkflowManagementContracts.SaveRequest(
                valid.id(),
                valid.name(),
                valid.nodes(),
                List.of(argument),
                valid.inputFields(),
                valid.edges(),
                valid.maxVisits());
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(WorkflowManagement.SAVE, candidate, Optional.of(key), 0)));
    }

    private static WorkflowManagementContracts.ToolArgumentRow toolArgument(String name, String value) {
        return new WorkflowManagementContracts.ToolArgumentRow(
                "argument", "tool", name, WorkflowManagementContracts.ScalarKind.NUMBER, value);
    }

    private static WorkflowContracts.Definition workflowWithInvalidRequiredField(BuiltinExtensionTestSupport support) {
        var input = new WorkflowContracts.UserInputConfig(
                "是否继续？",
                support.payloads.encode(Map.of(
                        "additionalProperties",
                        false,
                        "properties",
                        Map.of("approved", Map.of("type", "boolean")),
                        "required",
                        List.of("missing"),
                        "type",
                        "object")),
                "answer",
                Duration.ofMinutes(10));
        return new WorkflowContracts.Definition(
                "invalid-required",
                1,
                "无效输入 Schema",
                List.of(
                        workflowNode("start", WorkflowContracts.NodeKind.START, WorkflowContracts.NodeConfig.empty()),
                        workflowNode(
                                "input",
                                WorkflowContracts.NodeKind.USER_INPUT,
                                new WorkflowContracts.NodeConfig(
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of(input),
                                        Optional.empty())),
                        workflowNode("end", WorkflowContracts.NodeKind.END, WorkflowContracts.NodeConfig.empty())),
                List.of(
                        new WorkflowContracts.Edge("start", "input", Optional.empty()),
                        new WorkflowContracts.Edge("input", "end", Optional.empty())),
                10,
                Instant.parse("2026-09-01T00:00:00Z"));
    }

    private static WorkflowContracts.Node workflowNode(
            String id, WorkflowContracts.NodeKind kind, WorkflowContracts.NodeConfig config) {
        return new WorkflowContracts.Node(id, kind, id, Optional.empty(), config);
    }

    private static WorkflowManagementContracts.SaveRequest definition(String id, String name) {
        List<WorkflowManagementContracts.NodeRow> nodes = List.of(
                node("start", WorkflowContracts.NodeKind.START, "开始", "", "", "", 0),
                node("tool", WorkflowContracts.NodeKind.TOOL, "检查", "", "javaclaw.verify", "verification", 0),
                node("input", WorkflowContracts.NodeKind.USER_INPUT, "确认", "", "", "answer", 10),
                node("end", WorkflowContracts.NodeKind.END, "结束", "", "", "", 0));
        List<WorkflowManagementContracts.EdgeRow> edges = List.of(
                new WorkflowManagementContracts.EdgeRow("e1", "start", "tool", ""),
                new WorkflowManagementContracts.EdgeRow("e2", "tool", "input", ""),
                new WorkflowManagementContracts.EdgeRow("e3", "input", "end", ""));
        return new WorkflowManagementContracts.SaveRequest(
                id,
                name,
                nodes,
                List.of(new WorkflowManagementContracts.ToolArgumentRow(
                        "argument", "tool", "limit", WorkflowManagementContracts.ScalarKind.NUMBER, "2")),
                List.of(new WorkflowManagementContracts.InputFieldRow(
                        "field", "input", "approved", WorkflowManagementContracts.InputKind.BOOLEAN, true)),
                edges,
                100);
    }

    private static WorkflowManagementContracts.NodeRow node(
            String id,
            WorkflowContracts.NodeKind kind,
            String name,
            String instruction,
            String toolName,
            String resultField,
            int timeoutMinutes) {
        String inputPrompt = kind == WorkflowContracts.NodeKind.USER_INPUT ? "是否继续？" : "";
        return new WorkflowManagementContracts.NodeRow(
                id,
                kind,
                name,
                instruction,
                toolName,
                kind == WorkflowContracts.NodeKind.TOOL ? resultField : "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                inputPrompt,
                kind == WorkflowContracts.NodeKind.USER_INPUT ? resultField : "",
                timeoutMinutes,
                "");
    }
}
