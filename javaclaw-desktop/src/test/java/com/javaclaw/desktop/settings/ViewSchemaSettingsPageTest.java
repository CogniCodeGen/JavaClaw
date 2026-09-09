package com.javaclaw.desktop.settings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.view.ViewAttachmentUploadRequest;
import com.javaclaw.desktop.view.ViewCommandInvocation;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewLoadRequest;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.JsonRpcError;
import com.javaclaw.protocol.ProtocolErrorCode;
import com.javaclaw.protocol.ViewSchemaWireCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewSchemaSettingsPageTest {
    private static final Workspace WORKSPACE_VALUE = DesktopTestFixtures.workspace();
    private static final WorkspaceId WORKSPACE = WORKSPACE_VALUE.id();

    @Test
    void 服务端事件保留Dirty草稿并在显式丢弃后读取权威状态() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("本地初值", 1));
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();
            TextField editor = field(page.content());
            editor.setText("未保存草稿");
            assertTrue(page.dirty());

            gateway.authoritative = data("服务端新值", 2);
            gateway.emit(event(2));
            assertEquals(1, gateway.loads);
            assertEquals("未保存草稿", editor.getText());
            assertTrue(labels(page.content()).contains("服务端状态已经更新"));

            gateway.emit(event(1));
            assertEquals(1, gateway.loads);
            page.discardDraft();
            assertEquals(2, gateway.loads);
            assertEquals("服务端新值", field(page.content()).getText());
            assertFalse(page.dirty());

            page.dispose();
            assertTrue(gateway.subscriptionClosed.get());
        });
    }

    @Test
    void 命令Pending期间合并事件并只刷新一次权威状态() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("初值", 1));
            CompletableFuture<ExtensionRpcContracts.CallResult> command = new CompletableFuture<>();
            gateway.command = command;
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();

            field(page.content()).setText("待提交");
            button(page.content(), "保存").fire();
            gateway.authoritative = data("已提交", 2);
            gateway.emit(event(2));
            assertEquals(1, gateway.loads);

            command.complete(new ExtensionRpcContracts.CallResult(new CanonicalPayload("{}"), 2));
            assertEquals(2, gateway.loads);
            assertEquals("已提交", field(page.content()).getText());
            gateway.emit(event(2));
            assertEquals(2, gateway.loads);
            page.dispose();
        });
    }

    @Test
    void 较早Load响应在新事件刷新完成后被Epoch丢弃() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("默认", 0));
            CompletableFuture<ViewData> oldLoad = new CompletableFuture<>();
            CompletableFuture<ViewData> freshLoad = new CompletableFuture<>();
            gateway.loadsToReturn.add(oldLoad);
            gateway.loadsToReturn.add(freshLoad);
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();
            gateway.emit(event(1));

            freshLoad.complete(data("最新权威值", 1));
            oldLoad.complete(data("迟到旧值", 0));
            assertEquals(2, gateway.loads);
            assertEquals("最新权威值", field(page.content()).getText());
            page.dispose();
        });
    }

    @Test
    void 目录与数据读取失败都能显式重试且不伪造空数据() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("恢复后", 1));
            gateway.catalogsToReturn.add(CompletableFuture.failedFuture(new IllegalStateException("目录离线")));
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();
            assertTrue(labels(page.content()).contains("扩展页面目录读取失败"));

            button(page.content(), "重试").fire();
            assertEquals(1, gateway.loads);
            gateway.loadsToReturn.add(CompletableFuture.failedFuture(new IllegalStateException("数据暂不可用")));
            page.discardDraft();
            assertTrue(labels(page.content()).contains("页面数据读取失败"));
            button(page.content(), "重试").fire();
            assertEquals("恢复后", field(page.content()).getText());
            page.dispose();
        });
    }

    @Test
    void 空目录与不受支持Schema使用明确的不可用投影() {
        FxTestSupport.run(() -> {
            FakeGateway empty = new FakeGateway(data("无关", 0));
            empty.catalog = List.of();
            ViewSchemaSettingsPage emptyPage = page(empty);
            emptyPage.activate();
            assertTrue(labels(emptyPage.content()).contains("扩展当前不可用"));
            emptyPage.deactivate();
            emptyPage.activate();
            assertEquals(1, empty.catalogs);
            emptyPage.dispose();

            FakeGateway invalid = new FakeGateway(data("无关", 0));
            invalid.catalog = List.of(new ExtensionRpcContracts.ViewDocument(
                    "plan", "broken", new CanonicalPayload("{\"version\":999}")));
            ViewSchemaSettingsPage invalidPage = page(invalid);
            invalidPage.activate();
            assertTrue(labels(invalidPage.content()).contains("页面无法渲染"));
            invalidPage.dispose();
        });
    }

    @Test
    void 普通命令失败与revision冲突都保留用户草稿() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("初值", 1));
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();
            field(page.content()).setText("草稿一");
            gateway.command = CompletableFuture.failedFuture(new IllegalStateException("写入失败"));
            button(page.content(), "保存").fire();
            assertTrue(labels(page.content()).contains("操作未完成"));
            button(page.content(), "继续编辑").fire();
            assertEquals("草稿一", field(page.content()).getText());

            gateway.command = CompletableFuture.failedFuture(new RemoteRpcException(
                    new JsonRpcError(ProtocolErrorCode.REVISION_CONFLICT, "revision 冲突", Optional.empty())));
            button(page.content(), "保存").fire();
            assertTrue(labels(page.content()).contains("内容已被其他操作更新"));
            assertTrue(page.dirty());
            page.dispose();
        });
    }

    @Test
    void 停用期间的服务端事件在再次激活时只刷新一次() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("初值", 1));
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();
            page.deactivate();
            gateway.authoritative = data("停用期间更新", 2);
            gateway.emit(event(2));
            gateway.emit(event(3));
            assertEquals(1, gateway.loads);

            page.activate();
            assertEquals(2, gateway.loads);
            assertEquals("停用期间更新", field(page.content()).getText());
            page.dispose();
        });
    }

    @Test
    void 分页与选择只把平台维护的游标状态交给SDK() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(pagedData("cursor-0", "cursor-1", true, 0));
            gateway.catalog = List.of(document("plan.pages", pagedSchema(false)));
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();

            table(page.content()).getSelectionModel().selectFirst();
            ViewLoadRequest selected = gateway.loadRequests.getLast();
            assertEquals(Optional.of("doc-1"), selected.selectedKey("documents"));

            gateway.authoritative = pagedData("cursor-1", "", false, 1);
            button(page.content(), "下一页").fire();
            ViewLoadRequest next = gateway.loadRequests.getLast();
            assertEquals("cursor-1", next.cursor("documents"));
            assertEquals(1, next.pageIndex("documents"));
            assertEquals(Optional.of("doc-1"), next.selectedKey("documents"));

            gateway.authoritative = pagedData("cursor-0", "cursor-1", true, 0);
            button(page.content(), "上一页").fire();
            ViewLoadRequest previous = gateway.loadRequests.getLast();
            assertEquals("cursor-0", previous.cursor("documents"));
            assertEquals(0, previous.pageIndex("documents"));
            page.dispose();
        });
    }

    @Test
    void 切换扩展页面时取消保留草稿确认后才丢弃() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("初值", 1));
            ExtensionRpcContracts.ViewDocument first = document("plan.a", schema());
            ExtensionRpcContracts.ViewDocument second = document("plan.b", schema());
            gateway.catalog = List.of(second, first);
            ViewSchemaSettingsPage page = page(gateway);
            new Scene((Parent) page.content(), 800, 600);
            page.activate();
            ComboBox<ExtensionRpcContracts.ViewDocument> choices = documentChoices(page.content());
            field(page.content()).setText("保留的草稿");

            completeDialog(ButtonType.CANCEL);
            choices.getSelectionModel().select(second);
            assertEquals(first, choices.getValue());
            assertEquals("保留的草稿", field(page.content()).getText());
            assertEquals(1, gateway.loads);

            completeDialog(ButtonType.OK);
            choices.getSelectionModel().select(second);
            assertEquals(second, choices.getValue());
            assertEquals(2, gateway.loads);
            assertFalse(page.dirty());
            page.dispose();
        });
    }

    @Test
    void 扩展危险命令只有确认后才提交() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("初值", 1));
            gateway.catalog = List.of(document("plan.danger", dangerousSchema()));
            ViewSchemaSettingsPage page = page(gateway);
            new Scene((Parent) page.content(), 800, 600);
            page.activate();
            field(page.content()).setText("危险草稿");

            completeDialog(ButtonType.CANCEL);
            button(page.content(), "保存").fire();
            assertEquals(0, gateway.executions);
            assertTrue(page.dirty());

            completeDialog(ButtonType.OK);
            button(page.content(), "保存").fire();
            assertEquals(1, gateway.executions);
            assertFalse(page.dirty());
            page.dispose();
        });
    }

    static ViewSchemaSettingsPage page(FakeGateway gateway) {
        ViewSchemaSettingsPage page = new ViewSchemaSettingsPage("plan", "计划", "测试页面", gateway);
        page.workspaceChanged(Optional.of(WORKSPACE_VALUE));
        return page;
    }

    private static ExtensionRpcContracts.ExtensionEvent event(long revision) {
        return new ExtensionRpcContracts.ExtensionEvent(WORKSPACE, "plan", "document", "primary", "put", revision);
    }

    private static ViewSchema schema() {
        ViewField name = new ViewField(
                "name",
                "名称",
                ViewFieldType.TEXT,
                new ViewBinding("editor", "name"),
                Optional.empty(),
                ViewFieldValidation.required(true),
                List.of(),
                Optional.empty(),
                Optional.empty());
        ViewAction save = new ViewAction(
                "保存", "put", Map.of(), Map.of(), new ExpectedRevisionBinding.SourceRevision("editor"), false);
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "plan.editor",
                "计划编辑",
                List.of(new ViewDataSource("editor", "read", Map.of(), List.of(), 1)),
                List.of(new ViewSchema.Form("editor", "编辑", List.of(name), save)));
    }

    private static ViewSchema dangerousSchema() {
        ViewSchema safe = schema();
        ViewSchema.Form form = (ViewSchema.Form) safe.nodes().getFirst();
        ViewAction dangerous = new ViewAction(
                "保存", "put", Map.of(), Map.of(), new ExpectedRevisionBinding.SourceRevision("editor"), true);
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "plan.danger",
                "危险操作",
                safe.dataSources(),
                List.of(new ViewSchema.Form(form.id(), form.title(), form.fields(), dangerous)));
    }

    private static ViewSchema pagedSchema(boolean dangerous) {
        ViewAction open = new ViewAction(
                "打开",
                "open",
                Map.of(),
                Map.of("id", "id"),
                new ExpectedRevisionBinding.SourceRevision("documents"),
                dangerous);
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "plan.pages",
                "分页计划",
                List.of(new ViewDataSource("documents", "list", Map.of(), List.of(), 1)),
                List.of(new ViewSchema.Table(
                        "documents",
                        "计划",
                        "documents",
                        "id",
                        List.of(new ViewSchema.Column("name", "名称", Optional.empty())),
                        ViewSelectionMode.SINGLE,
                        List.of(open))));
    }

    static ViewData data(String name, long revision) {
        return new ViewData(Map.of(
                "editor",
                new ViewData.Source(List.of(), Map.of("name", name), "", "", false, revision, 0, Optional.empty())));
    }

    private static ViewData pagedData(String cursor, String nextCursor, boolean hasMore, int pageIndex) {
        return new ViewData(Map.of(
                "documents",
                new ViewData.Source(
                        List.of(Map.of("id", "doc-1", "name", "计划一")),
                        Map.of(),
                        cursor,
                        nextCursor,
                        hasMore,
                        3,
                        pageIndex,
                        Optional.empty())));
    }

    private static ExtensionRpcContracts.ViewDocument document(String viewId, ViewSchema viewSchema) {
        return new ExtensionRpcContracts.ViewDocument(
                "plan", viewId, new ViewSchemaWireCodec(new CanonicalJson()).encode(viewSchema));
    }

    static TextField field(Node root) {
        return descendants(root).stream()
                .filter(TextField.class::isInstance)
                .map(TextField.class::cast)
                .filter(candidate -> "名称".equals(candidate.getAccessibleText()))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<ExtensionRpcContracts.ViewDocument> documentChoices(Node root) {
        return descendants(root).stream()
                .filter(ComboBox.class::isInstance)
                .map(ComboBox.class::cast)
                .filter(candidate -> "计划页面选择".equals(candidate.getAccessibleText()))
                .map(candidate -> (ComboBox<ExtensionRpcContracts.ViewDocument>) candidate)
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static TableView<Map<String, Object>> table(Node root) {
        return descendants(root).stream()
                .filter(TableView.class::isInstance)
                .map(candidate -> (TableView<Map<String, Object>>) candidate)
                .findFirst()
                .orElseThrow();
    }

    static Button button(Node root, String text) {
        return descendants(root).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(candidate -> text.equals(candidate.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> labels(Node root) {
        return descendants(root).stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .map(Label::getText)
                .toList();
    }

    private static List<Node> descendants(Node root) {
        ArrayList<Node> nodes = new ArrayList<>();
        nodes.add(root);
        if (root instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> nodes.addAll(descendants(child)));
        }
        return List.copyOf(nodes);
    }

    private static void completeDialog(ButtonType result) {
        Platform.runLater(() -> Window.getWindows().stream()
                .map(Window::getScene)
                .filter(java.util.Objects::nonNull)
                .map(Scene::getRoot)
                .filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast)
                .findFirst()
                .map(dialog -> dialog.lookupButton(result))
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .ifPresent(Button::fire));
    }

    static final class FakeGateway implements ExtensionSettingsGateway {
        private static final ExtensionRpcContracts.ViewDocument DOCUMENT = new ExtensionRpcContracts.ViewDocument(
                "plan", "plan.editor", new ViewSchemaWireCodec(new CanonicalJson()).encode(schema()));

        final Deque<CompletableFuture<ViewData>> loadsToReturn = new ArrayDeque<>();
        private final List<ViewLoadRequest> loadRequests = new ArrayList<>();
        private final Deque<CompletableFuture<List<ExtensionRpcContracts.ViewDocument>>> catalogsToReturn =
                new ArrayDeque<>();
        private final AtomicBoolean subscriptionClosed = new AtomicBoolean();
        private List<ExtensionRpcContracts.ViewDocument> catalog = List.of(DOCUMENT);
        ViewData authoritative;
        private CompletableFuture<ExtensionRpcContracts.CallResult> command =
                CompletableFuture.completedFuture(new ExtensionRpcContracts.CallResult(new CanonicalPayload("{}"), 1));
        private Consumer<ExtensionRpcContracts.ExtensionEvent> listener = ignored -> {};
        int loads;
        int catalogs;
        private int executions;

        FakeGateway(ViewData authoritative) {
            this.authoritative = authoritative;
        }

        @Override
        public CompletableFuture<List<ExtensionRpcContracts.ViewDocument>> list(String extensionId) {
            catalogs++;
            if (!catalogsToReturn.isEmpty()) {
                return catalogsToReturn.removeFirst();
            }
            return CompletableFuture.completedFuture(catalog);
        }

        @Override
        public CompletableFuture<ViewData> load(
                WorkspaceId workspaceId,
                ExtensionRpcContracts.ViewDocument document,
                ViewSchema viewSchema,
                ViewLoadRequest request) {
            assertEquals(WORKSPACE, workspaceId);
            loads++;
            loadRequests.add(request);
            if (!loadsToReturn.isEmpty()) {
                return loadsToReturn.removeFirst();
            }
            return CompletableFuture.completedFuture(authoritative);
        }

        @Override
        public CompletableFuture<ExtensionRpcContracts.CallResult> execute(
                WorkspaceId workspaceId, String extensionId, ViewCommandInvocation invocation) {
            assertEquals(WORKSPACE, workspaceId);
            executions++;
            return command;
        }

        @Override
        public CompletableFuture<AttachmentRef> upload(WorkspaceId workspaceId, ViewAttachmentUploadRequest request) {
            assertEquals(WORKSPACE, workspaceId);
            return CompletableFuture.failedFuture(new AssertionError("本测试不上传 Attachment"));
        }

        @Override
        public DesktopNotificationSubscription subscribe(
                WorkspaceId workspaceId,
                String extensionId,
                Consumer<ExtensionRpcContracts.ExtensionEvent> eventListener) {
            assertEquals(WORKSPACE, workspaceId);
            listener = eventListener;
            return () -> subscriptionClosed.set(true);
        }

        private void emit(ExtensionRpcContracts.ExtensionEvent event) {
            listener.accept(event);
        }
    }
}
