package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.knowledge.KnowledgeApplicationService;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Document;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Health;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.HealthListener;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.HealthStatus;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.ImportResult;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.ReindexResult;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Scope;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.SearchResult;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Settings;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Snapshot;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.ui.javafx.control.WindowToastFactory;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;
import java.util.List;
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
class KnowledgeCenterFxmlLoadTest {
    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private KnowledgeCenterView view;
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
    void loadsControllerTreeAndReleasesHealthSubscription() throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(KnowledgeCenterViewFactory.class)
                .create(null, () -> { }, () -> { }));
        runFx(view.controller()::prepare);

        awaitFx(() -> documentList().getItems().size() == 1);
        assertEquals("研发工作区", callFx(() ->
                ((Label) view.root().lookup("#workspaceNameLabel")).getText()));
        assertEquals("1", callFx(() ->
                ((Label) view.root().lookup("#totalDocumentsLabel")).getText()));
        assertEquals(1340.0, callFx(() -> view.root().getScene().getWidth()));
        assertEquals(864.0, callFx(() -> view.root().getScene().getHeight()));
        assertEquals("知识库中心", callFx(() ->
                ((Stage) view.root().getScene().getWindow()).getTitle()));

        runFx(view::close);
        view = null;
        assertTrue(service.subscriptionClosed);
    }

    @Test
    void reusableCellsLoadTheirFxmlInConstructor() throws Exception {
        prepareContext();
        runFx(() -> {
            Document document = service.snapshot.documents().getFirst();
            KnowledgeDocumentCell documentCell = context.getBean(KnowledgeDocumentCellFactory.class)
                    .create(ignored -> { }, (ignored, enabled) -> { });
            documentCell.updateItem(document, false);
            assertNotNull(documentCell.getGraphic());

            KnowledgePreviewCell previewCell = context.getBean(KnowledgePreviewCellFactory.class)
                    .create();
            previewCell.updateItem("预览", false);
            assertNotNull(previewCell.getGraphic());

            KnowledgeSearchHitCell searchCell = context.getBean(KnowledgeSearchHitCellFactory.class)
                    .create(() -> "架构");
            searchCell.updateItem(new KnowledgeApplicationService.SearchHit(
                    "guide.md", Scope.WORKSPACE, .9, "JavaClaw 架构说明"), false);
            assertNotNull(searchCell.getGraphic());
        });
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        service = new FakeService();
        ManagedTaskExecutor executor = new ManagedTaskExecutor();
        FxDispatcher fx = new FxDispatcher();
        UserInteractionPort interaction = new UserInteractionPort() {
            @Override public boolean confirm(ConfirmRequest request) { return true; }
            @Override public void notify(ToastRequest request) { }
        };
        context.registerBean(KnowledgeApplicationService.class, () -> service);
        context.registerBean(ManagedTaskExecutor.class, () -> executor,
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean("workspaceTaskScope", TaskScope.class,
                () -> executor.openScope("knowledge-fxml-test", 8),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(FxDispatcher.class, () -> fx);
        context.registerBean(UserInteractionPort.class, () -> interaction);
        context.registerBean(DialogService.class, () -> new DialogService(interaction));
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(WindowToastFactory.class,
                () -> new WindowToastFactory(context.getBean(SpringFxmlLoader.class)));
        context.registerBean(KnowledgeDocumentCellFactory.class, KnowledgeDocumentCellFactory::new);
        context.registerBean(KnowledgePreviewCellFactory.class, KnowledgePreviewCellFactory::new);
        context.registerBean(KnowledgeSearchHitCellFactory.class, KnowledgeSearchHitCellFactory::new);
        context.registerBean(KnowledgeImportPicker.class, FakePicker::new);
        context.registerBean(KnowledgeTextImportDialogFactory.class,
                () -> new KnowledgeTextImportDialogFactory(
                        context.getBean(SpringFxmlLoader.class), fx));
        context.registerBean(KnowledgeCenterViewFactory.class,
                () -> new KnowledgeCenterViewFactory(context.getBean(SpringFxmlLoader.class)));
        context.refresh();
    }

    @SuppressWarnings("unchecked")
    private ListView<Document> documentList() {
        return (ListView<Document>) view.root().lookup("#documentList");
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

    private static final class FakePicker implements KnowledgeImportPicker {
        @Override public List<Path> chooseFiles(javafx.stage.Window owner) { return List.of(); }
        @Override public Optional<Path> chooseDirectory(javafx.stage.Window owner) {
            return Optional.empty();
        }
    }

    private static final class FakeService implements KnowledgeApplicationService {
        private final Snapshot snapshot = new Snapshot(true, "", "研发工作区",
                new Settings("OpenAI", "https://example.test", "embed-v3", 1024,
                        6, 512, 64),
                new Health(HealthStatus.HEALTHY, ""),
                List.of(new Document("guide.md", Scope.WORKSPACE, true, 3,
                        "2026-08-12 10:00:00", "JavaClaw 架构说明", List.of("预览片段"))));
        private boolean subscriptionClosed;

        @Override public Snapshot snapshot() { return snapshot; }
        @Override public SearchResult search(String query) { return new SearchResult(query, List.of()); }
        @Override public ImportResult importFiles(List<Path> files, Scope scope) {
            return new ImportResult(snapshot, files.size(), 0, List.of());
        }
        @Override public ImportResult importText(String title, String text, Scope scope) {
            return new ImportResult(snapshot, 1, 0, List.of());
        }
        @Override public Snapshot setDocumentEnabled(String name, boolean enabled) { return snapshot; }
        @Override public Snapshot setAllEnabled(boolean enabled, Scope scope) { return snapshot; }
        @Override public Snapshot deleteDocument(String name) { return snapshot; }
        @Override public Snapshot clear() { return snapshot; }
        @Override public ReindexResult rebuildIndex() { return new ReindexResult(snapshot, 3); }
        @Override public Settings saveChunkSettings(int chunkSize, int chunkOverlap) {
            return snapshot.settings();
        }
        @Override public AutoCloseable observeHealth(HealthListener listener) {
            return () -> subscriptionClosed = true;
        }
    }
}
