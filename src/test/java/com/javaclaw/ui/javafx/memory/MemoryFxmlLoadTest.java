package com.javaclaw.ui.javafx.memory;

import com.javaclaw.api.interaction.ChoiceRequest;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.memory.MemoryApplicationService;
import com.javaclaw.memory.graph.MemoryGraph;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;
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
class MemoryFxmlLoadTest {
    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private MemoryView view;

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
    void completeWindowAndAllDynamicFragmentsLoadFromFxml() throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(MemoryViewFactory.class).create(null));
        runFx(view.controller()::prepare);
        awaitFx(() -> "1 事实 · 1 情景".equals(text("scaleMain")));

        assertEquals(1000.0, callFx(() -> view.root().getScene().getWidth()));
        assertEquals(680.0, callFx(() -> view.root().getScene().getHeight()));
        assertNotNull(callFx(() -> view.root().lookup("#scaleSub")));

        MemoryChildView<javafx.scene.layout.VBox> factGroup = callFx(() ->
                context.getBean(MemoryComponentFactory.class).factGroup(
                        "偏好", context.getBean(MemoryApplicationService.class)
                                .snapshot().facts(), false, java.util.Set.of(),
                        new NoopFactActions(), true, ignored -> { }));
        runFx(factGroup::close);

        runFx(() -> ((Button) view.root().lookup("#factsButton")).fire());
        awaitFx(() -> section(MemoryFactsController.class, "facts").renderedGroupCount() == 1);
        runFx(() -> ((Button) view.root().lookup("#episodesButton")).fire());
        awaitFx(() -> section(MemoryEpisodesController.class, "episodes").renderedEpisodeCount() == 1);
        runFx(() -> ((Button) view.root().lookup("#entitiesButton")).fire());
        awaitFx(() -> section(MemoryEntitiesController.class, "entities").renderedGroupCount() == 1);
        runFx(() -> ((Button) view.root().lookup("#knowledgeButton")).fire());
        awaitFx(() -> section(MemoryKnowledgeController.class, "knowledge").renderedDocumentCount() == 1);
        runFx(() -> ((Button) view.root().lookup("#correctionsButton")).fire());
        awaitFx(() -> section(MemoryCorrectionsController.class, "corrections").renderedCorrectionCount() == 1);
        runFx(() -> ((Button) view.root().lookup("#logButton")).fire());
        awaitFx(() -> section(MemoryLogController.class, "log").renderedChangeCount() == 1);
        runFx(() -> ((Button) view.root().lookup("#graphButton")).fire());
        awaitFx(() -> section(MemoryGraphController.class, "graph").statusText().contains("1 个节点"));
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(MemoryApplicationService.class, FakeService::new);
        context.registerBean(UserInteractionPort.class, AllowInteraction::new);
        context.registerBean(DialogService.class,
                () -> new DialogService(context.getBean(UserInteractionPort.class)));
        context.registerBean(ManagedTaskExecutor.class, () -> new ManagedTaskExecutor(),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean("workspaceTaskScope", TaskScope.class,
                () -> context.getBean(ManagedTaskExecutor.class)
                        .openScope("memory-fxml-test", 16),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(MemoryComponentFactory.class,
                () -> new MemoryComponentFactory(context.getBean(SpringFxmlLoader.class)));
        context.registerBean(MemoryFactDialogFactory.class,
                () -> new MemoryFactDialogFactory(
                        context.getBean(SpringFxmlLoader.class),
                        new com.javaclaw.app.UIHelper(context.getBean(FxDispatcher.class))));
        context.registerBean(MemoryViewFactory.class,
                () -> new MemoryViewFactory(context.getBean(SpringFxmlLoader.class)));
        context.refresh();
    }

    private String text(String id) {
        return ((Label) view.root().lookup("#" + id)).getText();
    }

    private <T extends MemorySectionController> T section(Class<T> type, String id) {
        return type.cast(view.controller().sectionController(id));
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
        @Override public String choose(ChoiceRequest request) { return null; }
        @Override public void notify(ToastRequest request) {}
    }

    private static final class NoopFactActions implements MemoryFactActions {
        @Override public void toggleSelected(String id) {}
        @Override public void edit(String id, String text) {}
        @Override public void togglePin(String id) {}
        @Override public void restore(String id) {}
        @Override public void delete(String id, String text) {}
    }

    private static final class FakeService implements MemoryApplicationService {
        private final Snapshot snapshot = sampleSnapshot();
        @Override public Snapshot snapshot() { return snapshot; }
        @Override public OperationResult probeAndRefill() { return result(0, ""); }
        @Override public OperationResult refillPending() { return result(0, ""); }
        @Override public MemoryGraph graph() {
            return new MemoryGraph(List.of(new MemoryGraph.Node(
                    "fact-1", "偏好 Java", "fact", "偏好", "", 1)), List.of());
        }
        @Override public OperationResult addFact(AddFactCommand command) { return result(1, "已新增"); }
        @Override public OperationResult editFact(EditFactCommand command) { return result(1, "已编辑"); }
        @Override public OperationResult toggleFactPin(String factId) { return result(1, "已置顶"); }
        @Override public OperationResult restoreFact(String factId) { return result(1, "已恢复"); }
        @Override public OperationResult deleteFacts(List<String> factIds) { return result(factIds.size(), "已删除"); }
        @Override public OperationResult reindexDocument(String name) { return result(1, "已重建"); }
        @Override public OperationResult deleteDocument(String name) { return result(1, "已删除"); }
        @Override public OperationResult savePersona(PersonaDraft persona) { return result(1, "已保存"); }
        @Override public String personaMarkdown(PersonaDraft persona) { return "# 人格\n" + persona.identity(); }
        @Override public void exportPersona(Path target, PersonaDraft persona) {}
        @Override public OperationResult revokeCorrection(String id) { return result(1, "已撤销"); }
        @Override public OperationResult deleteCorrection(String id) { return result(1, "已删除"); }
        private OperationResult result(int affected, String message) {
            return new OperationResult(snapshot, affected, message);
        }

        private static Snapshot sampleSnapshot() {
            return new Snapshot(new Statistics(3, 2, 1, 1),
                    List.of(new FactItem("fact-1", "偏好", "偏好 Java", 1, 2, 1,
                            true, true, true, false, false, false, "episode-1", List.of("Java"))),
                    List.of(new EpisodeItem("episode-1", "使用什么语言？", "Java", "[]",
                            1, false, 1)),
                    List.of(new EntityItem("entity-1", "Java", "tool", 1)),
                    List.of(new KnowledgeDocument("guide.md", 1, 100, "2026-08-11")),
                    new PersonaDraft("Java 助手", "简洁直接", List.of("不可变数据"), List.of("臆测")),
                    List.of(new CorrectionItem("correction-1", "FACT_REPLACEMENT", "USER",
                            "ACTIVE", "Python", "Java", "我使用 Java", 1, true)),
                    List.of(new ChangeItem(1, "ADD", "Fact", "fact-1", "新增偏好")),
                    new EmbeddingState("", 0));
        }
    }
}
