package com.javaclaw.desktop.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewOption;
import com.javaclaw.extension.spi.ViewOptionFilter;
import com.javaclaw.extension.spi.ViewOptionSource;
import com.javaclaw.extension.spi.ViewSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ViewDynamicOptionsRendererTest {
    @Test
    void filtersPointerChoicesBySelectedGovernedTool() {
        FxTestSupport.run(() -> {
            VBox page = (VBox) new ViewSchemaRenderer().render(schema(), data(), new NoOpViewInteractionHandler());
            ComboBox<ViewOption> tool = combo(page, "证据工具");
            ComboBox<ViewOption> pointer = combo(page, "工具输出标量字段");

            assertEquals("/exitCode", pointer.getValue().value());
            assertEquals(1, pointer.getItems().size());
            tool.setValue(tool.getItems().get(1));
            assertEquals("/success", pointer.getItems().getFirst().value());
            assertNull(pointer.getValue());
        });
    }

    private static ViewSchema schema() {
        ViewField tool =
                choice("toolName", "证据工具", new ViewOptionSource("tools", "toolName", "toolLabel", Optional.empty()));
        ViewField pointer = choice(
                "fieldPointer",
                "工具输出标量字段",
                new ViewOptionSource(
                        "fields",
                        "fieldPointer",
                        "fieldLabel",
                        Optional.of(new ViewOptionFilter("toolName", "toolName"))));
        ViewAction save = new ViewAction(
                "保存",
                "definition/save",
                Map.of(),
                Map.of(),
                new ExpectedRevisionBinding.SourceRevision("editor"),
                false);
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "tool-fields",
                "工具字段",
                List.of(source("editor"), source("tools"), source("fields")),
                List.of(new ViewSchema.Form("form", "验证", List.of(tool, pointer), save)));
    }

    private static ViewField choice(String name, String label, ViewOptionSource source) {
        return new ViewField(
                name,
                label,
                ViewFieldType.CHOICE,
                new ViewBinding("editor", name),
                Optional.empty(),
                ViewFieldValidation.required(true),
                List.of(),
                Optional.of(source),
                Optional.empty());
    }

    private static ViewDataSource source(String id) {
        return new ViewDataSource(id, "view." + id, Map.of(), List.of(), 100);
    }

    private static ViewData data() {
        return new ViewData(Map.of(
                "editor",
                page(List.of(), Map.of("toolName", "read_file", "fieldPointer", "/exitCode"), 3),
                "tools",
                page(
                        List.of(
                                Map.of("toolName", "read_file", "toolLabel", "读取文件"),
                                Map.of("toolName", "write_file", "toolLabel", "写入文件")),
                        Map.of(),
                        0),
                "fields",
                page(
                        List.of(
                                pointer("read_file", "/exitCode", "integer"),
                                pointer("write_file", "/success", "boolean")),
                        Map.of(),
                        0)));
    }

    private static Map<String, Object> pointer(String tool, String pointer, String type) {
        return Map.of(
                "toolName", tool,
                "fieldPointer", pointer,
                "fieldLabel", pointer + " · " + type);
    }

    private static ViewData.Source page(List<Map<String, Object>> rows, Map<String, Object> value, long revision) {
        return new ViewData.Source(rows, value, "", "", false, revision, 0, Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<ViewOption> combo(Node root, String accessibleText) {
        return (ComboBox<ViewOption>) descendants(root).stream()
                .filter(ComboBox.class::isInstance)
                .filter(node -> accessibleText.equals(node.getAccessibleText()))
                .findFirst()
                .orElseThrow();
    }

    private static List<Node> descendants(Node root) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(root);
        if (root instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> nodes.addAll(descendants(child)));
        }
        return nodes;
    }

    private static final class NoOpViewInteractionHandler implements ViewInteractionHandler {
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
            return CompletableFuture.failedFuture(new UnsupportedOperationException("fixture does not upload"));
        }
    }
}
