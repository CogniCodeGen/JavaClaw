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
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class McpFxmlLoadTest {
    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private McpCenterView center;
    private ViewHandle<VBox> editorHandle;
    private McpServerEditorController editor;
    private FakeService service;
    private AllowInteraction interaction;

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
        if (editorHandle != null) runFx(editorHandle::close);
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
            center.activate();
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

    @Test
    void editorValidatesBothTransportsRowsAndConnectionOutcomes() throws Exception {
        prepareContext();
        runFx(() -> {
            try {
                editorHandle = context.getBean(SpringFxmlLoader.class).load(
                        getClass().getResource("/fxml/mcp/mcp-server-editor.fxml"));
                editor = editorHandle.controller(McpServerEditorController.class);
                new Scene(editorHandle.root(), 600, 700);

                editor.configure((McpManagementApplicationService.Server) null);
                assertFalse(editor.validate());
                textField(editor, "nameField").setText("local");
                assertFalse(editor.validate());
                textField(editor, "commandField").setText("npx");
                textArea(editor, "argumentsArea").setText(" -y \n\n server ");
                assertTrue(editor.validate());
                assertEquals(List.of("-y", "server"), editor.command().arguments());

                for (String key : List.of("API_KEY", "TOKEN", "SECRET", "PASSWORD",
                        "PASSWD", "VISIBLE")) {
                    invoke(editor, "addEnvironment", new Class<?>[] {String.class, String.class},
                            key, "value-123456789");
                }
                assertEquals(6, editor.command().environment().size());
                invoke(editor, "addEnvironmentRequested");
                @SuppressWarnings("unchecked")
                List<McpKeyValueRowFactory.Row> environment =
                        field(editor, "environment", List.class);
                McpKeyValueRowController emptyRow = environment.getLast().controller();
                assertFalse(editor.command().environment().containsKey(""));
                invoke(emptyRow, "removeRequested");
                assertEquals(6, environment.size());

                invoke(editor, "addEnvironment", new Class<?>[] {String.class, String.class},
                        "DUP", "one");
                invoke(editor, "addEnvironment", new Class<?>[] {String.class, String.class},
                        "DUP", "two");
                assertFalse(editor.validate());

                editor.configure((McpManagementApplicationService.Server) null);
                textField(editor, "nameField").setText("remote");
                invoke(editor, "httpRequested");
                assertFalse(editor.validate());
                textField(editor, "urlField").setText("not a uri");
                assertFalse(editor.validate());
                textField(editor, "urlField").setText("ftp://example.test/mcp");
                assertFalse(editor.validate());
                textField(editor, "urlField").setText("http:///mcp");
                assertFalse(editor.validate());
                textField(editor, "urlField").setText("https://example.test/mcp");
                invoke(editor, "addHeader", new Class<?>[] {String.class, String.class},
                        "Authorization", "one");
                invoke(editor, "addHeader", new Class<?>[] {String.class, String.class},
                        "Authorization", "two");
                assertFalse(editor.validate());

                editor.configure(new McpManagementApplicationService.SaveCommand(
                        "", "seed", McpManagementApplicationService.Transport.HTTP,
                        "", List.of(), Map.of(), "https://example.test/mcp",
                        Map.of("X-Key", "secret"), false));
                assertTrue(editor.validate());
                editor.configure((McpManagementApplicationService.SaveCommand) null);
                invoke(editor, "stdioRequested");

                editor.configure(service.snapshot().require("remote"));
                invoke(editor, "testRequested");
            } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
            }
        });
        awaitFx(() -> service.testCalls == 1 && !serviceTestBusy());

        service.nextTestResult = new McpManagementApplicationService.TestResult(
                false, List.of(), 20, "unauthorized", "remote", "");
        runEditorTest();
        awaitFx(() -> service.testCalls == 2 && !serviceTestBusy());
        assertTrue(callFx(() -> editorResult().getText().contains("unauthorized")));

        List<McpManagementApplicationService.Tool> tools = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            tools.add(new McpManagementApplicationService.Tool("tool-" + index, "description"));
        }
        service.nextTestResult = new McpManagementApplicationService.TestResult(
                true, tools, 30, "", "remote", "1");
        runEditorTest();
        awaitFx(() -> service.testCalls == 3 && !serviceTestBusy());
        assertTrue(callFx(() -> editorResult().getText().contains("...")));

        service.testFailure = new IllegalStateException();
        runEditorTest();
        awaitFx(() -> service.testCalls == 4 && !serviceTestBusy());
        assertTrue(callFx(() -> editorResult().getText().contains("未知错误")));
    }

    @Test
    void serverCardRendersEveryStateAndEmitsEveryAction() throws Exception {
        prepareContext();
        runFx(() -> {
            McpServerCardFactory.Card card = context.getBean(McpServerCardFactory.class)
                    .create(server("running", McpManagementApplicationService.State.RUNNING,
                            true, true, "", Instant.now().minusSeconds(20).toEpochMilli(), 2),
                            (name, action) -> { });
            try {
                McpServerCardController controller = card.handle()
                        .controller(McpServerCardController.class);
                AtomicReference<McpServerCardController.Action> action = new AtomicReference<>();
                AtomicReference<String> actionName = new AtomicReference<>();

                for (long age : List.of(20L, 120L, 7_200L, 172_800L, 0L)) {
                    controller.configure(server("running", McpManagementApplicationService.State.RUNNING,
                            true, true, "", age == 0 ? 0
                                    : Instant.now().minusSeconds(age).toEpochMilli(),
                            age == 0 ? 0 : 2),
                            (name, requested) -> {
                                actionName.set(name);
                                action.set(requested);
                            });
                }
                controller.configure(server("starting", McpManagementApplicationService.State.STARTING,
                        true, false, "", 0, 0), (name, requested) -> action.set(requested));
                controller.configure(server("failed", McpManagementApplicationService.State.FAILED,
                        true, false, "", 0, 0), (name, requested) -> action.set(requested));
                controller.configure(server("failed-detail", McpManagementApplicationService.State.FAILED,
                        true, false, "connection refused", 0, 0),
                        (name, requested) -> action.set(requested));
                controller.configure(server("stopped", McpManagementApplicationService.State.STOPPED,
                        false, false, "", 0, 0), (name, requested) -> action.set(requested));

                controller.configure(server("actions", McpManagementApplicationService.State.RUNNING,
                        true, true, "", 0, 1), (name, requested) -> {
                    actionName.set(name);
                    action.set(requested);
                });
                for (String method : List.of("startRequested", "restartRequested", "stopRequested",
                        "editRequested", "deleteRequested", "logRequested", "copyRequested")) {
                    invoke(controller, method);
                    assertNotNull(action.get());
                }
                CheckBox enabled = field(controller, "enabledCheck", CheckBox.class);
                enabled.setSelected(true);
                invoke(controller, "enabledChanged");
                assertEquals(McpServerCardController.Action.ENABLE, action.get());
                enabled.setSelected(false);
                invoke(controller, "enabledChanged");
                assertEquals(McpServerCardController.Action.DISABLE, action.get());
                assertEquals("actions", actionName.get());

                setField(controller, "action", null);
                invoke(controller, "startRequested");
                assertThrows(NullPointerException.class,
                        () -> controller.configure(service.snapshot().require("remote"), null));
            } finally {
                card.close();
            }
        });
    }

    @Test
    void centerCoordinatesFiltersCardOperationsFailuresAndRuntimeRefresh() throws Exception {
        prepareContext();
        AtomicInteger configurationChanges = new AtomicInteger();
        center = callFx(() -> context.getBean(McpCenterViewFactory.class)
                .createPanel(configurationChanges::incrementAndGet));
        McpCenterController controller = center.controller();
        runFx(() -> {
            new Scene(center.root(), 900, 700);
            center.root().applyCss();
            center.root().layout();
            center.activate();
        });
        awaitFx(() -> serverList().getChildren().size() == 1);

        service.replaceServers(List.of(
                server("running", McpManagementApplicationService.State.RUNNING,
                        true, true, "", 0, 2),
                server("starting", McpManagementApplicationService.State.STARTING,
                        true, false, "", 0, 0),
                server("failed", McpManagementApplicationService.State.FAILED,
                        true, false, "boom", 0, 0),
                server("stopped", McpManagementApplicationService.State.STOPPED,
                        false, false, "", 0, 0)));
        runFx(() -> {
            controller.configure(true, configurationChanges::incrementAndGet);
            invoke(controller, "requestSnapshot");
        });
        awaitFx(() -> serverList().getChildren().size() == 4);
        runFx(() -> {
            invoke(controller, "runningRequested");
            assertEquals(2, serverList().getChildren().size());
            invoke(controller, "failedRequested");
            assertEquals(1, serverList().getChildren().size());
            invoke(controller, "stoppedRequested");
            assertEquals(1, serverList().getChildren().size());
            invoke(controller, "allRequested");
            assertEquals(4, serverList().getChildren().size());
            ((TextField) center.root().lookup("#searchField")).setText("missing");
            assertTrue(((VBox) center.root().lookup("#emptyState")).isVisible());
            invoke(controller, "clearSearchRequested");
            controller.configure(false, configurationChanges::incrementAndGet);
            invoke(controller, "copyStorageRequested");
            invoke(controller, "copyLaunch", new Class<?>[] {String.class}, "running");
            invoke(controller, "closeRequested");
        });

        for (McpServerCardController.Action action : List.of(
                McpServerCardController.Action.START,
                McpServerCardController.Action.RESTART,
                McpServerCardController.Action.STOP,
                McpServerCardController.Action.ENABLE,
                McpServerCardController.Action.DISABLE)) {
            int before = service.operationCalls;
            runFx(() -> invoke(controller, "cardAction",
                    new Class<?>[] {String.class, McpServerCardController.Action.class},
                    "running", action));
            awaitFx(() -> service.operationCalls > before && !centerBusy());
        }
        assertTrue(configurationChanges.get() >= 5);

        interaction.confirmAllowed = false;
        runFx(() -> invoke(controller, "delete", new Class<?>[] {String.class}, "running"));
        awaitFx(() -> !centerBusy());
        assertEquals(0, service.deleteCalls);
        interaction.confirmAllowed = true;
        runFx(() -> invoke(controller, "delete", new Class<?>[] {String.class}, "running"));
        awaitFx(() -> service.deleteCalls == 1 && !centerBusy());

        service.nullOperationResult = true;
        int changesBeforeNull = configurationChanges.get();
        runFx(() -> invoke(controller, "cardAction",
                new Class<?>[] {String.class, McpServerCardController.Action.class},
                "starting", McpServerCardController.Action.STOP));
        awaitFx(() -> !centerBusy());
        assertEquals(changesBeforeNull, configurationChanges.get());
        service.nullOperationResult = false;

        service.runtimeSucceeded = false;
        runFx(() -> invoke(controller, "cardAction",
                new Class<?>[] {String.class, McpServerCardController.Action.class},
                "failed", McpServerCardController.Action.RESTART));
        awaitFx(() -> label("embeddedStatusLabel").getText().contains("runtime message"));
        service.runtimeSucceeded = true;

        service.operationFailure = new IllegalStateException();
        runFx(() -> invoke(controller, "cardAction",
                new Class<?>[] {String.class, McpServerCardController.Action.class},
                "stopped", McpServerCardController.Action.START));
        awaitFx(() -> label("embeddedStatusLabel").getText().contains("未知错误"));
        service.operationFailure = null;

        service.snapshotFailure = new IllegalStateException("snapshot offline");
        service.emitRuntime();
        awaitFx(() -> label("embeddedStatusLabel").getText().contains("snapshot offline"));
        service.snapshotFailure = null;
        runFx(() -> invoke(controller, "refreshRequested"));
        awaitFx(() -> !centerBusy());

        service.replaceServers(List.of());
        runFx(() -> invoke(controller, "requestSnapshot"));
        awaitFx(() -> ((VBox) center.root().lookup("#emptyState")).isVisible());
        assertEquals("还没有 MCP 服务器", callFx(() -> label("emptyTitle").getText()));

        service.closeSubscriptionThrows = true;
        assertThrows(AssertionError.class, () -> runFx(center::close));
        center = null;
        assertTrue(service.subscriptionClosed);
    }

    private void runEditorTest() throws Exception {
        runFx(() -> invoke(editor, "testRequested"));
    }

    private boolean serviceTestBusy() {
        return field(editor, "testButton", Button.class).isDisabled();
    }

    private Label editorResult() {
        return field(editor, "testResultLabel", Label.class);
    }

    private boolean centerBusy() {
        return field(center.controller(), "loadingOverlay", javafx.scene.layout.StackPane.class)
                .isVisible();
    }

    private static McpManagementApplicationService.Server server(
            String name,
            McpManagementApplicationService.State state,
            boolean enabled,
            boolean http,
            String error,
            long startedAt,
            int toolCount) {
        List<McpManagementApplicationService.Tool> tools = new ArrayList<>();
        for (int index = 0; index < toolCount; index++) {
            tools.add(new McpManagementApplicationService.Tool("tool-" + index, "description"));
        }
        return new McpManagementApplicationService.Server(
                name,
                http ? McpManagementApplicationService.Transport.HTTP
                        : McpManagementApplicationService.Transport.STDIO,
                http ? "" : "npx",
                http ? List.of() : List.of("-y", "server"),
                http ? Map.of() : Map.of("API_KEY", "short", "TOKEN", "1234567890abcdef"),
                http ? "https://example.test/mcp" : "",
                http ? Map.of("Authorization", "Bearer-1234567890") : Map.of(),
                enabled, state, tools, error, startedAt);
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        service = new FakeService();
        interaction = new AllowInteraction();
        context.registerBean(McpManagementApplicationService.class, () -> service);
        context.registerBean(UserInteractionPort.class, () -> interaction);
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

    private static TextField textField(Object target, String name) {
        return field(target, name, TextField.class);
    }

    private static TextArea textArea(Object target, String name) {
        return field(target, name, TextArea.class);
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法读取测试字段：" + name, failure);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法设置测试字段：" + name, failure);
        }
    }

    private static void invoke(Object target, String methodName) {
        invoke(target, methodName, new Class<?>[0]);
    }

    private static void invoke(
            Object target, String methodName, Class<?>[] parameterTypes, Object... arguments) {
        try {
            var method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法调用 Controller 方法：" + methodName, failure);
        }
    }

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
        private volatile boolean confirmAllowed = true;

        @Override public boolean confirm(ConfirmRequest request) { return confirmAllowed; }
        @Override public void notify(ToastRequest request) { }
    }

    private static final class FakeService implements McpManagementApplicationService {
        private final List<Server> servers = new ArrayList<>(List.of(new Server(
                "remote", Transport.HTTP, "", List.of(), Map.of(),
                "https://example.com/mcp", Map.of("Authorization", "Bearer secret"), true,
                State.RUNNING, List.of(new Tool("search", "搜索网页")), "", 100)));
        private volatile boolean subscriptionClosed;
        private volatile boolean closeSubscriptionThrows;
        private volatile boolean nullOperationResult;
        private volatile boolean runtimeSucceeded = true;
        private volatile int snapshotCalls;
        private volatile int operationCalls;
        private volatile int deleteCalls;
        private volatile int testCalls;
        private volatile RuntimeException snapshotFailure;
        private volatile RuntimeException operationFailure;
        private volatile RuntimeException testFailure;
        private volatile TestResult nextTestResult = new TestResult(
                true, List.of(new Tool("search", "搜索网页")), 10, "", "remote", "1");
        private volatile Runnable runtimeListener;

        @Override public synchronized Snapshot snapshot() {
            snapshotCalls++;
            if (snapshotFailure != null) throw snapshotFailure;
            return new Snapshot(servers, "fake-h2");
        }
        @Override public synchronized OperationResult save(SaveCommand command) {
            return operation("已保存");
        }
        @Override public synchronized OperationResult delete(String name) {
            deleteCalls++;
            servers.removeIf(server -> server.name().equals(name));
            return operation("已删除");
        }
        @Override public synchronized OperationResult setEnabled(String name, boolean enabled) {
            return operation(enabled ? "已启用" : "已禁用");
        }
        @Override public synchronized OperationResult start(String name) {
            return operation("已启动");
        }
        @Override public synchronized OperationResult restart(String name) {
            return operation("已重启");
        }
        @Override public synchronized OperationResult stop(String name) {
            return operation("已停止");
        }
        @Override public TestResult test(SaveCommand command) {
            testCalls++;
            if (testFailure != null) throw testFailure;
            return nextTestResult;
        }
        @Override public ImportPreview previewImport(String json, String fallbackName) {
            return new ImportPreview(List.of());
        }
        @Override public OperationResult importJson(String json, String fallbackName) {
            return operation("已导入");
        }
        @Override public List<Template> templates() {
            return List.of(new Template("demo", "Demo", "模板", "npx",
                    List.of("-y", "demo"), List.of("API_KEY"), "search"));
        }
        @Override public LogSnapshot log(String serverName) {
            return new LogSnapshot(serverName, "", List.of("ready"));
        }
        @Override public AutoCloseable observeRuntime(Runnable listener) {
            runtimeListener = listener;
            return () -> {
                subscriptionClosed = true;
                if (closeSubscriptionThrows) throw new IllegalStateException("close failure");
            };
        }
        private synchronized OperationResult operation(String message) {
            operationCalls++;
            if (operationFailure != null) throw operationFailure;
            if (nullOperationResult) return null;
            return new OperationResult(snapshot(), runtimeSucceeded,
                    runtimeSucceeded ? message : "runtime message");
        }
        private synchronized void replaceServers(List<Server> values) {
            servers.clear();
            servers.addAll(values);
        }
        private void emitRuntime() {
            Runnable current = runtimeListener;
            if (current != null) current.run();
        }
    }
}
