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
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewSchemaRendererBoundaryTest {
    @Test
    void 列表与表格恢复权威选择并让行操作跟随当前选择() {
        FxTestSupport.run(() -> {
            RecordingInteractions interactions = new RecordingInteractions();
            VBox page = (VBox) new ViewSchemaRenderer().render(schema(), data(), interactions);
            List<ListView> lists = nodes(page, ListView.class);
            List<TableView> tables = nodes(page, TableView.class);
            @SuppressWarnings("unchecked")
            ListView<Map<String, Object>> selectedList = (ListView<Map<String, Object>>) lists.getFirst();
            @SuppressWarnings("unchecked")
            TableView<Map<String, Object>> selectedTable = (TableView<Map<String, Object>>) tables.getFirst();

            assertEquals(
                    "two", selectedList.getSelectionModel().getSelectedItem().get("id"));
            assertEquals(
                    "two", selectedTable.getSelectionModel().getSelectedItem().get("id"));
            button(page, "运行").fire();
            assertEquals("two", interactions.commands.getFirst().arguments().get("recordId"));
            assertEquals(2, interactions.commands.getFirst().expectedRevision());

            selectedList.getSelectionModel().clearSelection();
            assertEquals(Optional.empty(), interactions.selection);
            selectedList.getSelectionModel().select(2);
            assertEquals(Optional.empty(), interactions.selection);
            selectedList.getSelectionModel().selectFirst();
            assertEquals(Optional.of("one"), interactions.selection);

            assertTrue(lists.get(1).getSelectionModel().isEmpty());
            assertTrue(tables.get(1).getSelectionModel().isEmpty());
        });
    }

    @Test
    void 空时间线和非数值进度使用安全空状态() {
        FxTestSupport.run(() -> {
            VBox page = (VBox) new ViewSchemaRenderer().render(schema(), data(), new RecordingInteractions());

            assertTrue(nodes(page, Label.class).stream().anyMatch(label -> "暂无时间线".equals(label.getText())));
            assertEquals(0.0, nodes(page, ProgressBar.class).getFirst().getProgress());
            assertFalse(button(page, "运行").isDisabled());
        });
    }

    private static ViewSchema schema() {
        ViewAction run = new ViewAction(
                "运行",
                "record/run",
                Map.of(),
                Map.of("recordId", "id"),
                new ExpectedRevisionBinding.RowField("revision"),
                false);
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "selection-boundaries",
                "选择边界",
                List.of(
                        source("selectable-list"),
                        source("selectable-table"),
                        source("plain-list"),
                        source("plain-table"),
                        source("events"),
                        source("progress")),
                List.of(
                        new ViewSchema.ListView(
                                "selected-list",
                                "可选列表",
                                "selectable-list",
                                "id",
                                "title",
                                "detail",
                                ViewSelectionMode.SINGLE,
                                List.of(run)),
                        new ViewSchema.Table(
                                "selected-table",
                                "可选表格",
                                "selectable-table",
                                "id",
                                List.of(new ViewSchema.Column("title", "标题", Optional.empty())),
                                ViewSelectionMode.SINGLE,
                                List.of()),
                        new ViewSchema.ListView(
                                "plain-list",
                                "只读列表",
                                "plain-list",
                                "id",
                                "title",
                                "detail",
                                ViewSelectionMode.NONE,
                                List.of()),
                        new ViewSchema.Table(
                                "plain-table",
                                "只读表格",
                                "plain-table",
                                "id",
                                List.of(new ViewSchema.Column("title", "标题", Optional.empty())),
                                ViewSelectionMode.NONE,
                                List.of()),
                        new ViewSchema.Timeline("timeline", "时间线", "events", "time", "content"),
                        new ViewSchema.Progress("progress", "进度", "progress", "value", "label")));
    }

    private static ViewData data() {
        List<Map<String, Object>> rows = List.of(
                Map.of("id", "one", "title", "第一项", "detail", "详情", "revision", 1),
                Map.of("id", "two", "title", "第二项", "detail", "详情", "revision", 2),
                Map.of("id", " ", "title", "无键项", "detail", "详情", "revision", 3));
        return new ViewData(Map.of(
                "selectable-list", page(rows, Map.of(), Optional.of("two")),
                "selectable-table", page(rows, Map.of(), Optional.of("two")),
                "plain-list", page(rows, Map.of(), Optional.of("two")),
                "plain-table", page(rows, Map.of(), Optional.of("two")),
                "events", page(List.of(), Map.of(), Optional.empty()),
                "progress", page(List.of(), Map.of("value", "unknown", "label", "等待"), Optional.empty())));
    }

    private static ViewDataSource source(String id) {
        return new ViewDataSource(id, "view/" + id, Map.of(), List.of(), 20);
    }

    private static ViewData.Source page(
            List<Map<String, Object>> rows, Map<String, Object> values, Optional<String> selectedKey) {
        return new ViewData.Source(rows, values, "", "", false, 4, 0, selectedKey);
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

    private static final class RecordingInteractions implements ViewInteractionHandler {
        private final List<ViewCommandInvocation> commands = new ArrayList<>();
        private Optional<String> selection = Optional.empty();

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
        public void select(String sourceId, Optional<String> selectedKey) {
            selection = selectedKey;
        }

        @Override
        public CompletionStage<AttachmentRef> upload(ViewAttachmentUploadRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("本测试不上传文件"));
        }
    }
}
