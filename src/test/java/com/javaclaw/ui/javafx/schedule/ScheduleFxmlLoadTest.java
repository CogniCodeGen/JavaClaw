package com.javaclaw.ui.javafx.schedule;

import com.javaclaw.api.interaction.ChoiceRequest;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.schedule.ScheduleApplicationService;
import com.javaclaw.application.schedule.ScheduleApplicationService.*;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
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
class ScheduleFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private ScheduleView view;
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
    void loadsCompleteWindowAndReleasesSubscription() throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(ScheduleViewFactory.class).create(null));
        runFx(view.controller()::prepare);

        awaitFx(() -> taskList().getItems().size() == 1);
        assertEquals("1 个启用 · 共 1 个", callFx(() ->
                ((Label) view.root().lookup("#listSubtitle")).getText()));
        assertNotNull(callFx(() -> view.controller().details()));
        assertEquals(960.0, callFx(() -> view.root().getScene().getWidth()));
        assertEquals(680.0, callFx(() -> view.root().getScene().getHeight()));
        assertEquals("定时任务", callFx(() ->
                ((Stage) view.root().getScene().getWindow()).getTitle()));

        runFx(view::close);
        view = null;
        assertTrue(service.subscriptionClosed);
    }

    @Test
    void reusableCellsLoadTheirFxmlOnceAndRenderSnapshots() throws Exception {
        prepareContext();
        runFx(() -> {
            ScheduleTaskCell taskCell = new ScheduleTaskCellFactory().create(task -> { });
            taskCell.updateItem(service.snapshot().tasks().getFirst(), false);
            assertNotNull(taskCell.getGraphic());
            ScheduleHistoryCell historyCell = new ScheduleHistoryCellFactory().create();
            historyCell.updateItem(service.snapshot().tasks().getFirst().history().getFirst(), false);
            assertNotNull(historyCell.getGraphic());
        });
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        service = new FakeService();
        context.registerBean(ScheduleApplicationService.class, () -> service);
        context.registerBean(UserInteractionPort.class, AllowInteraction::new);
        context.registerBean(DialogService.class,
                () -> new DialogService(context.getBean(UserInteractionPort.class)));
        context.registerBean(ManagedTaskExecutor.class, () -> new ManagedTaskExecutor(),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(ScheduleTaskCellFactory.class, ScheduleTaskCellFactory::new);
        context.registerBean(ScheduleHistoryCellFactory.class, ScheduleHistoryCellFactory::new);
        context.registerBean(ScheduleViewFactory.class,
                () -> new ScheduleViewFactory(context.getBean(SpringFxmlLoader.class)));
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

    private static final class AllowInteraction implements UserInteractionPort {
        @Override public boolean confirm(ConfirmRequest request) { return true; }
        @Override public String choose(ChoiceRequest request) { return "discard"; }
        @Override public void notify(ToastRequest request) { }
    }

    private static final class FakeService implements ScheduleApplicationService {
        private final Task task = new Task("daily", "每日简报", "", "daily",
                60, 60, "minute", "09:00", "", "", "生成简报", true, 1,
                "2026-08-10 09:00:00", "成功", "1.2s", 3, 0, false,
                "none", false, false, "", "", RuntimeState.ENABLED,
                java.time.LocalDateTime.now().plusHours(2), true,
                List.of(new History("2026-08-10 09:00:00", "成功", "1.2s", "完成")));
        private boolean subscriptionClosed;

        @Override public Snapshot snapshot() { return new Snapshot(List.of(task)); }
        @Override public Task createDraft(String name) { return task; }
        @Override public OperationResult save(SaveCommand command) {
            return new OperationResult(snapshot(), null, "已保存");
        }
        @Override public OperationResult setEnabled(
                SaveCommand command, boolean enabled, DisablePolicy disablePolicy) {
            return new OperationResult(snapshot(), null, enabled ? "已启用" : "已暂停");
        }
        @Override public OperationResult delete(String taskId) {
            return new OperationResult(new Snapshot(List.of()), null, "任务已删除");
        }
        @Override public OperationResult runNow(String taskId, boolean allowDisabled) {
            return new OperationResult(snapshot(), RunResult.STARTED, "已加入执行队列…");
        }
        @Override public AutoCloseable observe(EventListener listener) {
            return () -> subscriptionClosed = true;
        }
    }
}
