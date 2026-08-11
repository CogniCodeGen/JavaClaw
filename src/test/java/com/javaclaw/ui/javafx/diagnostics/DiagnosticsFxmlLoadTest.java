package com.javaclaw.ui.javafx.diagnostics;

import com.javaclaw.application.diagnostics.DiagnosticsApplicationService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class DiagnosticsFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private ViewHandle<BorderPane> handle;
    private FakeDiagnosticsService service;

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                started.countDown();
            });
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(() -> {
                Platform.setImplicitExit(false);
                started.countDown();
            });
        }
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (handle != null) runFx(handle::close);
        if (context != null) context.close();
    }

    @Test
    void queriesAndExportsThroughInjectedApplicationService() throws Exception {
        loadView(Optional.of(Path.of("/tmp/javaclaw-diagnostics.zip")));

        runFx(() -> {
            text("agentField").setText("planner");
            text("keywordField").setText("timeout");
            combo("eventBox").setValue("error");
            button("queryButton").fire();
        });
        awaitFx(() -> label("summaryLabel").getText().startsWith("共 2 条"));

        assertEquals("planner", service.lastQuery.get().agent());
        assertEquals("error", service.lastQuery.get().eventType());
        assertEquals("timeout", service.lastQuery.get().keyword());
        assertEquals(List.of("trace-one", "trace-two"),
                callFx(() -> List.copyOf(list("resultList").getItems())));

        runFx(() -> button("exportButton").fire());
        awaitFx(() -> label("summaryLabel").getText().contains("（4 KB）"));
        assertEquals(Path.of("/tmp/javaclaw-diagnostics.zip"), service.exportTarget.get());
    }

    @Test
    void mapsQueryFailureAndClosesController() throws Exception {
        loadView(Optional.empty());
        service.queryFailure = new IOException("trace unreadable");

        runFx(() -> button("queryButton").fire());
        awaitFx(() -> label("summaryLabel").getText().equals(
                "查询失败：trace unreadable"));

        DiagnosticsController controller = handle.controller(DiagnosticsController.class);
        runFx(handle::close);
        handle = null;
        assertTrue(controller.isClosed());
    }

    @Test
    void factoryPreservesWindowContractAndOwnsControllerLifecycle() throws Exception {
        prepareContext(Optional.empty());
        DiagnosticsViewFactory factory = new DiagnosticsViewFactory(
                context.getBean(SpringFxmlLoader.class));

        DiagnosticsView view = callFx(() -> factory.open(null));
        DiagnosticsController controller = callFx(view::controller);

        assertEquals("诊断面板", callFx(() -> view.stage().getTitle()));
        assertEquals(960.0, callFx(() -> view.stage().getScene().getWidth()));
        assertEquals(640.0, callFx(() -> view.stage().getScene().getHeight()));
        assertTrue(callFx(() -> view.stage().isShowing()));

        runFx(view::close);
        assertTrue(controller.isClosed());
        assertTrue(callFx(() -> !view.stage().isShowing()));
    }

    private void loadView(Optional<Path> target) throws Exception {
        prepareContext(target);
        URL resource = DiagnosticsFxmlLoadTest.class.getResource(
                "/fxml/diagnostics/diagnostics-view.fxml");
        handle = callFx(() -> context.getBean(SpringFxmlLoader.class).load(resource));
    }

    private void prepareContext(Optional<Path> target) {
        context = new AnnotationConfigApplicationContext();
        service = new FakeDiagnosticsService();
        context.registerBean(DiagnosticsApplicationService.class, () -> service);
        context.registerBean(DiagnosticsExportTargetPicker.class, () -> owner -> target);
        context.registerBean(ManagedTaskExecutor.class, () -> new ManagedTaskExecutor(),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.refresh();
    }

    private Button button(String id) { return (Button) handle.root().lookup("#" + id); }
    private Label label(String id) { return (Label) handle.root().lookup("#" + id); }
    private TextField text(String id) { return (TextField) handle.root().lookup("#" + id); }
    @SuppressWarnings("unchecked")
    private ComboBox<String> combo(String id) {
        return (ComboBox<String>) handle.root().lookup("#" + id);
    }
    @SuppressWarnings("unchecked")
    private ListView<String> list(String id) {
        return (ListView<String>) handle.root().lookup("#" + id);
    }

    private void awaitFx(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (callFx(condition)) return;
            Thread.sleep(10);
        }
        assertTrue(callFx(condition), "等待 JavaFX 状态更新超时");
    }

    private static void runFx(ThrowingRunnable action) throws Exception {
        callFx(() -> { action.run(); return null; });
    }

    private static <T> T callFx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.call();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (failure.get() instanceof Exception exception) throw exception;
        if (failure.get() instanceof Error error) throw error;
        return result.get();
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    private static final class FakeDiagnosticsService
            implements DiagnosticsApplicationService {
        private final AtomicReference<Query> lastQuery = new AtomicReference<>();
        private final AtomicReference<Path> exportTarget = new AtomicReference<>();
        private volatile IOException queryFailure;

        @Override
        public List<String> query(Query query) throws IOException {
            lastQuery.set(query);
            if (queryFailure != null) throw queryFailure;
            return List.of("trace-one", "trace-two");
        }

        @Override
        public ExportReceipt export(Path target) {
            exportTarget.set(target);
            return new ExportReceipt(target, 4096);
        }
    }
}
