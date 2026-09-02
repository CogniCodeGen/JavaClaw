package com.javaclaw.builtin.extensions;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.LoopContracts;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.OrchestratedToolEvidence;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoopManagementTest {
    @Test
    void strongTypedSaveBuildsRevisionedDefinitionAndScalarAssertion() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new LoopExtension());
        LoopContracts.Definition created = support.decode(
                started.command(support.request("definition/save", userConfirmation(), Optional.of("loop-create"), 0)),
                LoopContracts.Definition.class);
        LoopContracts.Definition updated = support.decode(
                started.command(support.request("definition/save", numberAssertion(), Optional.of("loop-update"), 1)),
                LoopContracts.Definition.class);

        LoopContracts.Definition read = support.decode(
                started.query(support.request("read", new DocumentContracts.Key("managed-loop"), Optional.empty(), 0)),
                LoopContracts.Definition.class);
        ViewQueryResult editor = support.decode(started.query(detailRequest(support, 2)), ViewQueryResult.class);

        assertEquals(1, created.revision());
        assertEquals(2, updated.revision());
        assertEquals(updated, read);
        assertEquals(
                "{\"value\":42}",
                updated.verificationRule().expectedValue().orElseThrow().json());
        LoopVerification.requireSatisfied(
                java.util.List.of(new OrchestratedToolEvidence(
                        "javaclaw.verify", true, support.payloads.encode(Map.of("attempts", 42)))),
                updated.verificationRule(),
                support.payloads);
        assertEquals(2, editor.revision());
        assertTrue(editor.values().json().contains("\"expectedValueKind\":\"NUMBER\""));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(
                        support.request("definition/save", userConfirmation(), Optional.of("loop-stale"), 1)));
        assertThrows(IllegalArgumentException.class, () -> started.query(detailRequest(support, 1)));
    }

    @Test
    void editFormUsesSelectedDefinitionIdentityAndRevision() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new LoopExtension());
        ViewSchema view = started.contributions().stream()
                .filter(ExtensionContributions.View.class::isInstance)
                .map(ExtensionContributions.View.class::cast)
                .map(ExtensionContributions.View::view)
                .filter(candidate -> candidate.viewId().endsWith(".management"))
                .findFirst()
                .orElseThrow();

        ViewSchema.Form create = view.nodes().stream()
                .filter(ViewSchema.Form.class::isInstance)
                .map(ViewSchema.Form.class::cast)
                .filter(form -> form.id().equals("loop-create"))
                .findFirst()
                .orElseThrow();
        ViewSchema.Form edit = view.nodes().stream()
                .filter(ViewSchema.Form.class::isInstance)
                .map(ViewSchema.Form.class::cast)
                .filter(form -> form.id().equals("loop-edit"))
                .findFirst()
                .orElseThrow();

        assertTrue(create.fields().stream().anyMatch(field -> field.name().equals("id")));
        assertFalse(edit.fields().stream().anyMatch(field -> field.name().equals("id")));
        assertEquals(
                new ExpectedRevisionBinding.SourceRevision("definitionEditor"),
                edit.submit().expectedRevision());
        assertEquals(1, edit.submit().commandBindings().size());
        assertEquals("id", edit.submit().commandBindings().getFirst().argumentName());
        assertEquals(
                new ViewBinding("definitionEditor", "id"),
                edit.submit().commandBindings().getFirst().binding());
        assertEquals(
                java.util.List.of("id", "revision"),
                view.dataSources().stream()
                        .filter(source -> source.id().equals("definitionEditor"))
                        .findFirst()
                        .orElseThrow()
                        .argumentBindings()
                        .stream()
                        .map(com.javaclaw.extension.spi.ViewArgumentBinding::argument)
                        .toList());
        assertFalse(started.contributions().stream()
                .filter(ExtensionContributions.Command.class::isInstance)
                .map(ExtensionContributions.Command.class::cast)
                .anyMatch(command -> command.operations().contains("put")));
    }

    private static com.javaclaw.extension.spi.ExtensionRequest detailRequest(
            BuiltinExtensionTestSupport support, long revision) {
        return support.request(
                "definition/view.selected",
                new ViewQueryRequest(
                        "definitionEditor",
                        Map.of("id", "managed-loop", "revision", Long.toString(revision)),
                        "",
                        1,
                        Optional.empty()),
                Optional.empty(),
                0);
    }

    private static LoopContracts.ManagementSaveRequest userConfirmation() {
        return new LoopContracts.ManagementSaveRequest(
                "managed-loop",
                "管理循环",
                "用真实证据完成目标",
                "推进一轮并收集证据",
                10,
                3,
                LoopContracts.VerificationKind.USER_CONFIRMATION,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static LoopContracts.ManagementSaveRequest numberAssertion() {
        return new LoopContracts.ManagementSaveRequest(
                "managed-loop",
                "管理循环",
                "用真实证据完成目标",
                "推进一轮并收集证据",
                8,
                2,
                LoopContracts.VerificationKind.TOOL_FIELD_ASSERTION,
                Optional.of("javaclaw.verify"),
                Optional.empty(),
                Optional.of("/attempts"),
                Optional.of(LoopContracts.ManagementValueKind.NUMBER),
                Optional.of("42"));
    }
}
