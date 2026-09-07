package com.javaclaw.desktop.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCommandBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewCommandBindingRendererTest {
    @Test
    void resolvesValuesAndSelectedDetailForFormCardAndRowActions() {
        FxTestSupport.run(() -> {
            RecordingInteractions interactions = new RecordingInteractions();
            VBox page = (VBox)
                    new ViewSchemaRenderer().render(schema(), data(Optional.of("definition-2"), true), interactions);
            List<Node> nodes = descendants(page);

            TextField name = control(nodes, TextField.class, "Role 名称");
            name.setText("更新后的名称");
            Button save = button(nodes, "保存 Role");
            assertFalse(save.isDisabled());
            save.fire();
            button(nodes, "启动定义").fire();
            button(nodes, "归档定义").fire();

            ViewCommandInvocation saved = interactions.commands.get(0);
            assertEquals("role-1", saved.arguments().get("roleId"));
            assertEquals(7, saved.arguments().get("roleRevision"));
            assertEquals("更新后的名称", saved.arguments().get("displayName"));
            assertEquals(9, saved.expectedRevision());

            ViewCommandInvocation started = interactions.commands.get(1);
            assertEquals("definition-2", started.arguments().get("definitionId"));
            assertEquals(4, started.arguments().get("definitionRevision"));

            ViewCommandInvocation archived = interactions.commands.get(2);
            assertEquals("definition-2", archived.arguments().get("definitionId"));
            assertEquals("role-1", archived.arguments().get("roleId"));
            assertEquals(4, archived.expectedRevision());
        });
    }

    @Test
    void authoritativeBindingsOverwriteInjectedArgumentsLast() {
        ViewAction action = roleSave();
        ViewSchema schema = new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "role.action",
                "Role",
                List.of(source("roles", "role/read")),
                List.of(new ViewSchema.Card("action", "操作", "保存", List.of(action))));
        ViewCommandBindingResolver resolver = new ViewCommandBindingResolver(schema, data(Optional.empty(), true));

        ViewCommandInvocation invocation = resolver.invocation(
                action, Map.of("roleId", "attacker", "roleRevision", -1, "displayName", "新名称"), Map.of());

        assertEquals("role-1", invocation.arguments().get("roleId"));
        assertEquals(7, invocation.arguments().get("roleRevision"));
    }

    @Test
    void disablesActionsWhenSelectionOrAuthoritativeFieldIsMissing() {
        FxTestSupport.run(() -> {
            VBox unselected = (VBox) new ViewSchemaRenderer()
                    .render(schema(), data(Optional.empty(), true), new RecordingInteractions());
            Button noSelection = button(descendants(unselected), "启动定义");
            assertTrue(noSelection.isDisabled());
            assertTrue(noSelection.getAccessibleHelp().contains("definitions.id"));

            VBox missingField = (VBox) new ViewSchemaRenderer()
                    .render(schema(), data(Optional.of("definition-2"), false), new RecordingInteractions());
            Button missingRevision = button(descendants(missingField), "启动定义");
            assertTrue(missingRevision.isDisabled());
            assertTrue(missingRevision.getAccessibleHelp().contains("definitions.revision"));
        });
    }

    private static ViewSchema schema() {
        ViewAction archive = new ViewAction(
                "归档定义",
                "definition/archive",
                Map.of(),
                Map.of("definitionId", "id"),
                new ExpectedRevisionBinding.RowField("revision"),
                true,
                binding("roleId", "roles", "id"));
        ViewAction start = new ViewAction(
                "启动定义",
                "execution/start",
                Map.of(),
                Map.of(),
                new ExpectedRevisionBinding.None(),
                false,
                binding("definitionId", "definitions", "id"),
                binding("definitionRevision", "definitions", "revision"));
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "authority",
                "权威绑定",
                List.of(source("roles", "role/read"), source("definitions", "definition/list")),
                List.of(
                        new ViewSchema.Table(
                                "definitions",
                                "定义",
                                "definitions",
                                "id",
                                List.of(new ViewSchema.Column("title", "名称", Optional.empty())),
                                ViewSelectionMode.SINGLE,
                                List.of(archive)),
                        new ViewSchema.Form("role", "Role", List.of(roleName()), roleSave()),
                        new ViewSchema.Card("start", "启动", "启动选中定义", List.of(start))));
    }

    private static ViewAction roleSave() {
        return new ViewAction(
                "保存 Role",
                "role/put",
                Map.of(),
                Map.of(),
                new ExpectedRevisionBinding.SourceRevision("roles"),
                false,
                binding("roleId", "roles", "id"),
                binding("roleRevision", "roles", "revision"));
    }

    private static ViewField roleName() {
        return new ViewField(
                "displayName",
                "Role 名称",
                ViewFieldType.TEXT,
                new ViewBinding("roles", "name"),
                Optional.empty(),
                ViewFieldValidation.required(true),
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    private static ViewData data(Optional<String> selectedDefinition, boolean includesRevision) {
        Map<String, Object> selected = includesRevision
                ? Map.of("id", "definition-2", "revision", 4, "title", "第二个定义")
                : Map.of("id", "definition-2", "title", "第二个定义");
        ViewData.Source definitions = new ViewData.Source(
                List.of(Map.of("id", "definition-1", "revision", 3, "title", "第一个定义"), selected),
                Map.of(),
                "",
                "",
                false,
                4,
                0,
                selectedDefinition);
        ViewData.Source roles = new ViewData.Source(
                List.of(), Map.of("id", "role-1", "revision", 7, "name", "原名称"), "", "", false, 9, 0, Optional.empty());
        return new ViewData(Map.of("roles", roles, "definitions", definitions));
    }

    private static ViewDataSource source(String id, String query) {
        return new ViewDataSource(id, query, Map.of(), List.of(), 20);
    }

    private static ViewCommandBinding binding(String argument, String source, String field) {
        return new ViewCommandBinding(argument, new ViewBinding(source, field));
    }

    private static List<Node> descendants(Node root) {
        List<Node> result = new ArrayList<>();
        result.add(root);
        if (root instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> result.addAll(descendants(child)));
        }
        return result;
    }

    private static Button button(List<Node> nodes, String text) {
        return nodes.stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static <T extends Node> T control(List<Node> nodes, Class<T> type, String accessibleText) {
        return nodes.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(node -> accessibleText.equals(node.getAccessibleText()))
                .findFirst()
                .orElseThrow();
    }

    private static final class RecordingInteractions implements ViewInteractionHandler {
        private final List<ViewCommandInvocation> commands = new ArrayList<>();

        @Override
        public void dirty(String formId, boolean dirty) {}

        @Override
        public void execute(ViewCommandInvocation invocation) {
            commands.add(invocation);
        }

        @Override
        public void reload() {}

        @Override
        public void page(String sourceId, ViewPageDirection direction) {}

        @Override
        public void select(String sourceId, Optional<String> selectedKey) {}

        @Override
        public CompletionStage<AttachmentRef> upload(ViewAttachmentUploadRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("测试不上传文件"));
        }
    }
}
