package com.javaclaw.desktop.view;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCondition;
import com.javaclaw.extension.spi.ViewConditionOperator;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewOption;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewStructuredItemField;
import com.javaclaw.extension.spi.ViewStructuredItemType;
import com.javaclaw.extension.spi.ViewStructuredItemValidation;
import com.javaclaw.extension.spi.ViewStructuredListField;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewStructuredListRendererTest {
    @Test
    void editsAddsRemovesReordersAndSubmitsCanonicalRows() {
        FxTestSupport.run(() -> {
            RecordingInteractions interactions = new RecordingInteractions();
            VBox page = (VBox) new ViewSchemaRenderer().render(schema(), data(), interactions);
            ViewStructuredListControl control =
                    nodes(page, ViewStructuredListControl.class).getFirst();
            Button submit = button(page, "保存");

            assertEquals(List.of("first", "second"), itemKeys(control));
            assertTrue(submit.isDisabled());
            assertTrue(
                    descendants(page).stream().allMatch(node -> node.getStyle().isEmpty()));

            enabledButton(page, "下移").fire();
            assertEquals(List.of("second", "first"), itemKeys(control));
            assertFalse(submit.isDisabled());
            assertTrue(interactions.dirty);

            button(page, "新增行").fire();
            assertEquals(3, control.value().size());
            assertTrue(button(page, "新增行").isDisabled());
            List<TextField> names = nodes(page, TextField.class).stream()
                    .filter(field -> "标题".equals(field.getAccessibleText()))
                    .toList();
            names.getLast().setText("第三步");
            submit.fire();

            List<?> payload = (List<?>) interactions.command.arguments().get("steps");
            assertEquals(
                    List.of("second", "first"),
                    payload.stream()
                            .limit(2)
                            .map(Map.class::cast)
                            .map(row -> row.get("stepId"))
                            .toList());
            Map<?, ?> first = (Map<?, ?>) payload.getFirst();
            Map<?, ?> last = (Map<?, ?>) payload.getLast();
            assertEquals(new BigDecimal("2"), first.get("weight"));
            assertEquals(List.of("beta", "safe"), first.get("tags"));
            assertTrue(last.get("stepId") instanceof String);

            List<Button> removals = buttons(page, "移除").stream()
                    .filter(button -> !button.isDisabled())
                    .toList();
            removals.getLast().fire();
            assertEquals(2, control.value().size());
        });
    }

    @Test
    void blocksInvalidRowsAndUsesSafeFieldLevelVisibility() {
        FxTestSupport.run(() -> {
            RecordingInteractions interactions = new RecordingInteractions();
            VBox page = (VBox) new ViewSchemaRenderer().render(conditionalSchema(), hiddenData(), interactions);
            ViewStructuredListControl control =
                    nodes(page, ViewStructuredListControl.class).getFirst();
            CheckBox visible = nodes(page, CheckBox.class).stream()
                    .filter(box -> "显示步骤".equals(box.getAccessibleText()))
                    .findFirst()
                    .orElseThrow();

            assertFalse(control.isVisible());
            visible.setSelected(true);
            assertTrue(control.isVisible());
            button(page, "新增行").fire();
            button(page, "保存").fire();

            assertTrue(interactions.command == null);
            assertTrue(nodes(page, Label.class).stream()
                    .anyMatch(label -> label.getText().contains("标题不能为空")));
        });
    }

    private static ViewSchema schema() {
        return schema(List.of(structured(Optional.empty())));
    }

    private static ViewSchema conditionalSchema() {
        ViewBinding show = new ViewBinding("editor", "showSteps");
        ViewField toggle = new ViewField(
                "showSteps",
                "显示步骤",
                ViewFieldType.BOOLEAN,
                show,
                Optional.of("false"),
                ViewFieldValidation.required(false),
                List.of(),
                Optional.empty(),
                Optional.empty());
        ViewCondition condition = new ViewCondition(show, ViewConditionOperator.EQUALS, "true");
        return schema(List.of(toggle, structured(Optional.of(condition))));
    }

    private static ViewSchema schema(List<? extends com.javaclaw.extension.spi.ViewFormField> fields) {
        ViewDataSource editor = new ViewDataSource("editor", "view.read", Map.of(), List.of(), 1);
        ViewAction save = new ViewAction(
                "保存", "steps.put", Map.of(), Map.of(), new ExpectedRevisionBinding.SourceRevision("editor"), false);
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "steps.editor",
                "步骤编辑",
                List.of(editor),
                List.of(new ViewSchema.Form("editor", "编辑", fields, save)));
    }

    private static ViewStructuredListField structured(Optional<ViewCondition> condition) {
        return new ViewStructuredListField(
                "steps",
                "步骤",
                new ViewBinding("editor", "steps"),
                1,
                3,
                "stepId",
                List.of(title(), weight(), enabled(), kind(), tags()),
                List.of(row("initial", "初始", 1, List.of())),
                condition);
    }

    private static ViewStructuredItemField title() {
        return new ViewStructuredItemField(
                "title",
                "标题",
                ViewStructuredItemType.TEXT,
                Optional.empty(),
                List.of(),
                ViewStructuredItemValidation.required(true),
                List.of());
    }

    private static ViewStructuredItemField weight() {
        return new ViewStructuredItemField(
                "weight",
                "权重",
                ViewStructuredItemType.NUMBER,
                Optional.empty(),
                List.of(),
                new ViewStructuredItemValidation(
                        false,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(BigDecimal.ZERO),
                        Optional.of(BigDecimal.TEN),
                        Optional.empty(),
                        Optional.empty()),
                List.of());
    }

    private static ViewStructuredItemField enabled() {
        return new ViewStructuredItemField(
                "enabled",
                "启用",
                ViewStructuredItemType.BOOLEAN,
                Optional.of("false"),
                List.of(),
                ViewStructuredItemValidation.required(false),
                List.of());
    }

    private static ViewStructuredItemField kind() {
        return new ViewStructuredItemField(
                "kind",
                "类型",
                ViewStructuredItemType.CHOICE,
                Optional.of("turn"),
                List.of(),
                ViewStructuredItemValidation.required(true),
                List.of(new ViewOption("turn", "Turn"), new ViewOption("tool", "Tool")));
    }

    private static ViewStructuredItemField tags() {
        return new ViewStructuredItemField(
                "tags",
                "标签",
                ViewStructuredItemType.TEXT_LIST,
                Optional.empty(),
                List.of(),
                new ViewStructuredItemValidation(
                        false,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(4)),
                List.of());
    }

    private static ViewData data() {
        return data(
                true,
                List.of(row("first", "第一步", 1, List.of("alpha")), row("second", "第二步", 2, List.of("beta", "safe"))));
    }

    private static ViewData hiddenData() {
        return data(false, List.of(row("first", "第一步", 1, List.of())));
    }

    private static ViewData data(boolean show, List<Map<String, Object>> rows) {
        return new ViewData(Map.of(
                "editor",
                new ViewData.Source(
                        List.of(), Map.of("showSteps", show, "steps", rows), "", "", false, 9, 0, Optional.empty())));
    }

    private static Map<String, Object> row(String id, String title, int weight, List<String> tags) {
        return Map.of(
                "stepId", id,
                "title", title,
                "weight", weight,
                "enabled", true,
                "kind", "turn",
                "tags", tags);
    }

    private static List<String> itemKeys(ViewStructuredListControl control) {
        return control.value().stream().map(row -> (String) row.get("stepId")).toList();
    }

    private static Button button(Node root, String text) {
        return buttons(root, text).getFirst();
    }

    private static Button enabledButton(Node root, String text) {
        return buttons(root, text).stream()
                .filter(button -> !button.isDisabled())
                .findFirst()
                .orElseThrow();
    }

    private static List<Button> buttons(Node root, String text) {
        return nodes(root, Button.class).stream()
                .filter(button -> text.equals(button.getText()))
                .toList();
    }

    private static <T extends Node> List<T> nodes(Node root, Class<T> type) {
        return descendants(root).stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private static List<Node> descendants(Node root) {
        List<Node> result = new ArrayList<>();
        result.add(root);
        if (root instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> result.addAll(descendants(child)));
        }
        return result;
    }

    private static final class RecordingInteractions implements ViewInteractionHandler {
        private boolean dirty;
        private ViewCommandInvocation command;

        @Override
        public void dirty(String formId, boolean dirty) {
            this.dirty = dirty;
        }

        @Override
        public void execute(ViewCommandInvocation invocation) {
            command = invocation;
        }

        @Override
        public void reload() {}

        @Override
        public void page(String sourceId, ViewPageDirection direction) {}

        @Override
        public void select(String sourceId, Optional<String> selectedKey) {}

        @Override
        public CompletionStage<AttachmentRef> upload(ViewAttachmentUploadRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("本测试不上传文件"));
        }
    }
}
