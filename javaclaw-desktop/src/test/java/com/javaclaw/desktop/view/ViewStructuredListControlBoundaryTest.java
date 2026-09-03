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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewOption;
import com.javaclaw.extension.spi.ViewOptionFilter;
import com.javaclaw.extension.spi.ViewOptionSource;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewStructuredItemField;
import com.javaclaw.extension.spi.ViewStructuredItemType;
import com.javaclaw.extension.spi.ViewStructuredItemValidation;
import com.javaclaw.extension.spi.ViewStructuredListField;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewStructuredListControlBoundaryTest {
    @Test
    void 可选列表使用无星号标签且新增键避开已有键() {
        FxTestSupport.run(() -> {
            ViewStructuredListField field = structured(fields(), List.of(row("item-1")));
            VBox page = render(field, data(field, List.of(row("item-1")), List.of()));
            ViewStructuredListControl control =
                    nodes(page, ViewStructuredListControl.class).getFirst();

            assertTrue(nodes(page, Label.class).stream().anyMatch(label -> "可选步骤".equals(label.getText())));
            button(page, "新增行").fire();
            assertEquals(List.of("item-1", "item-2"), keys(control));

            ComboBox<ViewOption> choice = combo(page, "类型");
            assertEquals("", choice.getConverter().toString(null));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> choice.getConverter().fromString("free"));

            assertFalse(nodes(page, TextArea.class).isEmpty());
            TextField number = nodes(page, TextField.class).stream()
                    .filter(input -> "数量".equals(input.getAccessibleText()))
                    .findFirst()
                    .orElseThrow();
            number.setText("invalid");
            assertEquals("invalid", control.value().getFirst().get("amount"));
        });
    }

    @Test
    void 行内动态选项随同行依赖变化并清除失效选择() {
        FxTestSupport.run(() -> {
            ViewStructuredItemField category = item(
                    "category", "类别", ViewStructuredItemType.TEXT, Optional.of("read"), List.of(), Optional.empty());
            ViewStructuredItemField tool = item(
                    "tool",
                    "工具",
                    ViewStructuredItemType.CHOICE,
                    Optional.of("read-file"),
                    List.of(),
                    Optional.of(new ViewOptionSource(
                            "options", "value", "label", Optional.of(new ViewOptionFilter("category", "category")))));
            Map<String, Object> row = Map.of("itemId", "first", "category", "read", "tool", "read-file");
            ViewStructuredListField field = structured(List.of(category, tool), List.of(row));
            List<Map<String, Object>> options = List.of(
                    Map.of("value", "read-file", "label", "读取文件", "category", "read"),
                    Map.of("value", "write-file", "label", "写入文件", "category", "write"));
            VBox page = render(field, data(field, List.of(row), options));
            ViewStructuredListControl control =
                    nodes(page, ViewStructuredListControl.class).getFirst();
            TextField dependency = nodes(page, TextField.class).stream()
                    .filter(input -> "类别".equals(input.getAccessibleText()))
                    .findFirst()
                    .orElseThrow();
            ComboBox<ViewOption> choice = combo(page, "工具");

            assertEquals("read-file", choice.getValue().value());
            dependency.setText("write");
            assertEquals("write-file", choice.getItems().getFirst().value());
            assertEquals(null, choice.getValue());
            assertEquals("", control.value().getFirst().get("tool"));
        });
    }

    private static List<ViewStructuredItemField> fields() {
        return List.of(
                item(
                        "kind",
                        "类型",
                        ViewStructuredItemType.CHOICE,
                        Optional.of("turn"),
                        List.of(new ViewOption("turn", "Turn")),
                        Optional.empty()),
                item(
                        "description",
                        "说明",
                        ViewStructuredItemType.MULTILINE,
                        Optional.of("内容"),
                        List.of(),
                        Optional.empty()),
                item("amount", "数量", ViewStructuredItemType.NUMBER, Optional.of("1"), List.of(), Optional.empty()));
    }

    private static ViewStructuredItemField item(
            String name,
            String label,
            ViewStructuredItemType type,
            Optional<String> initial,
            List<ViewOption> options,
            Optional<ViewOptionSource> optionSource) {
        return new ViewStructuredItemField(
                name,
                label,
                type,
                initial,
                List.of(),
                ViewStructuredItemValidation.required(false),
                options,
                optionSource);
    }

    private static ViewStructuredListField structured(
            List<ViewStructuredItemField> fields, List<Map<String, Object>> rows) {
        return new ViewStructuredListField(
                "steps", "可选步骤", new ViewBinding("editor", "steps"), 0, 3, "itemId", fields, rows, Optional.empty());
    }

    private static Map<String, Object> row(String key) {
        return Map.of("itemId", key, "kind", "turn", "description", "内容", "amount", 1);
    }

    private static ViewData data(
            ViewStructuredListField field, List<Map<String, Object>> rows, List<Map<String, Object>> options) {
        return new ViewData(Map.of(
                "editor", sourceData(Map.of(field.name(), rows), List.of(), 3),
                "options", sourceData(Map.of(), options, 0)));
    }

    private static ViewData.Source sourceData(
            Map<String, Object> values, List<Map<String, Object>> rows, long revision) {
        return new ViewData.Source(rows, values, "", "", false, revision, 0, Optional.empty());
    }

    private static VBox render(ViewStructuredListField field, ViewData data) {
        ViewAction save = new ViewAction(
                "保存", "steps/save", Map.of(), Map.of(), new ExpectedRevisionBinding.SourceRevision("editor"), false);
        ViewSchema schema = new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "structured-boundaries",
                "结构化列表",
                List.of(
                        new ViewDataSource("editor", "view/editor", Map.of(), List.of(), 1),
                        new ViewDataSource("options", "view/options", Map.of(), List.of(), 20)),
                List.of(new ViewSchema.Form("form", "编辑", List.of(field), save)));
        return (VBox) new ViewSchemaRenderer().render(schema, data, new NoOpInteractions());
    }

    private static List<String> keys(ViewStructuredListControl control) {
        return control.value().stream().map(row -> (String) row.get("itemId")).toList();
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<ViewOption> combo(Node root, String accessibleText) {
        return (ComboBox<ViewOption>) nodes(root, ComboBox.class).stream()
                .filter(input -> accessibleText.equals(input.getAccessibleText()))
                .findFirst()
                .orElseThrow();
    }

    private static Button button(Node root, String text) {
        return nodes(root, Button.class).stream()
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow();
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

    private static final class NoOpInteractions implements ViewInteractionHandler {
        @Override
        public void dirty(String formId, boolean dirty) {}

        @Override
        public void execute(ViewCommandInvocation invocation) {}

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
