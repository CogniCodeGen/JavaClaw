package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.view.ViewAttachmentUploadRequest;
import com.javaclaw.desktop.view.ViewCommandInvocation;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewLoadRequest;
import com.javaclaw.extension.spi.GraphBrowsing;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.ViewSchemaWireCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewGraphBrowsingPageTest {
    @Test
    void 原生图谱浏览只发送声明Query且窗口属于当前页面() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            ViewSchemaSettingsPage page = page(gateway);
            TextField filter = descendants(page.content()).stream()
                    .filter(TextField.class::isInstance)
                    .map(TextField.class::cast)
                    .findFirst()
                    .orElseThrow();
            filter.setText("筛选词");
            button(page.content(), "筛选").fire();
            assertTrue(gateway.loads.getLast().graphWindows().get("g").contains("筛选词"));
            button(page.content(), "展开 50 个").fire();
            assertEquals("custom/neighbors", gateway.operation);
            assertTrue(gateway.arguments.json().contains("\"id\":\"a\""));
            gateway.query.complete(result());
            assertTrue(gateway.loads.getLast().graphWindows().get("g").contains("new"));
            Gateway independent = new Gateway();
            ViewSchemaSettingsPage second = page(independent);
            assertEquals(Map.of(), independent.loads.getLast().graphWindows());
            page.dispose();
            second.dispose();
        });
    }

    @Test
    void 工作区撤销后迟到邻居结果不能重新加载旧页面() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            ViewSchemaSettingsPage page = page(gateway);
            button(page.content(), "展开 50 个").fire();
            int before = gateway.loads.size();
            page.workspaceChanged(Optional.empty());
            gateway.query.complete(result());
            assertEquals(before, gateway.loads.size());
            page.dispose();
        });
    }

    @Test
    void 邻居查询期间定时对账与失效通知都延后且展开结果不会丢失() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            ViewSchemaSettingsPage page = page(gateway);
            try {
                button(page.content(), "展开 50 个").fire();
                int before = gateway.loads.size();
                assertFalse(reconciliationAllowed(page));
                // 同时覆盖计时器准入和绕过计时器的主动失效入口，二者都不得推进展开请求的 epoch。
                page.refreshAuthoritativeState();
                gateway.listener.accept(new ExtensionRpcContracts.ExtensionEvent(
                        DesktopTestFixtures.workspace().id(), "test", "graph", "a", "update", 2));
                assertEquals(before, gateway.loads.size());
                gateway.query.complete(result());
                assertEquals(before + 1, gateway.loads.size());
                assertTrue(gateway.loads.getLast().graphWindows().get("g").contains("new"));
                assertTrue(reconciliationAllowed(page));
            } finally {
                page.dispose();
            }
        });
    }

    @Test
    void 邻居查询失败后可以继续刷新且不会永久阻塞对账() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            ViewSchemaSettingsPage page = page(gateway);
            try {
                button(page.content(), "展开 50 个").fire();
                page.refreshAuthoritativeState();
                int before = gateway.loads.size();
                gateway.query.completeExceptionally(new IllegalStateException("邻居查询失败"));
                assertTrue(reconciliationAllowed(page));
                page.refreshAuthoritativeState();
                assertEquals(before + 1, gateway.loads.size());
                assertTrue(reconciliationAllowed(page));
            } finally {
                page.dispose();
            }
        });
    }

    @Test
    void 离页后的迟到展开不能解除重新激活页面的加载状态() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            ViewSchemaSettingsPage page = page(gateway);
            try {
                button(page.content(), "展开 50 个").fire();
                page.deactivate();
                CompletableFuture<ViewData> resumed = new CompletableFuture<>();
                gateway.nextLoad = resumed;
                page.activate();
                int before = gateway.loads.size();
                gateway.query.complete(result());
                assertFalse(reconciliationAllowed(page));
                assertEquals(before, gateway.loads.size());
                resumed.complete(Gateway.data());
                assertTrue(reconciliationAllowed(page));
            } finally {
                page.dispose();
            }
        });
    }

    private static boolean reconciliationAllowed(ViewSchemaSettingsPage page) {
        Object[] callbacks = (Object[]) page.content().getProperties().get(ViewPageReconciler.class);
        return ((BooleanSupplier) callbacks[0]).getAsBoolean();
    }

    private static ViewSchemaSettingsPage page(Gateway gateway) {
        var page = new ViewSchemaSettingsPage("test", "关系", "浏览", gateway);
        page.workspaceChanged(Optional.of(DesktopTestFixtures.workspace()));
        page.activate();
        return page;
    }

    private static ExtensionRpcContracts.CallResult result() {
        return new ExtensionRpcContracts.CallResult(
                new CanonicalJson()
                        .encode(Map.of(
                                "graph",
                                Map.of("nodes", List.of(Map.of("id", "new"))),
                                "hasMore",
                                false,
                                "nextCursor",
                                "")),
                1);
    }

    private static Button button(Node root, String text) {
        return descendants(root).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> button.getText().equals(text))
                .findFirst()
                .orElseThrow();
    }

    private static List<Node> descendants(Node root) {
        List<Node> result = new ArrayList<>();
        result.add(root);
        if (root instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> result.addAll(descendants(child)));
        }
        return result;
    }

    private static final class Gateway implements ExtensionSettingsGateway {
        private final List<ViewLoadRequest> loads = new ArrayList<>();
        private final CompletableFuture<ExtensionRpcContracts.CallResult> query = new CompletableFuture<>();
        private String operation;
        private CanonicalPayload arguments;
        private CompletableFuture<ViewData> nextLoad;
        private Consumer<ExtensionRpcContracts.ExtensionEvent> listener;

        @Override
        public CompletableFuture<List<ExtensionRpcContracts.ViewDocument>> list(String extensionId) {
            var schema = new ViewSchema(
                    2,
                    "view",
                    "关系",
                    List.of(
                            new ViewDataSource("nodes", "view/nodes", Map.of(), List.of(), 200),
                            new ViewDataSource("edges", "view/edges", Map.of(), List.of(), 200)),
                    List.of(new ViewSchema.Graph(
                            "g", "图", "nodes", "edges", "id", "label", "state", "source", "target")),
                    Map.of("g", new GraphBrowsing("custom/neighbors", "window", "search", "inactive", "ids")));
            return CompletableFuture.completedFuture(List.of(new ExtensionRpcContracts.ViewDocument(
                    "test", "view", new ViewSchemaWireCodec(new CanonicalJson()).encode(schema))));
        }

        @Override
        public CompletableFuture<ViewData> load(
                WorkspaceId workspace,
                ExtensionRpcContracts.ViewDocument document,
                ViewSchema schema,
                ViewLoadRequest request) {
            loads.add(request);
            if (nextLoad != null) {
                CompletableFuture<ViewData> result = nextLoad;
                nextLoad = null;
                return result;
            }
            return CompletableFuture.completedFuture(data());
        }

        private static ViewData data() {
            return new ViewData(Map.of(
                    "nodes",
                    new ViewData.Source(
                            List.of(Map.of("id", "a", "label", "节点", "state", "ACTIVE")),
                            Map.of(),
                            "",
                            "",
                            false,
                            1,
                            0,
                            Optional.of("a")),
                    "edges",
                    ViewData.Source.empty()));
        }

        @Override
        public CompletableFuture<ExtensionRpcContracts.CallResult> query(
                WorkspaceId workspace, String extension, String requestedOperation, CanonicalPayload payload) {
            operation = requestedOperation;
            arguments = payload;
            return query;
        }

        @Override
        public CompletableFuture<ExtensionRpcContracts.CallResult> execute(
                WorkspaceId workspace, String extension, ViewCommandInvocation invocation) {
            throw new AssertionError("图谱浏览不能写业务命令");
        }

        @Override
        public CompletableFuture<AttachmentRef> upload(WorkspaceId workspace, ViewAttachmentUploadRequest request) {
            throw new AssertionError("图谱浏览不能上传");
        }

        @Override
        public DesktopNotificationSubscription subscribe(
                WorkspaceId workspace, String extension, Consumer<ExtensionRpcContracts.ExtensionEvent> listener) {
            this.listener = listener;
            return () -> {};
        }
    }
}
