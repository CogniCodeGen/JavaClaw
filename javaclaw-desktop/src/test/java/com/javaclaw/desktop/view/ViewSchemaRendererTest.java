package com.javaclaw.desktop.view;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewAttachmentPolicy;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCondition;
import com.javaclaw.extension.spi.ViewConditionOperator;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewOption;
import com.javaclaw.extension.spi.ViewOptionSource;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewSchemaRendererTest {
    @Test
    void rendersTypedInitialValuesDynamicConditionsAndExpectedRevision() {
        FxTestSupport.run(() -> {
            RecordingInteractions interactions = new RecordingInteractions();
            VBox page = (VBox) new ViewSchemaRenderer().render(schema(), data(), interactions);
            List<Node> descendants = descendants(page);

            TextField number = nodeWithAccessibleText(descendants, TextField.class, "数量");
            CheckBox enabled = nodeWithAccessibleText(descendants, CheckBox.class, "启用");
            ComboBox<?> choice = nodeWithAccessibleText(descendants, ComboBox.class, "动态选项");
            TextArea notes = nodeWithAccessibleText(descendants, TextArea.class, "备注");
            Button submit = button(descendants, "保存");

            assertEquals("3", number.getText());
            assertFalse(enabled.isSelected());
            assertEquals("Two", ((ViewOption) choice.getValue()).label());
            assertTrue(notes.isVisible());
            assertTrue(nodes(descendants, PasswordField.class).isEmpty());
            assertTrue(submit.isDisabled());
            assertTrue(interactions.dirtyForms.isEmpty());

            number.setText("42");
            assertFalse(submit.isDisabled());
            assertTrue(interactions.dirtyForms.contains("form"));
            submit.fire();
            assertTrue(interactions.commands.isEmpty());
            assertTrue(nodes(descendants, Label.class).stream()
                    .anyMatch(label -> label.getText().contains("大于允许的最大值")));

            number.setText("5");
            notes.setText("只包含非敏感备注");
            submit.fire();
            ViewCommandInvocation invocation = interactions.commands.getFirst();
            assertEquals("put", invocation.operation());
            assertEquals(new BigDecimal("5"), invocation.arguments().get("number"));
            assertEquals("two", invocation.arguments().get("choice"));
            assertFalse(invocation.arguments().containsKey("secret"));
            assertFalse(invocation.arguments().containsValue("password-plaintext"));
            assertEquals(7, invocation.expectedRevision());
        });
    }

    @Test
    void rendersPagingSelectionRowActionsAndSafeGraph() {
        FxTestSupport.run(() -> {
            RecordingInteractions interactions = new RecordingInteractions();
            VBox page = (VBox) new ViewSchemaRenderer().render(schema(), data(), interactions);
            List<Node> descendants = descendants(page);

            @SuppressWarnings("unchecked")
            TableView<Map<String, Object>> table = (TableView<Map<String, Object>>)
                    nodes(descendants, TableView.class).getFirst();
            table.getSelectionModel().selectFirst();
            button(descendants, "执行").fire();
            button(descendants, "下一页").fire();
            button(descendants, "刷新").fire();

            assertEquals(Optional.of("doc-1"), interactions.selection);
            assertEquals("doc-1", interactions.commands.getFirst().arguments().get("id"));
            assertEquals(4, interactions.commands.getFirst().expectedRevision());
            assertEquals(ViewPageDirection.NEXT, interactions.pageDirection);
            assertEquals("documents", interactions.pageSource);
            assertEquals(1, interactions.reloads);
            assertTrue(nodes(descendants, Label.class).stream()
                    .anyMatch(label -> "doc-1 → doc-2".equals(label.getText())));
            assertTrue(nodes(descendants, Label.class).stream()
                    .anyMatch(label -> label.getText().contains("忽略 1 条")));
            assertEquals(1.0, nodes(descendants, ProgressBar.class).getFirst().getProgress());
            assertEquals(
                    2, nodes(descendants, ListView.class).getFirst().getItems().size());
        });
    }

    @Test
    void rowActionUsesSelectedRowRevisionInsteadOfPageMaximum() {
        FxTestSupport.run(() -> {
            ViewAction delete = new ViewAction(
                    "删除",
                    "source/delete",
                    Map.of(),
                    Map.of("id", "id"),
                    new ExpectedRevisionBinding.RowField("revision"),
                    true);
            ViewSchema schema = new ViewSchema(
                    ViewSchema.CURRENT_VERSION,
                    "mixed-revisions",
                    "混合版本",
                    List.of(source("sources", "view.sources")),
                    List.of(new ViewSchema.Table(
                            "sources",
                            "来源",
                            "sources",
                            "id",
                            List.of(new ViewSchema.Column("revision", "版本", Optional.empty())),
                            ViewSelectionMode.SINGLE,
                            List.of(delete))));
            ViewData.Source rows = new ViewData.Source(
                    List.of(Map.of("id", "old", "revision", 2), Map.of("id", "new", "revision", 9)),
                    Map.of(),
                    "",
                    "",
                    false,
                    9,
                    0,
                    Optional.empty());
            RecordingInteractions interactions = new RecordingInteractions();
            VBox page =
                    (VBox) new ViewSchemaRenderer().render(schema, new ViewData(Map.of("sources", rows)), interactions);
            @SuppressWarnings("unchecked")
            TableView<Map<String, Object>> table = (TableView<Map<String, Object>>)
                    nodes(descendants(page), TableView.class).getFirst();

            table.getSelectionModel().selectFirst();
            button(descendants(page), "删除").fire();

            assertEquals("old", interactions.commands.getFirst().arguments().get("id"));
            assertEquals(2, interactions.commands.getFirst().expectedRevision());
        });
    }

    @Test
    void rendersReadOnlyContentWithoutInlineStyles() {
        FxTestSupport.run(() -> {
            VBox page = (VBox) new ViewSchemaRenderer().render(schema(), data(), new RecordingInteractions());
            List<Node> descendants = descendants(page);

            assertEquals("平台页面", ((Label) page.getChildren().getFirst()).getText());
            assertTrue(descendants.stream().allMatch(node -> node.getStyle().isEmpty()));
            TextArea code = nodes(descendants, TextArea.class).stream()
                    .filter(area -> !area.isEditable())
                    .findFirst()
                    .orElseThrow();
            assertEquals("record Demo() {}", code.getText());
            assertTrue(nodes(descendants, Label.class).stream()
                    .anyMatch(label -> "text/plain · sha256:abc".equals(label.getText())));
        });
    }

    @Test
    void attachmentFieldUploadsBySdkReferenceAndDiscardsCancelledEpoch(@TempDir Path temporary) throws Exception {
        Path firstFile = Files.writeString(temporary.resolve("first.txt"), "first");
        Path secondFile = Files.writeString(temporary.resolve("second.txt"), "second");
        AttachmentRef first = new AttachmentRef("a".repeat(64), "text/plain", "first.txt", 5);
        AttachmentRef second = new AttachmentRef("b".repeat(64), "text/plain", "second.txt", 6);
        RecordingInteractions interactions = new RecordingInteractions();
        CompletableFuture<AttachmentRef> firstResult = interactions.expectUpload();
        CompletableFuture<AttachmentRef> secondResult = interactions.expectUpload();

        FxTestSupport.run(() -> {
            ViewSchemaRenderer renderer = new ViewSchemaRenderer();
            VBox page = (VBox) renderer.render(attachmentSchema(), attachmentData(), interactions);
            ViewAttachmentFieldControl field =
                    nodes(descendants(page), ViewAttachmentFieldControl.class).getFirst();
            Button submit = button(descendants(page), "导入");

            field.upload(firstFile);
            assertTrue(field.pending());
            field.upload(secondFile);
            assertTrue(interactions.uploads.getFirst().cancellation().isCancelled());
            firstResult.complete(first);
            assertTrue(field.value().isEmpty());
            secondResult.complete(second);
            assertEquals(Optional.of(second), field.value());
            assertFalse(submit.isDisabled());
            submit.fire();
            assertEquals(second, interactions.commands.getFirst().arguments().get("attachment"));

            interactions.expectUpload();
            field.upload(firstFile);
            renderer.cancelUploads(page);
            assertTrue(interactions.uploads.getLast().cancellation().isCancelled());
        });
    }

    private static ViewSchema attachmentSchema() {
        ViewField attachment = new ViewField(
                "attachment",
                "文件",
                ViewFieldType.ATTACHMENT,
                new ViewBinding("editor", "attachment"),
                Optional.empty(),
                ViewFieldValidation.attachment(true, new ViewAttachmentPolicy(java.util.Set.of("text/*"), 1024)),
                List.of(),
                Optional.empty(),
                Optional.empty());
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "attachment",
                "Attachment",
                List.of(source("editor", "view.read")),
                List.of(new ViewSchema.Form(
                        "upload",
                        "导入",
                        List.of(attachment),
                        new ViewAction(
                                "导入",
                                "source.import",
                                Map.of(),
                                Map.of(),
                                new ExpectedRevisionBinding.None(),
                                false))));
    }

    private static ViewData attachmentData() {
        return new ViewData(Map.of("editor", page(List.of(), 0)));
    }

    private static ViewSchema schema() {
        return new ViewSchema(ViewSchema.CURRENT_VERSION, "all", "平台页面", dataSources(), viewNodes());
    }

    private static List<ViewDataSource> dataSources() {
        return List.of(
                source("editor", "view.read"),
                source("options", "view.options"),
                source("documents", "view.list"),
                source("status", "view.status"),
                source("events", "view.events"),
                source("content", "view.content"),
                source("edges", "view.edges"));
    }

    private static List<ViewSchema.Node> viewNodes() {
        ViewAction save = new ViewAction(
                "保存", "put", Map.of(), Map.of(), new ExpectedRevisionBinding.SourceRevision("editor"), false);
        ViewAction run = new ViewAction(
                "执行",
                "run",
                Map.of(),
                Map.of("id", "id"),
                new ExpectedRevisionBinding.SourceRevision("documents"),
                false);
        return List.of(
                new ViewSchema.Form("form", "编辑", formFields(), save),
                new ViewSchema.ListView(
                        "list", "列表", "documents", "id", "title", "detail", ViewSelectionMode.SINGLE, List.of()),
                documentTable(run),
                new ViewSchema.Card("card", "操作", "只执行声明式命令", List.of()),
                new ViewSchema.Progress("progress", "进度", "status", "value", "label"),
                new ViewSchema.Timeline("timeline", "时间线", "events", "time", "content"),
                new ViewSchema.Markdown("markdown", "说明", new ViewBinding("content", "markdown")),
                new ViewSchema.Code(
                        "code",
                        "代码",
                        new ViewBinding("content", "code"),
                        Optional.of(new ViewBinding("content", "language"))),
                new ViewSchema.Artifact(
                        "artifact",
                        "产物",
                        new ViewBinding("content", "artifact"),
                        new ViewBinding("content", "mediaType")),
                new ViewSchema.Graph("graph", "流程图", "documents", "edges", "id", "title", "kind", "from", "to"));
    }

    private static ViewSchema.Table documentTable(ViewAction run) {
        return new ViewSchema.Table(
                "table",
                "表格",
                "documents",
                "id",
                List.of(
                        new ViewSchema.Column("title", "标题", Optional.of(180)),
                        new ViewSchema.Column("detail", "详情", Optional.empty())),
                ViewSelectionMode.SINGLE,
                List.of(run));
    }

    private static List<ViewField> formFields() {
        ViewBinding mode = new ViewBinding("editor", "mode");
        return List.of(
                new ViewField(
                        "number",
                        "数量",
                        ViewFieldType.NUMBER,
                        new ViewBinding("editor", "number"),
                        Optional.empty(),
                        new ViewFieldValidation(
                                true,
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of(BigDecimal.ONE),
                                Optional.of(BigDecimal.TEN),
                                Optional.empty()),
                        List.of(),
                        Optional.empty(),
                        Optional.empty()),
                field("enabled", "启用", ViewFieldType.BOOLEAN, new ViewBinding("editor", "enabled")),
                new ViewField(
                        "choice",
                        "动态选项",
                        ViewFieldType.CHOICE,
                        new ViewBinding("editor", "choice"),
                        Optional.empty(),
                        ViewFieldValidation.required(true),
                        List.of(),
                        Optional.of(new ViewOptionSource("options", "value", "label", Optional.empty())),
                        Optional.empty()),
                new ViewField(
                        "mode",
                        "模式",
                        ViewFieldType.CHOICE,
                        mode,
                        Optional.empty(),
                        ViewFieldValidation.required(true),
                        List.of(new ViewOption("basic", "基础"), new ViewOption("advanced", "高级")),
                        Optional.empty(),
                        Optional.empty()),
                new ViewField(
                        "notes",
                        "备注",
                        ViewFieldType.MULTILINE,
                        new ViewBinding("editor", "notes"),
                        Optional.empty(),
                        ViewFieldValidation.required(false),
                        List.of(),
                        Optional.empty(),
                        Optional.of(new ViewCondition(mode, ViewConditionOperator.EQUALS, "advanced"))));
    }

    private static ViewField field(String name, String label, ViewFieldType type, ViewBinding binding) {
        return new ViewField(
                name,
                label,
                type,
                binding,
                Optional.empty(),
                ViewFieldValidation.required(false),
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    private static ViewDataSource source(String id, String query) {
        return new ViewDataSource(id, query, Map.of(), List.of(), 20);
    }

    private static ViewData data() {
        Map<String, ViewData.Source> sources = new LinkedHashMap<>();
        sources.put(
                "editor",
                new ViewData.Source(
                        List.of(),
                        Map.of(
                                "number", 3,
                                "enabled", false,
                                "choice", "two",
                                "mode", "advanced",
                                "secret", "password-plaintext"),
                        "",
                        "",
                        false,
                        7,
                        0,
                        Optional.empty()));
        sources.put(
                "options",
                page(List.of(Map.of("value", "one", "label", "One"), Map.of("value", "two", "label", "Two")), 0));
        sources.put(
                "documents",
                new ViewData.Source(
                        List.of(
                                Map.of("id", "doc-1", "title", "计划", "detail", "待执行", "kind", "TURN"),
                                Map.of("id", "doc-2", "title", "验收", "detail", "已完成", "kind", "OUTPUT")),
                        Map.of(),
                        "",
                        "doc-2",
                        true,
                        4,
                        0,
                        Optional.empty()));
        sources.put("status", page(List.of(Map.of("value", 2.0, "label", "完成")), 0));
        sources.put("events", page(List.of(Map.of("time", "09:00", "content", "开始")), 0));
        sources.put(
                "content",
                new ViewData.Source(
                        List.of(),
                        Map.of(
                                "markdown", "# 当前状态",
                                "code", "record Demo() {}",
                                "language", "java",
                                "artifact", "sha256:abc",
                                "mediaType", "text/plain"),
                        "",
                        "",
                        false,
                        0,
                        0,
                        Optional.empty()));
        sources.put(
                "edges",
                page(List.of(Map.of("from", "doc-1", "to", "doc-2"), Map.of("from", "missing", "to", "doc-2")), 0));
        return new ViewData(sources);
    }

    private static ViewData.Source page(List<Map<String, Object>> rows, long revision) {
        return new ViewData.Source(rows, Map.of(), "", "", false, revision, 0, Optional.empty());
    }

    private static List<Node> descendants(Node root) {
        ArrayList<Node> nodes = new ArrayList<>();
        nodes.add(root);
        if (root instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> nodes.addAll(descendants(child)));
        }
        return nodes;
    }

    private static <T extends Node> List<T> nodes(List<Node> nodes, Class<T> type) {
        return nodes.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static <T extends Node> T nodeWithAccessibleText(List<Node> nodes, Class<T> type, String accessibleText) {
        return nodes(nodes, type).stream()
                .filter(node -> accessibleText.equals(node.getAccessibleText()))
                .findFirst()
                .orElseThrow();
    }

    private static Button button(List<Node> nodes, String text) {
        return nodes(nodes, Button.class).stream()
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static final class RecordingInteractions implements ViewInteractionHandler {
        private final List<ViewCommandInvocation> commands = new ArrayList<>();
        private final java.util.Set<String> dirtyForms = new java.util.HashSet<>();
        private Optional<String> selection = Optional.empty();
        private String pageSource;
        private ViewPageDirection pageDirection;
        private int reloads;
        private final ArrayDeque<CompletableFuture<AttachmentRef>> uploadResults = new ArrayDeque<>();
        private final List<ViewAttachmentUploadRequest> uploads = new ArrayList<>();

        private CompletableFuture<AttachmentRef> expectUpload() {
            CompletableFuture<AttachmentRef> result = new CompletableFuture<>();
            uploadResults.add(result);
            return result;
        }

        @Override
        public void dirty(String formId, boolean dirty) {
            if (dirty) {
                dirtyForms.add(formId);
            } else {
                dirtyForms.remove(formId);
            }
        }

        @Override
        public void execute(ViewCommandInvocation invocation) {
            commands.add(invocation);
        }

        @Override
        public void reload() {
            reloads++;
        }

        @Override
        public void page(String sourceId, ViewPageDirection direction) {
            pageSource = sourceId;
            pageDirection = direction;
        }

        @Override
        public void select(String sourceId, Optional<String> selectedKey) {
            selection = selectedKey;
        }

        @Override
        public CompletionStage<AttachmentRef> upload(ViewAttachmentUploadRequest request) {
            uploads.add(request);
            if (uploadResults.isEmpty()) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException("fixture does not upload"));
            }
            return uploadResults.removeFirst();
        }
    }
}
