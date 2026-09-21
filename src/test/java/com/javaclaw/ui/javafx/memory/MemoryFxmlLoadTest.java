package com.javaclaw.ui.javafx.memory;

import com.javaclaw.api.interaction.ChoiceRequest;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.memory.MemoryApplicationService;
import com.javaclaw.memory.graph.MemoryGraph;
import com.javaclaw.memory.MemoryGraphScope;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.testsupport.FxmlTestBeans;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertNotNull(callFx(() -> node("scaleSub")));

        MemoryChildView<javafx.scene.layout.VBox> factGroup = callFx(() ->
                context.getBean(MemoryComponentFactory.class).factGroup(
                        "偏好", context.getBean(MemoryApplicationService.class)
                                .snapshot().facts(), false, java.util.Set.of(),
                        new NoopFactActions(), true, ignored -> { }));
        runFx(factGroup::close);

        runFx(() -> ((Button) node("factsButton")).fire());
        awaitFx(() -> section(MemoryFactsController.class, "facts").renderedGroupCount() == 1);
        runFx(() -> ((Button) node("episodesButton")).fire());
        awaitFx(() -> section(MemoryEpisodesController.class, "episodes").renderedEpisodeCount() == 1);
        runFx(() -> ((Button) node("entitiesButton")).fire());
        awaitFx(() -> section(MemoryEntitiesController.class, "entities").renderedGroupCount() == 1);
        runFx(() -> ((Button) node("knowledgeButton")).fire());
        awaitFx(() -> section(MemoryKnowledgeController.class, "knowledge").renderedDocumentCount() == 1);
        runFx(() -> ((Button) node("correctionsButton")).fire());
        awaitFx(() -> section(MemoryCorrectionsController.class, "corrections").renderedCorrectionCount() == 1);
        runFx(() -> ((Button) node("logButton")).fire());
        awaitFx(() -> section(MemoryLogController.class, "log").renderedChangeCount() == 1);
        runFx(() -> ((Button) node("graphButton")).fire());
        awaitFx(() -> section(MemoryGraphController.class, "graph").statusText().contains("1 个节点"));
    }

    @Test
    void scopeSelectorSeparatesEmptyThreadAndReadOnlyLegacyFromPersonalHabits() throws Exception {
        openReady();
        runFx(() -> selector().setValue(FakeService.THREAD));
        awaitFx(() -> text("scaleMain").equals("0 事实 · 0 情景") && !loading());
        assertTrue(callFx(() -> button("personaButton").isDisabled()));
        assertTrue(callFx(() -> button("knowledgeButton").isDisabled()));
        runFx(() -> button("factsButton").fire());
        assertEquals(0, callFx(() -> section(MemoryFactsController.class, "facts").renderedGroupCount()));
        runFx(() -> button("graphButton").fire());
        awaitFx(() -> section(MemoryGraphController.class, "graph").statusText().contains("0 个节点"));
        runFx(() -> selector().setValue(FakeService.LEGACY));
        awaitFx(() -> text("scaleMain").equals("1 事实 · 1 情景") && !loading());
        assertFalse(callFx(() -> button("refillButton").isVisible()), "旧混库不能通过回填成为自动召回图谱");
        runFx(() -> button("factsButton").fire());
        assertTrue(callFx(() -> button("addButton").isDisabled()));
        assertTrue(callFx(() -> button("batchButton").isDisabled()));
        assertTrue(callFx(() -> button("pinButton").isDisabled()));
        runFx(() -> button("correctionsButton").fire());
        assertEquals(1, callFx(() -> section(MemoryCorrectionsController.class, "corrections").renderedCorrectionCount()));
        assertTrue(callFx(() -> button("revokeButton").isDisabled()));
        assertTrue(callFx(() -> button("deleteButton").isDisabled()));
        runFx(() -> selector().setValue(FakeService.HABITS));
        awaitFx(() -> !loading());
        assertFalse(callFx(() -> button("personaButton").isDisabled()));
        assertFalse(callFx(() -> button("knowledgeButton").isDisabled()));
        runFx(() -> button("factsButton").fire());
        assertFalse(callFx(() -> button("addButton").isDisabled()));
        assertFalse(callFx(() -> button("pinButton").isDisabled()));
    }

    @Test
    void runningGraphBuildPreventsScopeSwitchAndSwitchWorksAfterCompletion() throws Exception {
        openReady();
        FakeService service = (FakeService) context.getBean(MemoryApplicationService.class);
        service.graphBlock = new CountDownLatch(1);
        try {
            runFx(() -> button("graphButton").fire());
            assertTrue(service.graphEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            runFx(() -> selector().setValue(FakeService.THREAD));
            assertEquals(FakeService.HABITS, callFx(() -> selector().getValue()));
        } finally { service.graphBlock.countDown(); }
        awaitFx(() -> !section(MemoryGraphController.class, "graph").isBusy());
        runFx(() -> selector().setValue(FakeService.THREAD));
        awaitFx(() -> !loading() && text("scaleMain").equals("0 事实 · 0 情景"));
        assertEquals(FakeService.THREAD, callFx(() -> selector().getValue()));
        runFx(() -> { selector().setValue(null); selector().setValue(FakeService.THREAD); });
        assertEquals(FakeService.THREAD, callFx(() -> selector().getValue()));
    }

    @Test
    void editsAndFilteringAfterScopeSwitchStayInThatGraphAndClearBatchSelection() throws Exception {
        prepareContext();
        FakeService catalog = (FakeService) context.getBean(MemoryApplicationService.class);
        FakeService target = catalog.views.get(FakeService.THREAD);
        target.snapshot = FakeService.sampleSnapshot();
        createReady();
        runFx(() -> {
            button("factsButton").fire();
            button("batchButton").fire();
            section(MemoryFactsController.class, "facts").toggleSelected("fact-1");
            selector().setValue(FakeService.THREAD);
        });
        awaitFx(() -> !loading());
        runFx(() -> button("factsButton").fire());
        assertEquals("已选 0 条", callFx(() -> text("selectedCount")));
        assertEquals("批量选择", callFx(() -> button("batchButton").getText()));
        runFx(() -> section(MemoryFactsController.class, "facts").edit("fact-1", "会话内修订"));
        awaitFx(() -> target.mutations.contains("edit:fact-1:会话内修订")
                && !section(MemoryFactsController.class, "facts").isBusy());
        assertTrue(catalog.mutations.isEmpty());
        runFx(() -> {
            button("correctionsButton").fire();
            button("revokeButton").fire();
        });
        awaitFx(() -> target.mutations.contains("revoke:correction-1")
                && !section(MemoryCorrectionsController.class, "corrections").isBusy());
        runFx(() -> {
            ((TextField) node("searchField")).setText("不存在的关键词");
            button("factsButton").fire();
        });
        assertEquals(0, callFx(() -> section(MemoryFactsController.class, "facts").renderedGroupCount()));
        runFx(() -> button("correctionsButton").fire());
        assertEquals(0, callFx(() -> section(MemoryCorrectionsController.class, "corrections").renderedCorrectionCount()));
        runFx(() -> button("episodesButton").fire());
        assertEquals(0, callFx(() -> section(MemoryEpisodesController.class, "episodes").renderedEpisodeCount()));
        runFx(() -> button("entitiesButton").fire());
        assertEquals(0, callFx(() -> section(MemoryEntitiesController.class, "entities").renderedGroupCount()));
        runFx(() -> {
            ((TextField) node("searchField")).clear();
            button("factsButton").fire();
            button("batchButton").fire();
            var facts = section(MemoryFactsController.class, "facts");
            facts.toggleSelected("fact-1"); facts.toggleSelected("fact-1"); facts.toggleSelected("fact-1");
            button("deleteSelectedButton").fire();
        });
        awaitFx(() -> target.mutations.contains("delete:fact-1")
                && !section(MemoryFactsController.class, "facts").isBusy());
        assertEquals("已选 0 条", callFx(() -> text("selectedCount")));
        runFx(() -> view.controller().apply(null));
    }

    @Test
    void personalPersonaFormAddsRemovesAndSavesOnlyTheHabitsScope() throws Exception {
        openReady();
        FakeService service = (FakeService) context.getBean(MemoryApplicationService.class);
        runFx(() -> {
            button("personaButton").fire();
            ((TextArea) node("identity")).setText("工作区助手");
            button("patientTone").fire(); button("livelyTone").fire(); button("directTone").fire();
            TextField preference = (TextField) node("preferenceInput");
            preference.setText("  "); preference.fireEvent(new javafx.event.ActionEvent());
            preference.setText("使用中文"); preference.fireEvent(new javafx.event.ActionEvent());
            TextField taboo = (TextField) node("tabooInput");
            taboo.setText("泄露凭据"); taboo.fireEvent(new javafx.event.ActionEvent());
            var preferenceRows = (javafx.scene.layout.VBox) node("preferences");
            ((Button) preferenceRows.getChildren().getFirst().lookup(".button")).fire();
            var tabooRows = (javafx.scene.layout.VBox) node("taboos");
            ((Button) tabooRows.getChildren().getFirst().lookup(".button")).fire();
            button("saveButton").fire();
        });
        awaitFx(() -> service.savedPersona != null && !section(MemoryPersonaController.class, "persona").isBusy());
        assertEquals("工作区助手", service.savedPersona.identity());
        assertEquals(List.of("使用中文"), service.savedPersona.preferences());
        assertEquals(List.of("泄露凭据"), service.savedPersona.taboos());
        assertTrue(service.views.get(FakeService.THREAD).mutations.isEmpty());
    }

    @Test
    void pendingAndDisputedFactsExposeOnlyValidRecoveryAndEditingActions() throws Exception {
        prepareContext();
        var components = context.getBean(MemoryComponentFactory.class);
        var edited = new AtomicReference<String>();
        MemoryFactActions actions = new NoopFactActions() {
            @Override public void edit(String id, String value) { edited.set(id + ":" + value); }
        };
        var pending = new MemoryApplicationService.FactItem("pending", "旧图", "待索引的原始断言", 1, 0, 0,
                false, true, false, true, true, true, "", List.of());
        MemoryChildView<javafx.scene.layout.HBox> pendingRow = callFx(() -> components.factRow(pending, true, true, actions));
        try {
            assertTrue(callFx(() -> pendingRow.root().lookup("#pending").isVisible()));
            assertTrue(callFx(() -> pendingRow.root().lookup("#asserted").isVisible()));
            assertTrue(callFx(() -> pendingRow.root().lookup("#superseded").isVisible()));
            assertFalse(callFx(() -> pendingRow.root().lookup("#contested").isVisible()));
            assertFalse(callFx(() -> pendingRow.root().lookup("#restoreButton").isVisible()));
            assertEquals("✓", callFx(() -> ((Button) pendingRow.root().lookup("#selectionButton")).getText()));
        } finally { runFx(pendingRow::close); }
        var disputed = new MemoryApplicationService.FactItem("disputed", "会话", "尚待核实的说法", 1, 0, 0,
                false, false, false, false, true, false, "", List.of());
        MemoryChildView<javafx.scene.layout.HBox> row = callFx(() -> components.factRow(disputed, false, false, actions));
        try {
            assertTrue(callFx(() -> row.root().lookup("#restoreButton").isVisible()));
            runFx(() -> buttonWithText(row.root(), "✎").fire());
            assertTrue(callFx(() -> row.root().lookup("#editBox").isVisible()));
            runFx(() -> buttonWithText(row.root(), "保存并重嵌入").fire());
            assertFalse(callFx(() -> row.root().lookup("#editBox").isVisible()));
            runFx(() -> {
                buttonWithText(row.root(), "✎").fire();
                ((TextArea) row.root().lookup("#editArea")).setText(" ");
                buttonWithText(row.root(), "保存并重嵌入").fire();
                buttonWithText(row.root(), "✎").fire();
                ((TextArea) row.root().lookup("#editArea")).setText("已核验的会话结论");
                buttonWithText(row.root(), "保存并重嵌入").fire();
                buttonWithText(row.root(), "取消").fire();
            });
            assertEquals("disputed:已核验的会话结论", edited.get());
        } finally { runFx(row::close); }
    }

    @Test
    void correctionRowsDistinguishUnverifiedRetractionsAndMissingReplacementClaims() throws Exception {
        prepareContext();
        var components = context.getBean(MemoryComponentFactory.class);
        String[] statuses = {"DISPUTED", "REVOKED", "ACTIVE", ""};
        String[] types = {"METHOD_CORRECTION", "RETRACTION", "FACT_REPLACEMENT", ""};
        String[] scopes = {"PROJECT", "GENERAL", "USER", ""};
        String[] wrong = {"", "错误结论", "", "旧结论"};
        String[] correct = {"", "", "新结论", "新结论"};
        String[] claims = {"（仅否定上一轮回答，未给出替代说法）", "「错误结论」", "「新结论」", "「旧结论」  →  「新结论」"};
        for (int index = 0; index < statuses.length; index++) {
            int position = index;
            var item = new MemoryApplicationService.CorrectionItem("c" + index, types[index], scopes[index], statuses[index],
                    wrong[index], correct[index], index == 0 ? "原始用户纠错" : "", 1, index == 2);
            MemoryChildView<javafx.scene.layout.HBox> row = callFx(() -> components.correction(item, ignored -> {}, ignored -> {}));
            try {
                assertEquals(claims[index], callFx(() -> ((Label) row.root().lookup("#claim")).getText()));
                assertEquals(position == 2, callFx(() -> row.root().lookup("#revokeButton").isVisible()));
                assertEquals(position == 0, callFx(() -> row.root().lookup("#source").isVisible()));
                assertEquals(position != 3, callFx(() -> row.root().lookup("#type").isVisible()));
            } finally { runFx(row::close); }
        }
    }

    private static Button buttonWithText(javafx.scene.Parent parent, String text) {
        return parent.lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
    }

    private void openReady() throws Exception { prepareContext(); createReady(); }
    private void createReady() throws Exception {
        view = callFx(() -> context.getBean(MemoryViewFactory.class).create(null));
        runFx(view.controller()::prepare);
        awaitFx(() -> selector().getItems().size() == 3 && !loading());
    }
    @SuppressWarnings("unchecked")
    private ComboBox<MemoryGraphScope> selector() { return (ComboBox<MemoryGraphScope>) node("graphScope"); }
    private Button button(String id) { return (Button) node(id); }
    private boolean loading() { return node("loadingOverlay").isVisible(); }

    private javafx.scene.Node node(String id) {
        javafx.scene.Node found = findNode(view.root(), id);
        assertNotNull(found, "FXML control: " + id);
        return found;
    }
    private static javafx.scene.Node findNode(javafx.scene.Node root, String id) {
        if (id.equals(root.getId())) return root;
        // ScrollPane content is a public FXML property before its lazy skin joins the scene graph.
        if (root instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            javafx.scene.Node found = findNode(scroll.getContent(), id);
            if (found != null) return found;
        }
        if (root instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                javafx.scene.Node found = findNode(child, id);
                if (found != null) return found;
            }
        }
        return null;
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
        FxmlTestBeans.register(context);
        context.refresh();
    }

    private String text(String id) {
        return ((Label) node(id)).getText();
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

    private static class NoopFactActions implements MemoryFactActions {
        @Override public void toggleSelected(String id) {}
        @Override public void edit(String id, String text) {}
        @Override public void togglePin(String id) {}
        @Override public void restore(String id) {}
        @Override public void delete(String id, String text) {}
    }

    private static final class FakeService implements MemoryApplicationService {
        private static final MemoryGraphScope HABITS = new MemoryGraphScope("workspace", "user", "", MemoryGraphScope.Kind.WORKSPACE_HABITS);
        private static final MemoryGraphScope THREAD = new MemoryGraphScope("workspace", "user", "thread", MemoryGraphScope.Kind.THREAD);
        private static final MemoryGraphScope LEGACY = new MemoryGraphScope("workspace", "user", "", MemoryGraphScope.Kind.LEGACY);
        private volatile Snapshot snapshot;
        private final MemoryGraphScope scope;
        private final java.util.Map<MemoryGraphScope, FakeService> views;
        private final java.util.List<String> mutations = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile PersonaDraft savedPersona;
        private volatile CountDownLatch graphBlock;
        private final CountDownLatch graphEntered = new CountDownLatch(1);
        FakeService() {
            this(HABITS, sampleSnapshot(), new java.util.LinkedHashMap<>());
            views.put(HABITS, this);
            views.put(THREAD, new FakeService(THREAD, new Snapshot(null, null, null, null, null, null, null, null, null), views));
            Snapshot sample = sampleSnapshot();
            views.put(LEGACY, new FakeService(LEGACY, new Snapshot(sample.statistics(), sample.facts(), sample.episodes(),
                    sample.entities(), sample.documents(), sample.persona(), sample.corrections(), sample.changes(), new EmbeddingState("", 2)), views));
        }
        FakeService(MemoryGraphScope scope, Snapshot snapshot, java.util.Map<MemoryGraphScope, FakeService> views) {
            this.scope = scope; this.snapshot = snapshot; this.views = views;
        }
        @Override public MemoryGraphScope scope() { return scope; }
        @Override public List<MemoryGraphScope> scopes() { return List.copyOf(views.keySet()); }
        @Override public MemoryApplicationService inScope(MemoryGraphScope selected) { return views.get(selected); }
        @Override public Snapshot snapshot() { return snapshot; }
        @Override public OperationResult probeAndRefill() { return result(0, ""); }
        @Override public OperationResult refillPending() { return result(0, ""); }
        @Override public MemoryGraph graph() {
            graphEntered.countDown();
            if (graphBlock != null) {
                try { if (!graphBlock.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new IllegalStateException("graph blocked"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            }
            if (snapshot.facts().isEmpty()) return MemoryGraph.empty();
            return new MemoryGraph(List.of(new MemoryGraph.Node(
                    "fact-1", "偏好 Java", "fact", "偏好", "", 1)), List.of());
        }
        @Override public OperationResult addFact(AddFactCommand command) { return result(1, "已新增"); }
        @Override public OperationResult editFact(EditFactCommand command) { mutations.add("edit:" + command.id() + ":" + command.text()); return result(1, "已编辑"); }
        @Override public OperationResult toggleFactPin(String factId) { return result(1, "已置顶"); }
        @Override public OperationResult restoreFact(String factId) { return result(1, "已恢复"); }
        @Override public OperationResult deleteFacts(List<String> factIds) { mutations.add("delete:" + String.join(",", factIds)); return result(factIds.size(), "已删除"); }
        @Override public OperationResult reindexDocument(String name) { return result(1, "已重建"); }
        @Override public OperationResult deleteDocument(String name) { return result(1, "已删除"); }
        @Override public OperationResult savePersona(PersonaDraft persona) { savedPersona = persona; return result(1, "已保存"); }
        @Override public String personaMarkdown(PersonaDraft persona) { return "# 人格\n" + persona.identity(); }
        @Override public void exportPersona(Path target, PersonaDraft persona) {}
        @Override public OperationResult revokeCorrection(String id) { mutations.add("revoke:" + id); return result(1, "已撤销"); }
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
