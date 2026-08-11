package com.javaclaw.ui.javafx.task;

import com.javaclaw.application.task.SddTaskApplicationService;
import com.javaclaw.application.task.SddTaskApplicationService.CreateCommand;
import com.javaclaw.application.task.SddTaskApplicationService.Event;
import com.javaclaw.application.task.SddTaskApplicationService.EventListener;
import com.javaclaw.application.task.SddTaskApplicationService.Snapshot;
import com.javaclaw.application.task.SddTaskApplicationService.Task;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.task.sdd.run.SddTaskState;
import com.javaclaw.task.sdd.spec.Capability;
import com.javaclaw.task.sdd.spec.Criterion;
import com.javaclaw.task.sdd.spec.OpenSpecChange;
import com.javaclaw.task.sdd.spec.Proposal;
import com.javaclaw.task.sdd.spec.Requirement;
import com.javaclaw.task.sdd.spec.Scenario;
import com.javaclaw.task.sdd.spec.TaskItem;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
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

    @Test
    void detailControllerProjectsEveryStageStateResultAndLogKind() throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(SddTaskViewFactory.class).create(null));
        SddTaskDetailController details = field(view.controller(),
                "detailPanelController", SddTaskDetailController.class);

        runFx(() -> {
            details.show(task("pending", SddTaskState.PENDING, 0, null,
                    0, 100, 200, "bad", "bad"), null);
            assertEquals("提案", label(details, "stageValue").getText());
            assertEquals("预算不限", label(details, "tokenHint").getText());

            OpenSpecChange proposalOnly = change(List.of(), null, List.of(), null,
                    "- 新增导出\n2. 保留兼容\n•   ");
            details.show(task("proposal", SddTaskState.PENDING, 10, "",
                    1_000, 500, 100, "2026-08-12 09:00:00", "2026-08-12 09:05:00"),
                    proposalOnly);
            assertEquals("规格", label(details, "stageValue").getText());

            Capability capability = capability();
            OpenSpecChange specification = change(List.of(capability), null, List.of(), "",
                    "- 新增 CSV");
            details.show(task("spec", SddTaskState.RUNNING, 25, "处理中",
                    600, 1_000, 20, "2026-08-12 09:00:00", "2026-08-12 10:00:00"),
                    specification);
            assertEquals("设计", label(details, "stageValue").getText());
            assertTrue(label(details, "resultTitle").getText().contains("人工"));

            OpenSpecChange designed = change(List.of(capability), "design", List.of(), "later",
                    "1) 设计存储\n* 编写测试");
            details.show(task("design", SddTaskState.PAUSED, 40, null,
                    10_000, 2_000, 500, "2026-08-12 09:00:00", "2026-08-12 11:15:00"),
                    designed);
            assertEquals("拆解", label(details, "stageValue").getText());
            assertTrue(field(details, "outOfScopeCard", VBox.class).isVisible());

            List<TaskItem> mixed = List.of(
                    new TaskItem(1, "done", List.of("a.java"), "ok", true),
                    new TaskItem(2, "todo", List.of(), "pending", false));
            OpenSpecChange implementing = change(List.of(capability), "design", mixed, null,
                    "");
            details.show(task("implement", SddTaskState.NEEDS_HUMAN, 50, "需要确认",
                    1_000, 950, 0, "2026-08-12 09:00:00", "2026-08-12 09:30:00"),
                    implementing);
            assertEquals("实现", label(details, "stageValue").getText());
            assertEquals("1/2", label(details, "checklistCounter").getText());

            List<TaskItem> done = List.of(new TaskItem(1, "done", List.of(), "ok", true));
            OpenSpecChange completedChange = change(List.of(capability), "design", done, "",
                    "- done");
            details.show(task("completed", SddTaskState.COMPLETED, 100, "交付完成",
                    2_000_000, 1_100_000, 200_000,
                    "2026-08-10 09:00:00", "2026-08-12 11:00:00"), completedChange);
            assertEquals("已完成", label(details, "stageValue").getText());
            assertEquals("任务结果", label(details, "resultTitle").getText());
            assertTrue(label(details, "elapsedValue").getText().contains("d"));

            details.show(task("failed", SddTaskState.FAILED, 80, "boom",
                    1_000, 900, 100, "2026-08-12 10:00:00", "2026-08-12 09:00:00"),
                    implementing);
            assertEquals("任务失败", label(details, "resultTitle").getText());
            assertEquals("—", label(details, "elapsedValue").getText());

            details.show(task("cancelled", SddTaskState.CANCELLED, 20, null,
                    1_000, 100, 100, "2026-08-12 09:00:00", "2026-08-12 09:00:30"),
                    null);
            assertEquals("0m 30s", label(details, "elapsedValue").getText());

            button(details, "acceptanceTab").fire();
            button(details, "checklistTab").fire();
            button(details, "logTab").fire();
            button(details, "overviewTab").fire();

            details.appendLog("[10:00] ✓ 完成");
            details.appendLog("[10:01] ⚠ 警告");
            details.appendLog("[10:02] ⚙ 执行");
            details.appendLog("普通日志");
            details.appendLog(null);
            for (int index = 0; index < 402; index++) details.appendLog("line-" + index);
            @SuppressWarnings("unchecked")
            ListView<SddLogEntry> logs = field(details, "logList", ListView.class);
            assertEquals(400, logs.getItems().size());
            details.clearLogs();
            assertTrue(logs.getItems().isEmpty());
            details.clear();
        });
    }

    @Test
    void createControllerValidatesBudgetsCapabilitiesAndCallbacks() throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(SddTaskViewFactory.class).create(null));
        SddTaskCreateController create = field(view.controller(),
                "createPanelController", SddTaskCreateController.class);
        AtomicReference<SddTaskCreateController.Draft> submitted = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();

        runFx(() -> {
            create.configure(submitted::set, () -> cancelled.set(true));
            create.prepare(null);
            button(create, "submitButton").fire();
            assertTrue(label(create, "errorLabel").isVisible());

            textArea(create, "descriptionField").setText(" 构建 CSV 导出 ");
            combo(create, "budgetBox").setValue("自定义…");
            textField(create, "customBudgetField").setText("bad");
            button(create, "submitButton").fire();
            assertNull(submitted.get());
            assertTrue(label(create, "errorLabel").getText().contains("非负整数"));

            textField(create, "customBudgetField").setText("-5");
            combo(create, "capabilityBox").setValue("全量加载");
            button(create, "submitButton").fire();
            assertEquals(0, submitted.get().tokenBudget());
            assertEquals("all", submitted.get().capabilities());

            submitted.set(null);
            combo(create, "budgetBox").setValue("unknown");
            combo(create, "capabilityBox").setValue("自定义…");
            textField(create, "customCapabilityField").setText(" ");
            button(create, "submitButton").fire();
            assertEquals(120_000, submitted.get().tokenBudget());
            assertEquals("auto", submitted.get().capabilities());

            submitted.set(null);
            textField(create, "customCapabilityField").setText("system,command");
            button(create, "submitButton").fire();
            assertEquals("system,command", submitted.get().capabilities());

            create.prepare("prefilled");
            create.setBusy(true);
            assertTrue(button(create, "submitButton").isDisabled());
            create.setBusy(false);
            create.configure(null, null);
            button(field(create, "root", VBox.class), "取消").fire();
            create.configure(submitted::set, () -> cancelled.set(true));
            button(field(create, "root", VBox.class), "取消").fire();
            assertTrue(cancelled.get());
        });
    }

    @Test
    void budgetDialogAcceptsNonNegativeValuesAndReportsInvalidInput() throws Exception {
        prepareContext();
        URL resource = SddTaskFxmlLoadTest.class.getResource("/fxml/task/sdd-budget-dialog.fxml");
        assertNotNull(resource);
        runFx(() -> {
            try (ViewHandle<VBox> handle = context.getBean(SpringFxmlLoader.class).load(resource)) {
                SddBudgetDialogController budget =
                        handle.controller(SddBudgetDialogController.class);
                AtomicBoolean closed = new AtomicBoolean();
                budget.configure(service.task, () -> closed.set(true));
                TextField value = textField(budget, "budgetField");
                value.setText("bad");
                button(handle.root(), "保存").fire();
                assertTrue(label(budget, "errorLabel").isVisible());
                assertEquals(OptionalLong.empty(), budget.result());

                value.setText("-9");
                button(handle.root(), "保存").fire();
                assertEquals(OptionalLong.of(0), budget.result());
                assertTrue(closed.get());

                closed.set(false);
                budget.configure(service.task, null);
                button(handle.root(), "取消").fire();
                assertFalse(closed.get());
                budget.configure(service.task, () -> closed.set(true));
                button(handle.root(), "取消").fire();
                assertTrue(closed.get());
            } catch (java.io.IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
    }

    @Test
    void mainControllerCoordinatesEveryOperationAndCreateOutcome() throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(SddTaskViewFactory.class).create(null));
        runFx(view.controller()::prepare);
        awaitFx(() -> taskList().getItems().size() == 1);
        SddTaskController controller = view.controller();
        AtomicBoolean closeRequested = new AtomicBoolean();

        runFx(() -> {
            controller.configure(() -> closeRequested.set(true));
            controller.showTask("task-1");
        });
        awaitFx(() -> service.requireCalls > 0 && !loading());
        runFx(() -> {
            invoke(controller, "select", new Class<?>[] {Task.class}, new Object[] {null});
            for (SddTaskState state : SddTaskState.values()) {
                Task stateTask = task("state-" + state, state, 20, null,
                        1_000, 100, 50, "2026-08-12 09:00:00", "2026-08-12 09:01:00");
                invoke(controller, "configureActions", new Class<?>[] {Task.class}, stateTask);
                assertTrue(actionButtons().stream().anyMatch(Button::isVisible));
            }
        });

        operate(controller, "showDetail", "startRequested", SddTaskState.PENDING,
                () -> service.startCalls == 1);
        operate(controller, "showDetail", "pauseRequested", SddTaskState.RUNNING,
                () -> service.pauseCalls == 1);
        operate(controller, "showDetail", "cancelRequested", SddTaskState.RUNNING,
                () -> service.cancelCalls == 1);
        operate(controller, "showDetail", "resumeRequested", SddTaskState.PAUSED,
                () -> service.resumeCalls == 1);
        operate(controller, "showDetail", "rerunRequested", SddTaskState.COMPLETED,
                () -> service.resumeCalls == 2);
        operate(controller, "showDetail", "deleteRequested", SddTaskState.FAILED,
                () -> service.deleteCalls == 1);

        int starts = service.startCalls;
        runFx(() -> invoke(controller, "startRequested"));
        assertEquals(starts, service.startCalls, "没有选择时不得执行任务操作");
        runFx(() -> invoke(controller, "budgetRequested"));

        SddTaskCreateController.Draft generated = new SddTaskCreateController.Draft(
                "", "Generate report", "", 120_000, "auto", "none");
        runFx(() -> invoke(controller, "create",
                new Class<?>[] {SddTaskCreateController.Draft.class}, generated));
        awaitFx(() -> service.createCalls == 1 && !loading());
        assertEquals(1, service.generateTitleCalls);
        assertEquals("Generated title", service.lastCreate.title());

        SddTaskCreateController.Draft explicit = new SddTaskCreateController.Draft(
                "Explicit", "Generate report", "/tmp", 0, "all", "email");
        runFx(() -> invoke(controller, "create",
                new Class<?>[] {SddTaskCreateController.Draft.class}, explicit));
        awaitFx(() -> service.createCalls == 2 && !loading());
        assertEquals(1, service.generateTitleCalls);
        assertEquals("Explicit", service.lastCreate.title());

        service.createFailure = new IllegalStateException();
        runFx(() -> invoke(controller, "create",
                new Class<?>[] {SddTaskCreateController.Draft.class}, explicit));
        awaitFx(() -> status().contains("IllegalStateException"));
        service.createFailure = null;

        runFx(() -> {
            field(controller, "viewModel", SddTaskViewModel.class).clearSelection();
            invoke(controller, "cancelCreate");
            invoke(controller, "showDetail", new Class<?>[] {Task.class}, service.task);
            controller.openCreate("prefilled");
            invoke(controller, "cancelCreate");
            invoke(controller, "closeRequested");
        });
        assertTrue(closeRequested.get());
        runFx(() -> controller.configure(null));
    }

    @Test
    void mainControllerMergesRuntimeEventsAndMapsFailures() throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(SddTaskViewFactory.class).create(null));
        runFx(view.controller()::prepare);
        awaitFx(() -> taskList().getItems().size() == 1);
        SddTaskController controller = view.controller();
        SddTaskDetailController details = field(controller,
                "detailPanelController", SddTaskDetailController.class);
        runFx(() -> controller.showTask("task-1"));
        awaitFx(() -> service.requireCalls > 0 && !loading());

        @SuppressWarnings("unchecked")
        ListView<SddLogEntry> logs = field(details, "logList", ListView.class);
        service.emit(new Event.Log("other", "Other", "ignored"));
        service.emit(new Event.Log("task-1", "Selected", "[10:00] ✓ selected"));
        awaitFx(() -> logs.getItems().size() == 1);

        Task other = task("other", SddTaskState.PENDING, 0, null,
                0, 0, 0, "bad", "bad");
        service.emit(new Event.Changed(other));
        awaitFx(() -> taskList().getItems().size() == 2);

        int requires = service.requireCalls;
        Task sameProgress = task("task-1", SddTaskState.RUNNING, service.task.progress(), null,
                120_000, 1_000, 400, "2026-08-12 09:00:00", "2026-08-12 09:01:00");
        service.emit(new Event.Changed(sameProgress));
        awaitFx(() -> taskList().getItems().size() == 2);
        assertEquals(requires, service.requireCalls);

        Task changedProgress = task("task-1", SddTaskState.RUNNING, 77, null,
                120_000, 1_000, 400, "2026-08-12 09:00:00", "2026-08-12 09:01:00");
        service.requiredTask = changedProgress;
        service.emit(new Event.Changed(changedProgress));
        awaitFx(() -> service.requireCalls > requires);

        service.pauseFailure = new IllegalArgumentException("pause denied");
        runFx(() -> {
            invoke(controller, "showDetail", new Class<?>[] {Task.class}, service.task);
            invoke(controller, "pauseRequested");
        });
        awaitFx(() -> status().contains("pause denied"));
        service.pauseFailure = null;

        service.requireFailure = new IllegalStateException();
        runFx(() -> controller.showTask("task-1"));
        awaitFx(() -> status().contains("IllegalStateException"));
        service.requireFailure = null;

        service.snapshotFailure = new IllegalStateException("snapshot unavailable");
        runFx(controller::prepare);
        awaitFx(() -> status().contains("snapshot unavailable"));
        service.snapshotFailure = null;

        service.closeSubscriptionThrows = true;
        runFx(() -> {
            view.close();
            service.emit(new Event.Changed(service.task));
            view.close();
        });
        view = null;
        assertTrue(service.subscriptionClosed);
    }

    private void operate(
            SddTaskController controller,
            String selectionMethod,
            String operationMethod,
            SddTaskState state,
            BooleanSupplier completed) throws Exception {
        Task selected = task("operation", state, 10, null,
                1_000, 100, 20, "2026-08-12 09:00:00", "2026-08-12 09:01:00");
        runFx(() -> {
            invoke(controller, selectionMethod, new Class<?>[] {Task.class}, selected);
            invoke(controller, operationMethod);
        });
        awaitFx(() -> completed.getAsBoolean() && !loading());
    }

    private boolean loading() {
        return field(view.controller(), "loadingOverlay", javafx.scene.layout.StackPane.class)
                .isVisible();
    }

    private String status() {
        return field(view.controller(), "statusLabel", Label.class).getText();
    }

    private List<Button> actionButtons() {
        SddTaskController controller = view.controller();
        return List.of(
                field(controller, "startButton", Button.class),
                field(controller, "pauseButton", Button.class),
                field(controller, "cancelButton", Button.class),
                field(controller, "resumeButton", Button.class),
                field(controller, "budgetButton", Button.class),
                field(controller, "resumeBudgetButton", Button.class),
                field(controller, "rerunButton", Button.class),
                field(controller, "deleteButton", Button.class));
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

    private static OpenSpecChange change(
            List<Capability> capabilities,
            String design,
            List<TaskItem> tasks,
            String outOfScope,
            String changes) {
        return new OpenSpecChange("change", "csv-export", "CSV export",
                new Proposal(" ", changes, outOfScope), capabilities, design, tasks);
    }

    private static Capability capability() {
        Scenario scenario = new Scenario("download", "data", "export", "csv",
                Criterion.freeform("file exists"));
        return new Capability("export", List.of(new Requirement("Export", List.of(scenario))));
    }

    private static Task task(
            String id,
            SddTaskState state,
            int progress,
            String result,
            long budget,
            long input,
            long output,
            String createdAt,
            String updatedAt) {
        return new Task(id, id, "", "", "auto", budget, "none",
                createdAt, updatedAt, state, progress, result, input, output,
                Map.of("phase", input), Map.of("phase", output));
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

    private static Label label(Object target, String name) {
        return field(target, name, Label.class);
    }

    private static Button button(Object target, String name) {
        return field(target, name, Button.class);
    }

    private static Button button(Node root, String text) {
        Button found = findButton(root, text);
        if (found == null) throw new AssertionError("找不到按钮：" + text);
        return found;
    }

    private static Button findButton(Node node, String text) {
        if (node instanceof Button candidate && text.equals(candidate.getText())) return candidate;
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            Button found = findButton(scroll.getContent(), text);
            if (found != null) return found;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Button found = findButton(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextField textField(Object target, String name) {
        return field(target, name, TextField.class);
    }

    private static TextArea textArea(Object target, String name) {
        return field(target, name, TextArea.class);
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<String> combo(Object target, String name) {
        return (ComboBox<String>) field(target, name, ComboBox.class);
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
        private volatile Task requiredTask = task;
        private volatile boolean subscriptionClosed;
        private volatile boolean closeSubscriptionThrows;
        private volatile int snapshotCalls;
        private volatile int requireCalls;
        private volatile int generateTitleCalls;
        private volatile int createCalls;
        private volatile int startCalls;
        private volatile int resumeCalls;
        private volatile int pauseCalls;
        private volatile int cancelCalls;
        private volatile int deleteCalls;
        private volatile CreateCommand lastCreate;
        private volatile RuntimeException snapshotFailure;
        private volatile RuntimeException requireFailure;
        private volatile RuntimeException createFailure;
        private volatile RuntimeException pauseFailure;
        private volatile EventListener listener;

        @Override public Snapshot snapshot() {
            snapshotCalls++;
            if (snapshotFailure != null) throw snapshotFailure;
            return new Snapshot(List.of(task));
        }
        @Override public Task require(String taskId) {
            requireCalls++;
            if (requireFailure != null) throw requireFailure;
            return requiredTask;
        }
        @Override public String generateTitle(String description) {
            generateTitleCalls++;
            return "Generated title";
        }
        @Override public Task create(CreateCommand command) {
            createCalls++;
            lastCreate = command;
            if (createFailure != null) throw createFailure;
            return task;
        }
        @Override public void start(String taskId, String completionStamp) { startCalls++; }
        @Override public void resume(String taskId, String completionStamp) { resumeCalls++; }
        @Override public void pause(String taskId) {
            pauseCalls++;
            if (pauseFailure != null) throw pauseFailure;
        }
        @Override public void cancel(String taskId) { cancelCalls++; }
        @Override public void delete(String taskId) { deleteCalls++; }
        @Override public Task updateTokenBudget(String taskId, long newBudget) { return task; }
        @Override public Optional<OpenSpecChange> specification(String taskId) {
            return Optional.empty();
        }
        @Override public AutoCloseable observe(EventListener listener) {
            this.listener = listener;
            return () -> {
                subscriptionClosed = true;
                if (closeSubscriptionThrows) throw new IllegalStateException("close failure");
            };
        }
        @Override public void suspendForRuntimeTransition() { }

        private void emit(Event event) {
            EventListener current = listener;
            if (current != null) current.onEvent(event);
        }
    }
}
