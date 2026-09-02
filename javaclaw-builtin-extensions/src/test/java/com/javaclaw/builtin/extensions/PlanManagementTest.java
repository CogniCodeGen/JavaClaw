package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.ContractDigests;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.PlanContracts;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ExtensionContributions;
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

class PlanManagementTest {
    @Test
    void createAndEditDeriveRevisionTimeAndQuestionHashes() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new PlanExtension());

        PlanContracts.Definition created = support.decode(
                started.command(support.request("definition/create", createInput(), Optional.of("create-plan"), 0)),
                PlanContracts.Definition.class);
        PlanContracts.Definition updated = support.decode(
                started.command(support.request("definition/update", updateInput(), Optional.of("update-plan"), 1)),
                PlanContracts.Definition.class);
        PlanContracts.Definition persisted = support.decode(
                started.query(support.request("read", new DocumentContracts.Key("managed-plan"), Optional.empty(), 0)),
                PlanContracts.Definition.class);

        assertEquals(1, created.revision());
        assertEquals(BuiltinExtensionTestSupport.NOW, created.updatedAt());
        assertEquals(
                ContractDigests.sha256("选择持久化方案"),
                created.openQuestions().getFirst().contentHash());
        assertEquals(
                created.openQuestions().getFirst().contentHash(),
                created.openQuestions().getFirst().decision().orElseThrow().questionHash());
        assertEquals(2, updated.revision());
        assertNotEquals(
                created.openQuestions().getFirst().contentHash(),
                updated.openQuestions().getFirst().contentHash());
        assertEquals(updated, persisted);
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(
                        support.request("definition/update", updateInput(), Optional.of("stale-plan"), 1)));
    }

    @Test
    void exactDetailRejectsStaleSelectionAndGraphRejectsCycle() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new PlanExtension());
        started.command(support.request("definition/create", createInput(), Optional.of("create-plan"), 0));

        ViewQueryResult detail = support.decode(started.query(detailRequest(support, 1)), ViewQueryResult.class);
        started.command(support.request("definition/update", updateInput(), Optional.of("update-plan"), 1));

        assertEquals(1, detail.revision());
        assertTrue(detail.values().json().contains("\"id\":\"managed-plan\""));
        assertTrue(detail.values().json().contains("\"itemKey\":\"build\""));
        assertThrows(IllegalArgumentException.class, () -> started.query(detailRequest(support, 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(
                        support.request("definition/create", cyclicInput(), Optional.of("cycle-plan"), 0)));
    }

    @Test
    void schemaUsesStructuredListsAndAuthoritativeEditIdentity() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new PlanExtension());
        ViewSchema view = managementView(started);
        ViewSchema.Form create = form(view, "plan-create");
        ViewSchema.Form edit = form(view, "plan-edit");

        assertTrue(create.fields().stream().anyMatch(field -> field.name().equals("id")));
        assertFalse(edit.fields().stream().anyMatch(field -> field.name().equals("id")));
        assertInstanceOf(ViewStructuredListField.class, field(edit, "risks"));
        assertInstanceOf(ViewStructuredListField.class, field(edit, "openQuestions"));
        assertInstanceOf(ViewStructuredListField.class, field(edit, "steps"));
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

    private static com.javaclaw.extension.spi.ExtensionRequest detailRequest(
            BuiltinExtensionTestSupport support, long revision) {
        return support.request(
                "definition/view.selected",
                new ViewQueryRequest(
                        "definitionEditor",
                        Map.of("id", "managed-plan", "revision", Long.toString(revision)),
                        "",
                        1,
                        Optional.empty()),
                Optional.empty(),
                0);
    }

    private static PlanContracts.ManagementSaveRequest createInput() {
        return input("选择持久化方案", "H2", List.of());
    }

    private static PlanContracts.ManagementSaveRequest updateInput() {
        return input("选择生产持久化方案", "PostgreSQL", List.of());
    }

    private static PlanContracts.ManagementSaveRequest input(
            String question, String decision, List<String> buildDependencies) {
        return new PlanContracts.ManagementSaveRequest(
                "managed-plan",
                "破坏性升级",
                "全部验收门禁通过",
                "只修改平台核心",
                List.of(new PlanContracts.ManagementRisk("risk-1", "需要回滚策略")),
                List.of(new PlanContracts.ManagementOpenQuestion(
                        "question-1", "database", question, Optional.of(decision))),
                List.of(
                        new PlanContracts.ManagementStep("build", "build", "构建", "执行构建", "构建成功", buildDependencies),
                        new PlanContracts.ManagementStep("verify", "verify", "验证", "执行测试", "测试通过", List.of("build"))));
    }

    private static PlanContracts.ManagementSaveRequest cyclicInput() {
        return new PlanContracts.ManagementSaveRequest(
                "cycle-plan",
                "循环计划",
                "验证图",
                "测试",
                List.of(),
                List.of(),
                List.of(
                        new PlanContracts.ManagementStep("a-row", "a", "A", "执行 A", "完成 A", List.of("b")),
                        new PlanContracts.ManagementStep("b-row", "b", "B", "执行 B", "完成 B", List.of("a"))));
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
