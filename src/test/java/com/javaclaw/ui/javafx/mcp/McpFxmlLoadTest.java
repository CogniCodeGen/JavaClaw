package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.mcp.McpManagementApplicationService;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class McpFxmlLoadTest {
    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private McpCenterView center;
    private FakeService service;

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                started.countDown();
            });
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(started::countDown);
        }
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (center != null) runFx(center::close);
        if (context != null) context.close();
    }

    @Test
    void centerRendersCardsFiltersAndReleasesRuntimeSubscription() throws Exception {
        prepareContext();
        center = callFx(() -> context.getBean(McpCenterViewFactory.class).createPanel(() -> { }));
        McpCenterController controller = callFx(center::controller);
        runFx(() -> {
            new Scene(center.root(), 800, 620);
            center.root().applyCss();
            center.root().layout();
        });

        awaitFx(() -> serverList().getChildren().size() == 1);
        assertEquals("remote", callFx(() -> label("nameLabel").getText()));
        assertEquals("运行中 1/1 服务器 · 共 1 个工具",
                callFx(() -> label("overviewText").getText()));

        runFx(() -> ((TextField) center.root().lookup("#searchField")).setText("missing"));
        awaitFx(() -> ((VBox) center.root().lookup("#emptyState")).isVisible());
        runFx(() -> ((TextField) center.root().lookup("#searchField")).clear());
        awaitFx(() -> serverList().getChildren().size() == 1);

        runFx(center::close);
        center = null;
        assertTrue(controller.isClosed());
        assertTrue(service.subscriptionClosed);
    }

    @Test
    void editorLoadsReusableSecretRowsAndProducesImmutableCommand() throws Exception {
        prepareContext();
        runFx(() -> {
            try {
                ViewHandle<VBox> handle = context.getBean(SpringFxmlLoader.class).load(
                        getClass().getResource("/fxml/mcp/mcp-server-editor.fxml"));
                try {
                    McpServerEditorController controller =
                            handle.controller(McpServerEditorController.class);
                    controller.configure(service.snapshot().require("remote"));
                    new Scene(handle.root(), 600, 700);
                    handle.root().applyCss();
                    assertEquals(1, ((VBox) handle.root().lookup("#headerRows")).getChildren().size());
                    assertEquals("remote", controller.command().name());
                    assertEquals("Bearer secret", controller.command().headers().get("Authorization"));
                    assertTrue(controller.validate());
                } finally {
                    handle.close();
                }
            } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
            }
        });
    }

    @Test
    void standaloneFactoryPreservesWindowContract() throws Exception {
        prepareContext();
        center = callFx(() -> context.getBean(McpCenterViewFactory.class)
                .createWindow(null, () -> { }));
        runFx(center::show);
        assertEquals(960.0, callFx(() -> center.root().getScene().getWidth()));
        assertEquals(680.0, callFx(() -> center.root().getScene().getHeight()));
        assertEquals("MCP 服务器", callFx(() ->
                ((javafx.stage.Stage) center.root().getScene().getWindow()).getTitle()));
        runFx(center::close);
        center = null;
        assertTrue(service.subscriptionClosed);
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        service = new FakeService();
        context.registerBean(McpManagementApplicationService.class, () -> service);
        context.registerBean(UserInteractionPort.class, AllowInteraction::new);
        context.registerBean(DialogService.class,
                () -> new DialogService(context.getBean(UserInteractionPort.class)));
        context.registerBean(ManagedTaskExecutor.class, () -> new ManagedTaskExecutor(),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(McpToolRowFactory.class,
                () -> new McpToolRowFactory(context.getBean(SpringFxmlLoader.class)));
        context.registerBean(McpServerCardFactory.class,
                () -> new McpServerCardFactory(context.getBean(SpringFxmlLoader.class)));
        context.registerBean(McpKeyValueRowFactory.class,
                () -> new McpKeyValueRowFactory(context.getBean(SpringFxmlLoader.class)));
        context.registerBean(McpServerEditorFactory.class,
                () -> new McpServerEditorFactory(
                        context.getBean(SpringFxmlLoader.class),
                        new com.javaclaw.app.UIHelper(context.getBean(FxDispatcher.class))));
        context.registerBean(McpTemplateCellFactory.class,
                () -> new McpTemplateCellFactory(context.getBean(SpringFxmlLoader.class)));
        context.registerBean(McpTemplateDialogFactory.class,
                () -> new McpTemplateDialogFactory(
                        context.getBean(SpringFxmlLoader.class),
                        new com.javaclaw.app.UIHelper(context.getBean(FxDispatcher.class))));
        context.registerBean(McpImportDialogFactory.class,
                () -> new McpImportDialogFactory(
                        context.getBean(SpringFxmlLoader.class),
                        new com.javaclaw.app.UIHelper(context.getBean(FxDispatcher.class))));
        context.registerBean(McpLogDialogFactory.class,
                () -> new McpLogDialogFactory(
                        context.getBean(SpringFxmlLoader.class),
                        new com.javaclaw.app.UIHelper(context.getBean(FxDispatcher.class))));
        context.registerBean(McpCenterViewFactory.class,
                () -> new McpCenterViewFactory(context.getBean(SpringFxmlLoader.class)));
        context.refresh();
    }

    private VBox serverList() { return (VBox) center.root().lookup("#serverList"); }
    private Label label(String id) { return (Label) center.root().lookup("#" + id); }

    private static void awaitFx(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (callFx(condition::getAsBoolean)) return;
            Thread.sleep(10);
        }
        assertTrue(callFx(condition::getAsBoolean), "等待 JavaFX 状态超时");
    }

    private static void runFx(Runnable action) throws Exception {
        callFx(() -> { action.run(); return null; });
    }

    private static <T> T callFx(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { result.set(action.call()); }
            catch (Throwable thrown) { failure.set(thrown); }
            finally { completed.countDown(); }
        });
        assertTrue(completed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    private static final class AllowInteraction implements UserInteractionPort {
        @Override public boolean confirm(ConfirmRequest request) { return true; }
        @Override public void notify(ToastRequest request) { }
    }

    private static final class FakeService implements McpManagementApplicationService {
        private final List<Server> servers = new ArrayList<>(List.of(new Server(
                "remote", Transport.HTTP, "", List.of(), Map.of(),
                "https://example.com/mcp", Map.of("Authorization", "Bearer secret"), true,
                State.RUNNING, List.of(new Tool("search", "搜索网页")), "", 100)));
        private volatile boolean subscriptionClosed;

        @Override public synchronized Snapshot snapshot() { return new Snapshot(servers, "fake-h2"); }
        @Override public synchronized OperationResult save(SaveCommand command) { return ok("已保存"); }
        @Override public synchronized OperationResult delete(String name) {
            servers.removeIf(server -> server.name().equals(name)); return ok("已删除");
        }
        @Override public synchronized OperationResult setEnabled(String name, boolean enabled) {
            return ok(enabled ? "已启用" : "已禁用");
        }
        @Override public OperationResult start(String name) { return ok("已启动"); }
        @Override public OperationResult restart(String name) { return ok("已重启"); }
        @Override public OperationResult stop(String name) { return ok("已停止"); }
        @Override public TestResult test(SaveCommand command) {
            return new TestResult(true, List.of(new Tool("search", "搜索网页")),
                    10, "", "remote", "1");
        }
        @Override public ImportPreview previewImport(String json, String fallbackName) {
            return new ImportPreview(List.of());
        }
        @Override public OperationResult importJson(String json, String fallbackName) {
            return ok("已导入");
        }
        @Override public List<Template> templates() {
            return List.of(new Template("demo", "Demo", "模板", "npx",
                    List.of("-y", "demo"), List.of("API_KEY"), "search"));
        }
        @Override public LogSnapshot log(String serverName) {
            return new LogSnapshot(serverName, "", List.of("ready"));
        }
        @Override public AutoCloseable observeRuntime(Runnable listener) {
            return () -> subscriptionClosed = true;
        }
        private OperationResult ok(String message) { return new OperationResult(snapshot(), true, message); }
    }
}
