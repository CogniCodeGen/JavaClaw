package com.javaclaw.ui.javafx.task;

import com.javaclaw.application.task.SddTaskApplicationService;
import com.javaclaw.application.task.SddTaskApplicationService.CreateCommand;
import com.javaclaw.application.task.SddTaskApplicationService.EventListener;
import com.javaclaw.application.task.SddTaskApplicationService.Snapshot;
import com.javaclaw.application.task.SddTaskApplicationService.Task;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.task.sdd.run.SddTaskState;
import com.javaclaw.task.sdd.spec.OpenSpecChange;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class SddTaskFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private ManagedTaskExecutor executor;
    private SddTaskView view;
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
        if (view != null) runFx(view::close);
        if (context != null) context.close();
    }

    @Test
    void loadsCompleteControllerTreeAndReleasesSubscription() throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(SddTaskViewFactory.class).create(null));
        runFx(view.controller()::prepare);

        awaitFx(() -> taskList().getItems().size() == 1);
        assertEquals("1 运行中 · 0 待人工 · 共 1", callFx(() ->
                ((Label) view.root().lookup("#listSubtitle")).getText()));
        assertEquals(1000.0, callFx(() -> view.root().getScene().getWidth()));
        assertEquals(700.0, callFx(() -> view.root().getScene().getHeight()));
        assertEquals("托管任务", callFx(() ->
                ((Stage) view.root().getScene().getWindow()).getTitle()));

        runFx(view::close);
        view = null;
        assertTrue(service.subscriptionClosed);
    }

    @Test
    void reusableCellsLoadTheirFxmlOnce() throws Exception {
        prepareContext();
        runFx(() -> {
            SddTaskCell taskCell = context.getBean(SddTaskCellFactory.class)
                    .create(task -> { });
            taskCell.updateItem(service.task, false);
            assertNotNull(taskCell.getGraphic());

            SddDetailCellFactory cells = context.getBean(SddDetailCellFactory.class);
            assertNotNull(cells.change());
            assertNotNull(cells.scenario());
            assertNotNull(cells.checklist());
            assertNotNull(cells.log());
        });
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        service = new FakeService();
        executor = new ManagedTaskExecutor();
        context.registerBean(SddTaskApplicationService.class, () -> service);
        context.registerBean(ManagedTaskExecutor.class, () -> executor,
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean("workspaceTaskScope", TaskScope.class,
                () -> executor.openScope("sdd-fxml-test", 8),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(SddTaskCellFactory.class, SddTaskCellFactory::new);
        context.registerBean(SddDetailCellFactory.class, SddDetailCellFactory::new);
        context.registerBean(SddBudgetDialogFactory.class,
                () -> new SddBudgetDialogFactory(context.getBean(SpringFxmlLoader.class)));
        context.registerBean(SddTaskViewFactory.class,
                () -> new SddTaskViewFactory(context.getBean(SpringFxmlLoader.class)));
        context.refresh();
    }

    @SuppressWarnings("unchecked")
    private ListView<Task> taskList() {
        return (ListView<Task>) view.root().lookup("#taskList");
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

    private static final class FakeService implements SddTaskApplicationService {
        private final Task task = new Task("task-1", "迁移 SDD 页面", "保持行为不变",
                "/tmp/project", "auto", 120_000, "none",
                "2026-08-12 09:00:00", "2026-08-12 09:01:00",
                SddTaskState.RUNNING, 36, "", 1_000, 400, Map.of(), Map.of());
        private boolean subscriptionClosed;

        @Override public Snapshot snapshot() { return new Snapshot(List.of(task)); }
        @Override public Task require(String taskId) { return task; }
        @Override public String generateTitle(String description) { return task.title(); }
        @Override public Task create(CreateCommand command) { return task; }
        @Override public void start(String taskId, String completionStamp) { }
        @Override public void resume(String taskId, String completionStamp) { }
        @Override public void pause(String taskId) { }
        @Override public void cancel(String taskId) { }
        @Override public void delete(String taskId) { }
        @Override public Task updateTokenBudget(String taskId, long newBudget) { return task; }
        @Override public Optional<OpenSpecChange> specification(String taskId) {
            return Optional.empty();
        }
        @Override public AutoCloseable observe(EventListener listener) {
            return () -> subscriptionClosed = true;
        }
        @Override public void suspendForRuntimeTransition() { }
    }
}
