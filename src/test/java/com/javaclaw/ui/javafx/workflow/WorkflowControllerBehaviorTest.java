package com.javaclaw.ui.javafx.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.workflow.WorkflowApplicationService;
import com.javaclaw.application.workflow.WorkflowApplicationService.RunObserver;
import com.javaclaw.application.workflow.WorkflowApplicationService.RunTerminal;
import com.javaclaw.application.workflow.WorkflowApplicationService.RunTerminalStatus;
import com.javaclaw.application.workflow.WorkflowApplicationService.WorkflowItem;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.workflow.editor.WorkflowEditorModel;
import com.javaclaw.workflow.model.ConditionOperator;
import com.javaclaw.workflow.model.EdgeDefinition;
import com.javaclaw.workflow.model.EdgeKind;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.model.NodeDefinition;
import com.javaclaw.workflow.model.NodeType;
import com.javaclaw.workflow.model.RunStatus;
import com.javaclaw.workflow.runtime.GraphRun;
import com.javaclaw.workflow.runtime.ValidationIssue;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class WorkflowControllerBehaviorTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private WorkflowView view;
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
    void viewControllerCoordinatesEditingPersistenceValidationAndFailures() throws Exception {
        WorkflowViewController controller = openView();
        WorkflowViewModel model = model(controller);
        AtomicInteger published = new AtomicInteger();
        AtomicInteger closeRequests = new AtomicInteger();
        AtomicBoolean rejectPublishedCallback = new AtomicBoolean();
        runFx(() -> controller.configure(item -> {
            published.incrementAndGet();
            if (rejectPublishedCallback.get()) throw new IllegalStateException("refresh failed");
        }, closeRequests::incrementAndGet));

        int createBefore = service.createCalls;
        runFx(() -> invoke(controller, "createRequested"));
        awaitFx(() -> service.createCalls > createBefore && !loading(controller));

        int cloneBefore = service.cloneCalls;
        runFx(() -> invoke(controller, "cloneRequested"));
        awaitFx(() -> service.cloneCalls > cloneBefore && !loading(controller));

        runFx(() -> {
            for (String method : List.of("addAgent", "addTool", "addCondition",
                    "addTransform", "addHumanInput", "addOutput")) {
                invoke(controller, method);
            }
            invoke(controller, "undoRequested");
            invoke(controller, "redoRequested");
            field(controller, "autosave", PauseTransition.class).stop();
        });
        assertEquals(9, callFx(() -> model.currentGraph().nodes().size()));
        assertTrue(callFx(() -> model.dirtyProperty().get()));

        int emptyValidationBefore = service.validateCalls;
        runFx(() -> invoke(controller, "validateRequested"));
        awaitFx(() -> service.validateCalls > emptyValidationBefore
                && model.consoleProperty().get().contains("校验通过"));
        service.validation = List.of(new ValidationIssue(
                ValidationIssue.Severity.WARNING, "node-a", "需要确认"));
        int issueValidationBefore = service.validateCalls;
        runFx(() -> invoke(controller, "validateRequested"));
        awaitFx(() -> service.validateCalls > issueValidationBefore
                && model.consoleProperty().get().contains("WARNING [node-a] 需要确认"));

        int publishBefore = service.publishCalls;
        runFx(() -> invoke(controller, "publishRequested"));
        awaitFx(() -> service.publishCalls > publishBefore && published.get() == 1
                && model.consoleProperty().get().contains("发布成功"));

        rejectPublishedCallback.set(true);
        int callbackPublishBefore = service.publishCalls;
        runFx(() -> invoke(controller, "publishRequested"));
        awaitFx(() -> service.publishCalls > callbackPublishBefore
                && model.consoleProperty().get().contains("模式列表刷新失败"));

        service.publishFailure = new IllegalStateException();
        int failedPublishBefore = service.publishCalls;
        runFx(() -> invoke(controller, "publishRequested"));
        awaitFx(() -> service.publishCalls > failedPublishBefore
                && model.consoleProperty().get().contains("发布失败：IllegalStateException"));
        service.publishFailure = null;

        service.validateFailure = new IllegalStateException("validator offline");
        int failedValidationBefore = service.validateCalls;
        runFx(() -> invoke(controller, "validateRequested"));
        awaitFx(() -> service.validateCalls > failedValidationBefore
                && model.consoleProperty().get().contains("校验失败：validator offline"));
        service.validateFailure = null;

        service.snapshotFailure = new IllegalStateException("snapshot offline");
        int snapshots = service.snapshotCalls;
        runFx(() -> invoke(controller, "requestSnapshot", new Class<?>[] {String.class},
                (Object) null));
        awaitFx(() -> service.snapshotCalls > snapshots
                && model.consoleProperty().get().contains("加载工作流失败：snapshot offline"));
        service.snapshotFailure = null;

        service.createFailure = new IllegalStateException("create offline");
        int failedCreateBefore = service.createCalls;
        runFx(() -> invoke(controller, "createRequested"));
        awaitFx(() -> service.createCalls > failedCreateBefore
                && model.consoleProperty().get().contains("新建工作流失败：create offline"));
        service.createFailure = null;

        WorkflowItem current = callFx(() -> model.selectedWorkflowProperty().get());
        WorkflowItem system = service.systemItem();
        runFx(() -> invoke(controller, "selectionRequested",
                new Class<?>[] {WorkflowItem.class}, current));
        assertEquals(current.id(), callFx(() -> model.selectedWorkflowProperty().get().id()));

        runFx(() -> {
            model.changed();
            field(controller, "autosave", PauseTransition.class).stop();
        });
        int saves = service.saveCalls;
        runFx(() -> invoke(controller, "selectionRequested",
                new Class<?>[] {WorkflowItem.class}, system));
        awaitFx(() -> service.saveCalls > saves
                && system.id().equals(model.selectedWorkflowProperty().get().id()));
        assertTrue(callFx(() -> model.readOnlyProperty().get()));

        AtomicBoolean readOnlyContinuation = new AtomicBoolean();
        runFx(() -> invoke(controller, "saveDraft", new Class<?>[] {Runnable.class},
                (Runnable) () -> readOnlyContinuation.set(true)));
        assertTrue(readOnlyContinuation.get());

        runFx(() -> model.select(service.draftItem()));
        runFx(() -> {
            model.changed();
            field(controller, "autosave", PauseTransition.class).stop();
        });
        service.saveFailure = new IllegalStateException("disk full");
        CompletableFuture<Boolean> rejectedClose = callFx(controller::prepareClose);
        assertFalse(rejectedClose.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        awaitFx(() -> "保存失败".equals(model.saveStateProperty().get()));
        service.saveFailure = null;

        runFx(() -> {
            model.changed();
            field(controller, "autosave", PauseTransition.class).stop();
        });
        CompletableFuture<Boolean> acceptedClose = callFx(controller::prepareClose);
        assertTrue(acceptedClose.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(callFx(() -> controller.prepareClose().join()));

        runFx(() -> {
            invoke(controller, "applySnapshot",
                    new Class<?>[] {WorkflowApplicationService.Snapshot.class, String.class},
                    new WorkflowApplicationService.Snapshot(List.of()), null);
            invoke(controller, "cloneRequested");
            invoke(controller, "validateRequested");
            invoke(controller, "publishRequested");
            invoke(controller, "testRequested");
            invoke(controller, "closeRequested");
        });
        assertEquals(1, closeRequests.get());
        assertFalse(callFx(model::hasSelection));

        runFx(() -> controller.configure(null, null));
        runFx(() -> invoke(controller, "closeRequested"));
    }

    @Test
    void canvasAndInspectorCoverConnectionEditingAndReadOnlyRules() throws Exception {
        WorkflowViewController controller = openView();
        WorkflowViewModel model = model(controller);
        WorkflowCanvasController canvas = field(controller, "canvasController",
                WorkflowCanvasController.class);
        WorkflowInspectorController inspector = field(controller, "inspectorController",
                WorkflowInspectorController.class);

        runFx(() -> {
            model.selectNode(null);
            invoke(inspector, "applyRequested");
            invoke(inspector, "deleteRequested");
            invoke(inspector, "connectRequested");
            invoke(inspector, "errorConnectionRequested");
            canvas.beginConnection(null, EdgeKind.NORMAL);
            canvas.cancelConnection();
        });

        NodeDefinition initialStart = node(model, NodeType.START);
        NodeDefinition output = node(model, NodeType.OUTPUT);
        NodeDefinition initialEnd = node(model, NodeType.END);
        runFx(() -> {
            model.readOnlyProperty().set(true);
            model.selectNode(output);
            invoke(inspector, "applyRequested");
            invoke(inspector, "deleteRequested");
            canvas.beginConnection(output, EdgeKind.NORMAL);
            model.readOnlyProperty().set(false);
            canvas.beginConnection(initialEnd, EdgeKind.NORMAL);
        });
        assertTrue(callFx(() -> model.consoleProperty().get().contains("系统图为只读")));
        assertTrue(callFx(() -> model.consoleProperty().get().contains("END 节点不能创建出口")));

        runFx(() -> {
            model.selectNode(output);
            textField(inspector, "nodeLabel").setText(" ");
            textArea(inspector, "configEditor").setText("{\"template\":\"ok\"}");
            combo(inspector, "resumeSafety").setValue(null);
            invoke(inspector, "applyRequested");
            field(controller, "autosave", PauseTransition.class).stop();
        });
        assertEquals("输出", callFx(() -> model.selectedNodeProperty().get().label()));

        runFx(() -> {
            textArea(inspector, "configEditor").setText("{");
            invoke(inspector, "applyRequested");
        });
        assertTrue(callFx(() -> model.consoleProperty().get().contains("配置 JSON 无效")));

        NodeDefinition stale = new NodeDefinition("missing", output.type(), output.executorType(),
                output.label(), output.config(), output.x(), output.y(), output.retryPolicy(),
                output.resumeSafety());
        runFx(() -> {
            model.selectNode(stale);
            invoke(inspector, "applyRequested");
            model.selectNode(initialEnd);
            invoke(inspector, "deleteRequested");
            model.selectNode(initialStart);
            invoke(inspector, "deleteRequested");
        });
        assertTrue(callFx(() -> model.consoleProperty().get().contains("节点已不存在")));
        assertTrue(callFx(() -> model.consoleProperty().get().contains("END 节点是工作流必需出口")));
        assertTrue(callFx(() -> model.consoleProperty().get().contains("不能删除 START 节点")));

        selectGraph(model, graphWith(NodeType.SYSTEM));
        NodeDefinition systemNode = node(model, NodeType.SYSTEM);
        runFx(() -> {
            model.selectNode(systemNode);
            textField(inspector, "nodeLabel").setText("系统阶段 2");
            textArea(inspector, "configEditor").setText("not-json-is-ignored");
            invoke(inspector, "applyRequested");
            field(controller, "autosave", PauseTransition.class).stop();
        });
        assertEquals("系统阶段 2", callFx(() -> model.selectedNodeProperty().get().label()));

        selectGraph(model, graphWith(NodeType.CONDITION));
        NodeDefinition condition = node(model, NodeType.CONDITION);
        NodeDefinition target = node(model, NodeType.END);
        NodeDefinition source = node(model, NodeType.START);
        runFx(() -> {
            canvas.beginConnection(source, null);
            invoke(canvas, "finishConnection", new Class<?>[] {NodeDefinition.class}, source);
            invoke(canvas, "finishConnection", new Class<?>[] {NodeDefinition.class}, target);
            canvas.beginConnection(source, EdgeKind.ERROR);
            invoke(canvas, "finishConnection", new Class<?>[] {NodeDefinition.class}, target);
            invoke(canvas, "finishConnection", new Class<?>[] {NodeDefinition.class},
                    (Object) null);
            field(controller, "autosave", PauseTransition.class).stop();
        });
        assertTrue(callFx(() -> model.consoleProperty().get().contains("不能连接节点自身")));

        connectConditional(model, canvas, false, "state.score", ConditionOperator.EXISTS, "", 1);
        connectConditional(model, canvas, false, "state.score", ConditionOperator.EQUAL,
                "not-json", 2);
        connectConditional(model, canvas, false, "state.score", ConditionOperator.EQUAL,
                "{\"ok\":true}", 3);
        connectConditional(model, canvas, true, "", ConditionOperator.EQUAL, "", 4);
        selectGraph(model, graphWith(NodeType.CONDITION));
        condition = node(model, NodeType.CONDITION);
        NodeDefinition conditionalTarget = node(model, NodeType.END);
        NodeDefinition conditionalSource = condition;
        AssertionError invalidCondition = assertThrows(AssertionError.class,
                () -> runFx(() -> invoke(canvas,
                "connectConditional",
                new Class<?>[] {String.class, String.class,
                        WorkflowConditionDialogController.Selection.class},
                conditionalSource.id(), conditionalTarget.id(),
                new WorkflowConditionDialogController.Selection(
                        " ", ConditionOperator.EQUAL, "1", 0, false))));
        assertTrue(invalidCondition.getCause() instanceof IllegalArgumentException);

        runFx(() -> {
            setField(canvas, "contextNode", null);
            invoke(canvas, "deleteNodeFromMenu");
            setField(canvas, "contextEdge", null);
            invoke(canvas, "deleteEdgeFromMenu");
            model.readOnlyProperty().set(true);
            setField(canvas, "contextNode", conditionalSource);
            invoke(canvas, "deleteNodeFromMenu");
            setField(canvas, "contextEdge", model.currentGraph().edges().getFirst());
            invoke(canvas, "deleteEdgeFromMenu");
            model.readOnlyProperty().set(false);
        });

        selectGraph(model, graphWith(NodeType.CONDITION));
        NodeDefinition removable = node(model, NodeType.CONDITION);
        EdgeDefinition removableEdge = model.currentGraph().edges().getFirst();
        runFx(() -> {
            setField(canvas, "contextEdge", removableEdge);
            invoke(canvas, "deleteEdgeFromMenu");
            setField(canvas, "contextNode", removable);
            invoke(canvas, "deleteNodeFromMenu");
            field(controller, "autosave", PauseTransition.class).stop();
        });

        runFx(() -> {
            invoke(canvas, "zoomIn");
            invoke(canvas, "zoomOut");
            invoke(canvas, "fitGraph");
            invoke(canvas, "render", new Class<?>[] {GraphDefinition.class}, (Object) null);
            invoke(canvas, "render", new Class<?>[] {GraphDefinition.class}, model.currentGraph());
            invoke(canvas, "setZoom", new Class<?>[] {double.class}, 10.0);
            assertEquals(2.0, model.zoomProperty().get(), 0.0001);
            invoke(canvas, "setZoom", new Class<?>[] {double.class}, -10.0);
        });
        assertEquals(0.1, callFx(() -> model.zoomProperty().get()), 0.0001);
        assertEquals(0.5, normalizedCenter(10, 20, 10), 0.0001);
        assertEquals(0.0, normalizedCenter(0, 10, 100), 0.0001);
        assertEquals(1.0, normalizedCenter(100, 10, 100), 0.0001);

        try (ViewHandle<VBox> standalone = callFx(() -> context.getBean(SpringFxmlLoader.class)
                .load(getClass().getResource("/fxml/workflow/workflow-inspector.fxml")))) {
            runFx(() -> standalone.controller(WorkflowInspectorController.class).close());
        }
    }

    @Test
    void runtimeControllerHandlesHistoryResumeCancelAndTerminalEvents() throws Exception {
        WorkflowViewController controller = openView();
        WorkflowViewModel model = model(controller);
        WorkflowRuntimeController runtime = field(controller, "runtimeController",
                WorkflowRuntimeController.class);
        TextArea console = field(runtime, "console", TextArea.class);
        @SuppressWarnings("unchecked")
        ListView<GraphRun> history = field(runtime, "runHistory", ListView.class);
        TextField input = field(runtime, "resumeInput", TextField.class);

        runFx(() -> {
            invoke(runtime, "showHistory");
            invoke(runtime, "showTrace");
            model.select(null);
            runtime.refresh();
            invoke(runtime, "resumeRequested");
            invoke(runtime, "cancelRequested");
        });
        assertTrue(callFx(() -> console.getText().contains("请先选择运行记录")));

        runFx(() -> model.select(service.draftItem()));
        service.runs = List.of(
                run(RunStatus.COMPLETED), run(RunStatus.WAITING_INPUT), run(RunStatus.PAUSED));
        int historyLoadBefore = service.runCalls;
        runFx(runtime::refresh);
        awaitFx(() -> service.runCalls > historyLoadBefore && history.getItems().size() == 3);

        runFx(() -> {
            history.getSelectionModel().select(0);
            invoke(runtime, "resumeRequested");
            history.getSelectionModel().select(1);
            input.clear();
            invoke(runtime, "resumeRequested");
        });
        assertTrue(callFx(() -> console.getText().contains("终态运行不可恢复")));
        assertTrue(callFx(() -> console.getText().contains("人工中断恢复必须填写输入")));

        runFx(() -> {
            history.getSelectionModel().select(1);
            input.setText(" answer ");
            invoke(runtime, "resumeRequested");
        });
        awaitFx(() -> service.resumeCalls == 1
                && console.getText().contains("已提交恢复"));
        assertEquals("answer", service.lastResumeInput);
        assertFalse(service.lastResumeConfirmed);

        runFx(() -> {
            history.getSelectionModel().select(2);
            input.clear();
            invoke(runtime, "retryRequested");
        });
        awaitFx(() -> service.resumeCalls == 2);
        assertTrue(service.lastResumeConfirmed);

        service.resumeFailure = new IllegalStateException();
        runFx(() -> {
            history.getSelectionModel().select(2);
            invoke(runtime, "resumeRequested");
        });
        awaitFx(() -> service.resumeCalls == 3
                && console.getText().contains("恢复失败：IllegalStateException"));
        service.resumeFailure = null;

        runFx(() -> {
            history.getSelectionModel().clearSelection();
            invoke(runtime, "cancelRequested");
            history.getSelectionModel().select(2);
            invoke(runtime, "cancelRequested");
        });
        awaitFx(() -> service.cancelCalls == 1 && console.getText().contains("已取消："));
        service.cancelled = false;
        runFx(() -> {
            history.getSelectionModel().select(2);
            invoke(runtime, "cancelRequested");
        });
        awaitFx(() -> service.cancelCalls == 2
                && console.getText().contains("运行已结束或不存在"));

        service.cancelFailure = new IllegalStateException("cancel offline");
        runFx(() -> {
            history.getSelectionModel().select(2);
            invoke(runtime, "cancelRequested");
        });
        awaitFx(() -> service.cancelCalls == 3
                && console.getText().contains("取消运行失败：cancel offline"));
        service.cancelFailure = null;

        service.runsFailure = new IllegalStateException();
        int failedHistoryLoadBefore = service.runCalls;
        runFx(runtime::refresh);
        awaitFx(() -> service.runCalls > failedHistoryLoadBefore
                && console.getText().contains("读取运行历史失败：IllegalStateException"));
        service.runsFailure = null;

        RunObserver observer = runtime.observer();
        runFx(() -> {
            observer.onTrace("node trace");
            observer.onTerminal(new RunTerminal(RunTerminalStatus.COMPLETED, "done"));
            observer.onTerminal(new RunTerminal(RunTerminalStatus.CANCELLED, "user"));
            observer.onTerminal(new RunTerminal(RunTerminalStatus.FAILED, "boom"));
        });
        awaitFx(() -> console.getText().contains("运行结束")
                && console.getText().contains("已取消：user")
                && console.getText().contains("失败：boom"));

        String beforeClose = callFx(console::getText);
        runFx(() -> {
            runtime.close();
            runtime.close();
            runtime.refresh();
            observer.onTrace("late trace");
            observer.onTerminal(new RunTerminal(RunTerminalStatus.FAILED, "late"));
        });
        assertEquals(beforeClose, callFx(console::getText));
    }

    private WorkflowViewController openView() throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(WorkflowViewFactory.class).create(null, ignored -> { }));
        WorkflowViewController controller = view.controller();
        runFx(() -> {
            view.root().applyCss();
            view.root().layout();
            controller.prepare();
        });
        awaitFx(() -> service.snapshotCalls > 0 && model(controller).workflows().size() == 2
                && model(controller).selectedWorkflowProperty().get() != null);
        return controller;
    }

    private void connectConditional(
            WorkflowViewModel model,
            WorkflowCanvasController canvas,
            boolean fallback,
            String path,
            ConditionOperator operator,
            String value,
            int priority) throws Exception {
        selectGraph(model, graphWith(NodeType.CONDITION));
        NodeDefinition source = node(model, NodeType.CONDITION);
        NodeDefinition target = node(model, NodeType.END);
        runFx(() -> invoke(canvas, "connectConditional",
                new Class<?>[] {String.class, String.class,
                        WorkflowConditionDialogController.Selection.class},
                source.id(), target.id(), new WorkflowConditionDialogController.Selection(
                        path, operator, value, priority, fallback)));
    }

    private void selectGraph(WorkflowViewModel model, GraphDefinition graph) throws Exception {
        runFx(() -> model.select(new WorkflowItem(
                graph.id(), graph.name(), graph, false, false)));
    }

    private static GraphDefinition graphWith(NodeType type) {
        WorkflowEditorModel editor = new WorkflowEditorModel(WorkflowEditorModel.blank("branch"));
        editor.addNode(type, 300, 300);
        return editor.current();
    }

    private static NodeDefinition node(WorkflowViewModel model, NodeType type) {
        return model.currentGraph().nodes().stream().filter(value -> value.type() == type)
                .findFirst().orElseThrow();
    }

    private GraphRun run(RunStatus status) {
        GraphDefinition graph = service.draftItem().graph();
        return new GraphRun("run-" + status, graph.id(), graph.version(), "thread", graph,
                new GraphState(), status, null, graph.startNodeId(), 1, 1,
                "", "", null, 1, 2);
    }

    private static WorkflowViewModel model(WorkflowViewController controller) {
        return field(controller, "viewModel", WorkflowViewModel.class);
    }

    private static boolean loading(WorkflowViewController controller) {
        return field(controller, "loadingOverlay", javafx.scene.layout.StackPane.class).isVisible();
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        service = new FakeService();
        context.registerBean(WorkflowApplicationService.class, () -> service);
        context.registerBean(ManagedTaskExecutor.class, () -> new ManagedTaskExecutor(),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean("workspaceTaskScope", TaskScope.class,
                () -> context.getBean(ManagedTaskExecutor.class)
                        .openScope("workflow-controller-test", 16),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(JsonCodec.class, () -> new JsonCodec(new ObjectMapper()));
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(WorkflowDefinitionCellFactory.class,
                WorkflowDefinitionCellFactory::new);
        context.registerBean(WorkflowRunCellFactory.class, WorkflowRunCellFactory::new);
        context.registerBean(WorkflowNodeCardFactory.class, WorkflowNodeCardFactory::new);
        context.registerBean(WorkflowConditionDialogFactory.class,
                () -> new WorkflowConditionDialogFactory(
                        context.getBean(SpringFxmlLoader.class),
                        new com.javaclaw.app.UIHelper(context.getBean(FxDispatcher.class))));
        context.registerBean(WorkflowInputDialogFactory.class,
                () -> new WorkflowInputDialogFactory(
                        context.getBean(SpringFxmlLoader.class),
                        context.getBean(FxDispatcher.class),
                        new com.javaclaw.app.UIHelper(context.getBean(FxDispatcher.class))));
        context.registerBean(WorkflowViewFactory.class,
                () -> new WorkflowViewFactory(context.getBean(SpringFxmlLoader.class),
                        context.getBean(FxDispatcher.class)));
        context.refresh();
    }

    private static TextField textField(Object target, String name) {
        return field(target, name, TextField.class);
    }

    private static TextArea textArea(Object target, String name) {
        return field(target, name, TextArea.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> ComboBox<T> combo(Object target, String name) {
        return field(target, name, ComboBox.class);
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

    private static Object invoke(Object target, String methodName) {
        return invoke(target, methodName, new Class<?>[0]);
    }

    private static Object invoke(
            Object target, String methodName, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法调用 Controller 方法：" + methodName, failure);
        }
    }

    private static double normalizedCenter(double center, double viewport, double content) {
        try {
            Method method = WorkflowCanvasController.class.getDeclaredMethod(
                    "normalizedCenter", double.class, double.class, double.class);
            method.setAccessible(true);
            return (double) method.invoke(null, center, viewport, content);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
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
        callFx(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T callFx(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                completed.countDown();
            }
        });
        assertTrue(completed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    private static final class FakeService implements WorkflowApplicationService {
        private GraphDefinition draft = WorkflowEditorModel.blank("测试工作流");
        private final GraphDefinition system = WorkflowEditorModel.blank("系统工作流");
        private volatile List<ValidationIssue> validation = List.of();
        private volatile List<GraphRun> runs = List.of();
        private volatile RuntimeException snapshotFailure;
        private volatile RuntimeException createFailure;
        private volatile RuntimeException saveFailure;
        private volatile RuntimeException publishFailure;
        private volatile RuntimeException validateFailure;
        private volatile RuntimeException runsFailure;
        private volatile RuntimeException resumeFailure;
        private volatile RuntimeException cancelFailure;
        private volatile boolean cancelled = true;
        private volatile int snapshotCalls;
        private volatile int createCalls;
        private volatile int cloneCalls;
        private volatile int saveCalls;
        private volatile int publishCalls;
        private volatile int validateCalls;
        private volatile int runCalls;
        private volatile int resumeCalls;
        private volatile int cancelCalls;
        private volatile String lastResumeInput;
        private volatile boolean lastResumeConfirmed;

        private WorkflowItem draftItem() {
            return new WorkflowItem(draft.id(), draft.name(), draft, false, false);
        }

        private WorkflowItem systemItem() {
            return new WorkflowItem(system.id(), system.name(), system, true, true);
        }

        @Override
        public Snapshot snapshot() {
            snapshotCalls++;
            if (snapshotFailure != null) throw snapshotFailure;
            return new Snapshot(List.of(draftItem(), systemItem()));
        }

        @Override
        public OperationResult createDraft() {
            createCalls++;
            if (createFailure != null) throw createFailure;
            return new OperationResult(snapshot(), draft.id());
        }

        @Override
        public OperationResult cloneDraft(String workflowId) {
            cloneCalls++;
            return new OperationResult(snapshot(), draft.id());
        }

        @Override
        public void saveDraft(GraphDefinition graph) {
            saveCalls++;
            if (saveFailure != null) throw saveFailure;
            draft = graph;
        }

        @Override
        public PublishResult publish(GraphDefinition graph) {
            publishCalls++;
            if (publishFailure != null) throw publishFailure;
            draft = graph;
            WorkflowItem published = new WorkflowItem(
                    graph.id(), graph.name(), graph, false, true);
            return new PublishResult(new Snapshot(List.of(published, systemItem())), published);
        }

        @Override
        public List<ValidationIssue> validate(GraphDefinition graph) {
            validateCalls++;
            if (validateFailure != null) throw validateFailure;
            return validation;
        }

        @Override
        public List<GraphRun> runs(String workflowId) {
            runCalls++;
            if (runsFailure != null) throw runsFailure;
            return runs;
        }

        @Override
        public void testRun(GraphDefinition graph, String input, RunObserver observer) { }

        @Override
        public void resumeRun(
                String runId, String input, boolean confirmed, RunObserver observer) {
            resumeCalls++;
            lastResumeInput = input;
            lastResumeConfirmed = confirmed;
            if (resumeFailure != null) throw resumeFailure;
        }

        @Override
        public boolean cancelRun(String runId) {
            cancelCalls++;
            if (cancelFailure != null) throw cancelFailure;
            return cancelled;
        }
    }
}
