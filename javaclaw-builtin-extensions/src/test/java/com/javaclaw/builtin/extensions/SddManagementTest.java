package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.SddContracts;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.OrchestratedToolEvidence;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewStructuredListField;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SddManagementTest {
    @Test
    void createAndEditDeriveDigestsRevisionTimeAndScalarEvidence() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SddExtension());

        SddContracts.Definition created = support.decode(
                started.command(support.request("definition/create", exitCodeInput(), Optional.of("create-sdd"), 0)),
                SddContracts.Definition.class);
        SddContracts.Definition updated = support.decode(
                started.command(support.request("definition/update", fieldInput(), Optional.of("update-sdd"), 1)),
                SddContracts.Definition.class);
        SddContracts.Definition persisted = support.decode(
                started.query(support.request("read", new DocumentContracts.Key("managed-sdd"), Optional.empty(), 0)),
                SddContracts.Definition.class);

        assertEquals(1, created.revision());
        assertEquals(BuiltinExtensionTestSupport.NOW, created.updatedAt());
        assertEquals(2, updated.revision());
        assertNotEquals(created.specificationDigest(), updated.specificationDigest());
        assertNotEquals(created.taskDigest(), updated.taskDigest());
        assertEquals(
                "{\"value\":42}",
                updated.verificationRule().expectedValue().orElseThrow().json());
        assertTrue(SddVerification.satisfied(
                List.of(new OrchestratedToolEvidence(
                        "javaclaw.verify", true, support.payloads.encode(Map.of("passed", 42)))),
                updated.verificationRule(),
                support.payloads));
        assertEquals(updated, persisted);
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request("definition/update", fieldInput(), Optional.of("stale-sdd"), 1)));
    }

    @Test
    void detailCarriesServerDigestsAndRejectsStaleRevisionOrInvalidRule() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SddExtension());
        SddContracts.Definition created = support.decode(
                started.command(support.request("definition/create", exitCodeInput(), Optional.of("create-sdd"), 0)),
                SddContracts.Definition.class);
        ViewQueryResult detail = support.decode(started.query(detailRequest(support, 1)), ViewQueryResult.class);
        started.command(support.request("definition/update", fieldInput(), Optional.of("update-sdd"), 1));

        assertTrue(detail.values().json().contains(created.specificationDigest()));
        assertTrue(detail.values().json().contains(created.taskDigest()));
        assertTrue(detail.values().json().contains("\"itemKey\":\"task-1\""));
        assertThrows(IllegalArgumentException.class, () -> started.query(detailRequest(support, 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(
                        support.request("definition/create", invalidRuleInput(), Optional.of("invalid-sdd"), 0)));
    }

    @Test
    void schemaUsesStructuredTasksAndAuthoritativeEditIdentity() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SddExtension());
        ViewSchema view = managementView(started);
        ViewSchema.Form create = form(view, "sdd-create");
        ViewSchema.Form edit = form(view, "sdd-edit");

        assertTrue(create.fields().stream().anyMatch(field -> field.name().equals("id")));
        assertFalse(edit.fields().stream().anyMatch(field -> field.name().equals("id")));
        assertInstanceOf(ViewStructuredListField.class, field(edit, "tasks"));
        assertEquals(
                new ExpectedRevisionBinding.SourceRevision("definitionEditor"),
                edit.submit().expectedRevision());
        assertEquals("id", edit.submit().commandBindings().getFirst().argumentName());
        assertEquals(
                new ViewBinding("definitionEditor", "id"),
                edit.submit().commandBindings().getFirst().binding());
        assertFalse(started.contributions().stream()
                .filter(ExtensionContributions.Command.class::isInstance)
                .map(ExtensionContributions.Command.class::cast)
                .anyMatch(command -> command.operations().contains("put")));
    }

    @Test
    void approvalAcceptsOnlyDigestDerivedFromFrozenDefinition() {
        SddContracts.Definition definition = definitionForApproval();
        SddContracts.Checkpoint waiting = new SddContracts.Checkpoint(
                SddContracts.Phase.SPECIFICATION_APPROVAL, 0, Optional.empty(), Optional.empty(), 0, false);

        assertThrows(
                IllegalArgumentException.class,
                () -> SddExtension.approved(
                        definition,
                        waiting,
                        new SddContracts.Approval("job", SddContracts.ApprovalKind.SPECIFICATION, "a".repeat(64))));
        SddContracts.Checkpoint approved = SddExtension.approved(
                definition,
                waiting,
                new SddContracts.Approval(
                        "job", SddContracts.ApprovalKind.SPECIFICATION, definition.specificationDigest()));
        assertEquals(SddContracts.Phase.DESIGN, approved.phase());
    }

    private static com.javaclaw.extension.spi.ExtensionRequest detailRequest(
            BuiltinExtensionTestSupport support, long revision) {
        return support.request(
                "definition/view.selected",
                new ViewQueryRequest(
                        "definitionEditor",
                        Map.of("id", "managed-sdd", "revision", Long.toString(revision)),
                        "",
                        1,
                        Optional.empty()),
                Optional.empty(),
                0);
    }

    private static SddContracts.ManagementSaveRequest exitCodeInput() {
        return new SddContracts.ManagementSaveRequest(
                "managed-sdd",
                "发布规格",
                "所有验收通过",
                "使用受治理 Turn",
                List.of(new SddContracts.ManagementTask("task-1", "实现功能")),
                SddContracts.VerificationKind.TOOL_EXIT_CODE,
                "javaclaw.verify",
                Optional.of(0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                1);
    }

    private static SddContracts.ManagementSaveRequest fieldInput() {
        return new SddContracts.ManagementSaveRequest(
                "managed-sdd",
                "发布规格",
                "所有验收和安全门禁通过",
                "使用可恢复的受治理 Turn",
                List.of(
                        new SddContracts.ManagementTask("task-1", "实现功能"),
                        new SddContracts.ManagementTask("task-2", "验证功能")),
                SddContracts.VerificationKind.TOOL_FIELD_ASSERTION,
                "javaclaw.verify",
                Optional.empty(),
                Optional.of("/passed"),
                Optional.of(SddContracts.ManagementValueKind.NUMBER),
                Optional.of("42"),
                2);
    }

    private static SddContracts.ManagementSaveRequest invalidRuleInput() {
        return new SddContracts.ManagementSaveRequest(
                "invalid-sdd",
                "无效规格",
                "验收",
                "设计",
                List.of(new SddContracts.ManagementTask("task-1", "任务")),
                SddContracts.VerificationKind.TOOL_FIELD_ASSERTION,
                "javaclaw.verify",
                Optional.of(0),
                Optional.of("/passed"),
                Optional.of(SddContracts.ManagementValueKind.BOOLEAN),
                Optional.of("true"),
                1);
    }

    private static SddContracts.Definition definitionForApproval() {
        return new SddContracts.Definition(
                "approval-sdd",
                1,
                "审批规格",
                new SddContracts.Content("验收条件", "设计", List.of("任务")),
                new SddContracts.VerificationRule(
                        SddContracts.VerificationKind.TOOL_EXIT_CODE,
                        "javaclaw.verify",
                        Optional.of(0),
                        Optional.empty(),
                        Optional.empty()),
                1,
                BuiltinExtensionTestSupport.NOW);
    }

    private static ViewSchema managementView(BuiltinExtensionTestSupport.Started started) {
        return started.contributions().stream()
                .filter(ExtensionContributions.View.class::isInstance)
                .map(ExtensionContributions.View.class::cast)
                .map(ExtensionContributions.View::view)
                .filter(view -> view.viewId().endsWith(".management"))
                .findFirst()
                .orElseThrow();
    }

    private static ViewSchema.Form form(ViewSchema view, String id) {
        return view.nodes().stream()
                .filter(ViewSchema.Form.class::isInstance)
                .map(ViewSchema.Form.class::cast)
                .filter(form -> form.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static com.javaclaw.extension.spi.ViewFormField field(ViewSchema.Form form, String name) {
        return form.fields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
