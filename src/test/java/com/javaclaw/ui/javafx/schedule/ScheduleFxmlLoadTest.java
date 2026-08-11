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
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.LocalDateTime;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
class ScheduleFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private ScheduleView view;
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
            taskCell.updateItem(task("running", "interval", "minute", "",
                    RuntimeState.RUNNING, true, false, true,
                    "", "", LocalDateTime.now().plusMinutes(1), "", List.of()), false);
            taskCell.updateItem(task("queued", "interval", "minute", "",
                    RuntimeState.QUEUED, true, false, true,
                    "", "", null, "", List.of()), false);
            taskCell.updateItem(task("builtin", "interval", "minute", "",
                    RuntimeState.BUILTIN, true, true, true,
                    "", "", null, "scheduler", List.of()), false);
            taskCell.updateItem(task("failed", "daily", "day", "",
                    RuntimeState.PAUSED, false, false, true,
                    "2026-08-10", "失败", null, "", List.of()), false);
            taskCell.updateItem(null, true);
            assertNull(taskCell.getGraphic());

            assertEquals("即将运行", ScheduleTaskCell.relative(LocalDateTime.now().minusSeconds(1)));
            assertTrue(ScheduleTaskCell.relative(LocalDateTime.now().plusSeconds(30)).endsWith("s"));
            assertTrue(ScheduleTaskCell.relative(LocalDateTime.now().plusMinutes(5)).endsWith("m"));
            assertTrue(ScheduleTaskCell.relative(LocalDateTime.now().plusHours(3)).contains("h"));
            assertTrue(ScheduleTaskCell.relative(LocalDateTime.now().plusDays(2)).contains("d"));

            ScheduleHistoryCell historyCell = new ScheduleHistoryCellFactory().create();
            historyCell.updateItem(service.snapshot().tasks().getFirst().history().getFirst(), false);
            assertNotNull(historyCell.getGraphic());
            historyCell.updateItem(new History("now", "失败", "2s", "error"), false);
            historyCell.updateItem(new History("now", "已取消", "1s", "cancelled"), false);
            historyCell.updateItem(null, true);
            assertNull(historyCell.getGraphic());
        });
    }

    @Test
    void detailControllerRendersEveryTriggerRuntimeAndHistoryVariant() throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(ScheduleViewFactory.class).create(null));
        ScheduleDetailController details = view.controller().details();
        AtomicReference<Boolean> toggled = new AtomicReference<>();
        AtomicBoolean changed = new AtomicBoolean();
        AtomicBoolean deleted = new AtomicBoolean();

        runFx(() -> {
            details.configure(toggled::set, () -> deleted.set(true), () -> changed.set(true));
            details.show(task("builtin-manual", "interval", "minute", "",
                    RuntimeState.BUILTIN, true, true, true,
                    "", "", null, "", List.of()), false);
            assertTrue(label("#builtinNote").getText().contains("手动触发"));
            assertEquals("—", label("#builtinSource").getText());

            details.show(task("builtin-passive", "interval", "minute", "",
                    RuntimeState.BUILTIN, true, true, false,
                    "", "", null, "scheduler", List.of()), false);
            assertTrue(label("#builtinNote").getText().contains("不可编辑"));
            assertEquals("scheduler", label("#builtinSource").getText());

            List<History> history = List.of(new History("now", "成功", "1s", "done"));
            details.show(task("once", "once", "minute", "2026-08-12 08:30",
                    RuntimeState.RUNNING, true, false, true,
                    "2026-08-11", "成功", LocalDateTime.now().plusHours(1), "", history), false);
            assertEquals("运行中", label("#stateLabel").getText());
            assertEquals("2026-08-12 08:30", details.command().onceDateTime());
            assertTrue(node("#historyList").isVisible());

            details.show(task("stopping", "interval", "hour", "",
                    RuntimeState.RUNNING, false, false, true,
                    "2026-08-11", "已取消", null, "", List.of()), false);
            assertEquals("正在停止…", label("#stateLabel").getText());
            assertEquals("执行中…", label("#nextStat").getText());

            details.show(task("queued", "daily", "day", "",
                    RuntimeState.QUEUED, true, false, true,
                    "2026-08-11", "失败", null, "", List.of()), false);
            assertEquals("排队中", label("#stateLabel").getText());

            details.show(task("waiting", "cron", "minute", "",
                    RuntimeState.ENABLED, true, false, true,
                    "", "", null, "", List.of()), false);
            assertEquals("等待调度", label("#nextStat").getText());
            assertFalse(label("#lastBadge").isVisible());

            details.show(task("scheduled", "", "day", "",
                    RuntimeState.ENABLED, true, false, true,
                    "", "", LocalDateTime.now().plusDays(1), "", List.of()), true);
            assertTrue(label("#nextStat").getText().contains("-"));
            assertEquals("interval", details.command().triggerType());

            details.show(task("paused", "interval", "minute", "",
                    RuntimeState.PAUSED, false, false, true,
                    "", "", null, "", List.of()), false);
            assertEquals("已暂停", label("#stateLabel").getText());
            assertEquals("—", label("#nextStat").getText());

            details.show(task("builtin-state", "interval", "minute", "",
                    RuntimeState.BUILTIN, true, false, true,
                    "", "", null, "", List.of()), false);
            assertEquals("常驻运行", label("#stateLabel").getText());

            TextField interval = textField("#intervalValueField");
            ComboBox<String> unit = combo("#intervalUnitCombo");
            interval.setText("bad");
            unit.setValue("小时");
            assertEquals(1, details.command().intervalValue());
            assertEquals("hour", details.command().intervalUnit());
            interval.setText("0");
            unit.setValue("天");
            assertEquals(1, details.command().intervalValue());
            assertEquals("day", details.command().intervalUnit());
            interval.setText("7");
            unit.setValue("分钟");
            assertEquals(7, details.command().intervalValue());
            assertEquals("minute", details.command().intervalUnit());

            ToggleButton once = (ToggleButton) node("#onceButton");
            once.fire();
            assertTrue(node("#onceFields").isVisible());
            once.fire();
            assertTrue(once.isSelected(), "再次点击当前触发器不得取消唯一选择");
            ((ToggleButton) node("#dailyButton")).fire();
            assertTrue(node("#dailyFields").isVisible());
            ((ToggleButton) node("#cronButton")).fire();
            assertTrue(node("#cronFields").isVisible());

            TextField cron = textField("#cronField");
            cron.setText("");
            cron.setText("bad cron");
            assertTrue(label("#cronHint").getText().contains("非法"));
            cron.setText("0 0 9 * * ?");
            assertTrue(label("#cronHint").getText().contains("有效"));

            ToggleSwitch enabled = (ToggleSwitch) node("#enabledToggle");
            enabled.setSelected(!enabled.isSelected());
            assertNotNull(toggled.get());
            ToggleSwitch notify = (ToggleSwitch) node("#notifyToggle");
            notify.setSelected(!notify.isSelected());
            assertTrue(changed.get());
            ((Button) node("#deleteButton")).fire();
            assertTrue(deleted.get());

            details.refreshClock();
            details.clear();
            details.refreshClock();
            assertThrows(NullPointerException.class, details::command);
            assertThrows(NullPointerException.class, () -> details.show(null, false));
            details.configure(null, null, null);
        });
    }

    @Test
    void viewControllerCoordinatesDraftSaveRunToggleDeleteAndClose() throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(ScheduleViewFactory.class).create(null));
        awaitFx(() -> taskList().getItems().size() == 1);
        AtomicBoolean closeRequested = new AtomicBoolean();
        runFx(() -> {
            view.controller().configure(() -> closeRequested.set(true));
            button("＋ 新建定时任务").fire();
        });
        awaitFx(() -> view.controller().details().task() != null);

        runFx(() -> {
            ToggleSwitch enabled = (ToggleSwitch) node("#enabledToggle");
            enabled.setSelected(!enabled.isSelected());
            assertTrue(label("#statusLabel").getText().contains("首次保存"));
            ((Button) view.root().lookup("#runButton")).fire();
        });
        awaitFx(() -> service.saveCalls == 1 && service.runCalls == 1);
        assertEquals("已加入执行队列…", callFx(() -> label("#statusLabel").getText()));

        runFx(() -> {
            ToggleSwitch enabled = (ToggleSwitch) node("#enabledToggle");
            enabled.setSelected(!enabled.isSelected());
        });
        awaitFx(() -> service.toggleCalls == 1);

        runFx(() -> ((Button) node("#deleteButton")).fire());
        awaitFx(() -> service.deleteCalls == 1);
        assertEquals(0, callFx(() -> taskList().getItems().size()));

        runFx(() -> {
            button("＋ 新建定时任务").fire();
            ((Button) node("#deleteButton")).fire();
        });
        assertTrue(callFx(() -> label("#statusLabel").getText().contains("草稿已丢弃")));

        runFx(() -> button("关闭").fire());
        assertTrue(closeRequested.get());
    }

    @Test
    void viewControllerHandlesRunDecisionsEventsAndFailures() throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(ScheduleViewFactory.class).create(null));
        awaitFx(() -> taskList().getItems().size() == 1);
        Task paused = task("paused", "interval", "minute", "",
                RuntimeState.PAUSED, false, false, true,
                "", "", null, "", List.of());
        runFx(() -> select(paused));

        interaction.confirmAllowed = false;
        runFx(() -> invoke(view.controller(), "runRequested"));
        awaitFx(() -> label("#statusLabel").getText().contains("已取消"));
        assertEquals(0, service.runCalls);

        interaction.confirmAllowed = true;
        runFx(() -> invoke(view.controller(), "runRequested"));
        awaitFx(() -> service.runCalls == 1);
        awaitFx(() -> label("#statusLabel").getText().equals("已加入执行队列…"));
        assertTrue(service.lastAllowDisabled);

        Task active = task("active", "interval", "minute", "",
                RuntimeState.RUNNING, true, false, true,
                "", "", null, "", List.of());
        runFx(() -> {
            select(active);
            invoke(view.controller(), "runRequested");
        });
        assertEquals("任务已在运行或排队", callFx(() -> label("#statusLabel").getText()));

        Task builtin = task("builtin", "interval", "minute", "",
                RuntimeState.BUILTIN, true, true, true,
                "", "", null, "scheduler", List.of());
        runFx(() -> {
            select(builtin);
            invoke(view.controller(), "saveRequested");
            invoke(view.controller(), "runRequested");
        });
        awaitFx(() -> service.runCalls == 2);

        service.emit(new Event(EventKind.LOG, "active", "运行日志"));
        awaitFx(() -> label("#statusLabel").getText().equals("运行日志"));
        int snapshotsBefore = service.snapshotCalls;
        service.emit(new Event(EventKind.COMPLETED, "active", "done"));
        awaitFx(() -> service.snapshotCalls > snapshotsBefore);

        service.runFailure = new IllegalStateException();
        Task enabled = task("enabled", "interval", "minute", "",
                RuntimeState.ENABLED, true, false, true,
                "", "", null, "", List.of());
        runFx(() -> {
            select(enabled);
            invoke(view.controller(), "runRequested");
        });
        awaitFx(() -> label("#statusLabel").getText().contains("IllegalStateException"));

        service.snapshotFailure = new IllegalArgumentException("snapshot offline");
        service.emit(new Event(EventKind.STARTED, "enabled", "start"));
        awaitFx(() -> label("#statusLabel").getText().contains("snapshot offline"));

        service.closeSubscriptionThrows = true;
        runFx(() -> {
            view.close();
            view.close();
        });
        view = null;
        assertTrue(service.subscriptionClosed);
    }

    @Test
    void unresolvedDraftSupportsCancelDiscardAndSaveChoices() throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(ScheduleViewFactory.class).create(null));
        awaitFx(() -> taskList().getItems().size() == 1);
        AtomicBoolean closed = new AtomicBoolean();
        runFx(() -> {
            view.controller().configure(() -> closed.set(true));
            button("＋ 新建定时任务").fire();
            textField("#nameField").setText(" ");
        });

        interaction.choice = "cancel";
        runFx(view.controller()::requestClose);
        awaitFx(() -> interaction.choiceCalls == 1);
        awaitFx(() -> !node("#loadingOverlay").isVisible());
        assertFalse(closed.get());

        interaction.choice = "discard";
        runFx(view.controller()::requestClose);
        awaitFx(closed::get);

        closed.set(false);
        runFx(() -> button("＋ 新建定时任务").fire());
        interaction.choice = "save";
        runFx(view.controller()::requestClose);
        awaitFx(() -> service.saveCalls == 1 && closed.get());
    }

    private Node node(String selector) {
        Node found = view.root().lookup(selector);
        if (found != null) return found;
        String fieldName = selector.startsWith("#") ? selector.substring(1) : selector;
        try {
            var field = ScheduleDetailController.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (Node) field.get(view.controller().details());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("找不到详情控件：" + selector, failure);
        }
    }

    private Label label(String selector) {
        return (Label) node(selector);
    }

    private TextField textField(String selector) {
        return (TextField) node(selector);
    }

    private Button button(String text) {
        return view.root().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(candidate -> text.equals(candidate.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到按钮：" + text));
    }

    private void select(Task task) {
        invoke(view.controller(), "selectionRequested", new Class<?>[] {Task.class}, task);
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

    @SuppressWarnings("unchecked")
    private ComboBox<String> combo(String selector) {
        return (ComboBox<String>) node(selector);
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        service = new FakeService();
        interaction = new AllowInteraction();
        context.registerBean(ScheduleApplicationService.class, () -> service);
        context.registerBean(UserInteractionPort.class, () -> interaction);
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

    private static Task task(
            String id,
            String trigger,
            String unit,
            String once,
            RuntimeState state,
            boolean enabled,
            boolean builtin,
            boolean manuallyRunnable,
            String lastRun,
            String lastStatus,
            LocalDateTime next,
            String source,
            List<History> history) {
        return new Task(id, id, "description", trigger,
                0, 0, unit, "", "0 0 9 * * ?", once, "prompt", enabled, 1,
                lastRun, lastStatus, "1s", 2, 1, false, "none", false,
                builtin, "", source, state, next, manuallyRunnable, history);
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
        private volatile String choice = "discard";
        private volatile int choiceCalls;

        @Override public boolean confirm(ConfirmRequest request) { return confirmAllowed; }
        @Override public String choose(ChoiceRequest request) {
            choiceCalls++;
            return choice;
        }
        @Override public void notify(ToastRequest request) { }
    }

    private static final class FakeService implements ScheduleApplicationService {
        private final Task task = new Task("daily", "每日简报", "", "daily",
                60, 60, "minute", "09:00", "", "", "生成简报", true, 1,
                "2026-08-10 09:00:00", "成功", "1.2s", 3, 0, false,
                "none", false, false, "", "", RuntimeState.ENABLED,
                java.time.LocalDateTime.now().plusHours(2), true,
                List.of(new History("2026-08-10 09:00:00", "成功", "1.2s", "完成")));
        private volatile boolean subscriptionClosed;
        private volatile boolean closeSubscriptionThrows;
        private volatile int snapshotCalls;
        private volatile int saveCalls;
        private volatile int toggleCalls;
        private volatile int deleteCalls;
        private volatile int runCalls;
        private volatile boolean lastAllowDisabled;
        private volatile RuntimeException snapshotFailure;
        private volatile RuntimeException runFailure;
        private volatile EventListener listener;

        @Override public Snapshot snapshot() {
            snapshotCalls++;
            if (snapshotFailure != null) throw snapshotFailure;
            return new Snapshot(List.of(task));
        }
        @Override public Task createDraft(String name) { return task; }
        @Override public OperationResult save(SaveCommand command) {
            saveCalls++;
            return new OperationResult(snapshot(), null, "已保存");
        }
        @Override public OperationResult setEnabled(
                SaveCommand command, boolean enabled, DisablePolicy disablePolicy) {
            toggleCalls++;
            return new OperationResult(snapshot(), null, enabled ? "已启用" : "已暂停");
        }
        @Override public OperationResult delete(String taskId) {
            deleteCalls++;
            return new OperationResult(new Snapshot(List.of()), null, "任务已删除");
        }
        @Override public OperationResult runNow(String taskId, boolean allowDisabled) {
            runCalls++;
            lastAllowDisabled = allowDisabled;
            if (runFailure != null) throw runFailure;
            return new OperationResult(snapshot(), RunResult.STARTED, "已加入执行队列…");
        }
        @Override public AutoCloseable observe(EventListener listener) {
            this.listener = listener;
            return () -> {
                subscriptionClosed = true;
                if (closeSubscriptionThrows) throw new IllegalStateException("close failure");
            };
        }

        private void emit(Event event) {
            EventListener current = listener;
            if (current != null) current.onEvent(event);
        }
    }
}
