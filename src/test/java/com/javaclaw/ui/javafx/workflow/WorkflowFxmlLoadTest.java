package com.javaclaw.ui.javafx.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.workflow.WorkflowApplicationService;
import com.javaclaw.application.workflow.WorkflowApplicationService.WorkflowItem;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.workflow.editor.WorkflowEditorModel;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.runtime.GraphRun;
import com.javaclaw.workflow.runtime.ValidationIssue;
import javafx.application.Platform;
import javafx.scene.control.ListView;
import javafx.scene.layout.GridPane;
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
class WorkflowFxmlLoadTest {

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
    void loadsCompleteWindowAndDestroysControllerTree() throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(WorkflowViewFactory.class).create(null, ignored -> { }));
        runFx(view::show);

        awaitFx(() -> workflowList().getItems().size() == 1);
        assertEquals(1320.0, callFx(() -> view.root().getScene().getWidth()));
        assertEquals(820.0, callFx(() -> view.root().getScene().getHeight()));
        assertEquals("JavaClaw · 工作流中心", callFx(() ->
                ((Stage) view.root().getScene().getWindow()).getTitle()));
        assertNotNull(callFx(() -> view.root().lookup(".workflow-canvas-shell")));
        assertNotNull(callFx(() -> view.root().lookup(".workflow-inspector")));
        assertNotNull(callFx(() -> view.root().lookup(".workflow-dock")));

        runFx(view::close);
        view = null;
    }

    @Test
    void loadsReusableCellsCardsAndDialogContent() throws Exception {
        prepareContext();
        callFx(() -> {
            WorkflowItem item = service().snapshot().workflows().getFirst();
            WorkflowDefinitionCell definitionCell =
                    new WorkflowDefinitionCellFactory().create();
            definitionCell.updateItem(item, false);
            assertNotNull(definitionCell.getGraphic());

            WorkflowNodeCard card = new WorkflowNodeCardFactory().create(
                    item.graph().nodes().getFirst());
            assertNotNull(card.root());

            SpringFxmlLoader loader = context.getBean(SpringFxmlLoader.class);
            try (ViewHandle<GridPane> condition = loader.load(
                    WorkflowFxmlLoadTest.class.getResource(
                            "/fxml/workflow/workflow-condition-dialog.fxml"));
                 ViewHandle<VBox> input = loader.load(
                         WorkflowFxmlLoadTest.class.getResource(
                                 "/fxml/workflow/workflow-input-dialog.fxml"))) {
                assertNotNull(condition.controller(WorkflowConditionDialogController.class));
                assertNotNull(input.controller(WorkflowInputDialogController.class));
            }
            return null;
        });
    }

    private FakeService service() { return service; }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        service = new FakeService();
        context.registerBean(WorkflowApplicationService.class, () -> service);
        context.registerBean(ManagedTaskExecutor.class, () -> new ManagedTaskExecutor(),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean("workspaceTaskScope", TaskScope.class,
                () -> context.getBean(ManagedTaskExecutor.class)
                        .openScope("workflow-fxml-test", 16),
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
                () -> new WorkflowInputDialogFactory(context.getBean(SpringFxmlLoader.class),
                        context.getBean(FxDispatcher.class),
                        new com.javaclaw.app.UIHelper(context.getBean(FxDispatcher.class))));
        context.registerBean(WorkflowViewFactory.class,
                () -> new WorkflowViewFactory(context.getBean(SpringFxmlLoader.class),
                        context.getBean(FxDispatcher.class)));
        context.refresh();
    }

    @SuppressWarnings("unchecked")
    private ListView<WorkflowItem> workflowList() {
        return (ListView<WorkflowItem>) view.root().lookup("#workflowList");
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

    private static final class FakeService implements WorkflowApplicationService {
        private final GraphDefinition graph = WorkflowEditorModel.blank("测试工作流");
        @Override public Snapshot snapshot() {
            return new Snapshot(List.of(new WorkflowItem(
                    graph.id(), graph.name(), graph, false, false)));
        }
        @Override public OperationResult createDraft() {
            return new OperationResult(snapshot(), graph.id());
        }
        @Override public OperationResult cloneDraft(String workflowId) {
            return new OperationResult(snapshot(), graph.id());
        }
        @Override public void saveDraft(GraphDefinition graph) { }
        @Override public PublishResult publish(GraphDefinition graph) {
            return new PublishResult(snapshot(), snapshot().workflows().getFirst());
        }
        @Override public List<ValidationIssue> validate(GraphDefinition graph) { return List.of(); }
        @Override public List<GraphRun> runs(String workflowId) { return List.of(); }
        @Override public void testRun(GraphDefinition graph, String input, RunObserver observer) { }
        @Override public void resumeRun(
                String runId, String input, boolean confirmed, RunObserver observer) { }
        @Override public boolean cancelRun(String runId) { return true; }
    }
}
