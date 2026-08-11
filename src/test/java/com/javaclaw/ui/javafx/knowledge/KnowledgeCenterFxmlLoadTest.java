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
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class KnowledgeCenterFxmlLoadTest {
    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private KnowledgeCenterView view;
    private FakeService service;
    private FakePicker picker;
    private FakeInteraction interaction;

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

    @Test
    void centerCoordinatesNavigationHealthLifecycleAndConfigChanges() throws Exception {
        prepareContext();
        AtomicBoolean configChanged = new AtomicBoolean();
        AtomicBoolean modelSettingsOpened = new AtomicBoolean();
        view = callFx(() -> context.getBean(KnowledgeCenterViewFactory.class)
                .create(null, () -> configChanged.set(true),
                        () -> modelSettingsOpened.set(true)));
        runFx(() -> {
            view.controller().prepare();
            view.controller().prepare();
        });
        awaitFx(() -> service.snapshotCalls >= 1 && documentList().getItems().size() == 1);
        assertEquals(1, service.observeCalls, "重复 prepare 不得重复订阅健康事件");

        runFx(() -> {
            invoke(view.controller(), "workspaceRequested");
            invoke(view.controller(), "globalRequested");
            invoke(view.controller(), "allRequested");
            invoke(view.controller(), "searchRequested");
            assertTrue(field(view.controller(), "searchPanel", VBox.class).isVisible());
            invoke(view.controller(), "settingsRequested");
            assertTrue(field(view.controller(), "settingsPanel", VBox.class).isVisible());
            invoke(view.controller(), "embeddingSettingsRequested");
            assertTrue(modelSettingsOpened.get());
        });

        runFx(() -> {
            for (HealthStatus status : HealthStatus.values()) {
                Snapshot state = snapshot(true, new Health(status, "detail"),
                        new Settings("", "", null, 0, 1, 128, 0), documents());
                invoke(view.controller(), "acceptSnapshot",
                        new Class<?>[] {Snapshot.class}, state);
            }
            Snapshot disabled = snapshot(false,
                    new Health(HealthStatus.HEALTHY, ""), service.snapshot.settings(), documents());
            invoke(view.controller(), "acceptSnapshot",
                    new Class<?>[] {Snapshot.class}, disabled);
            assertEquals("RAG 未启用",
                    field(view.controller(), "ragBadgeLabel", Label.class).getText());
            invoke(view.controller(), "acceptSnapshot",
                    new Class<?>[] {Snapshot.class}, service.snapshot);
            invoke(view.controller(), "notifyUser", new Class<?>[] {String.class}, "手动刷新完成");
            invoke(view.controller(), "markChunkSettingsDirty");
        });

        service.emitHealth(new Health(HealthStatus.DEGRADED, "slow"));
        awaitFx(() -> field(view.controller(), "ragBadgeLabel", Label.class)
                .getText().contains("降级"));

        service.snapshotFailure = new IllegalStateException();
        runFx(view.controller()::prepare);
        awaitFx(() -> field(view.controller(), "feedbackLabel", Label.class)
                .getText().contains("IllegalStateException"));
        service.snapshotFailure = null;

        service.closeSubscriptionThrows = true;
        runFx(() -> {
            view.close();
            view.controller().prepare();
            service.emitHealth(new Health(HealthStatus.HEALTHY, ""));
            view.close();
        });
        view = null;
        assertTrue(service.subscriptionClosed);
        assertTrue(configChanged.get());
    }

    @Test
    void documentsCoordinateScopesMutationsImportsAndFailures(@TempDir Path directory)
            throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(KnowledgeCenterViewFactory.class)
                .create(null, () -> { }, () -> { }));
        runFx(view.controller()::prepare);
        awaitFx(() -> documentList().getItems().size() == 1);
        KnowledgeDocumentsController documentsController = field(view.controller(),
                "documentsPanelController", KnowledgeDocumentsController.class);
        AtomicReference<Snapshot> accepted = new AtomicReference<>();
        AtomicReference<String> message = new AtomicReference<>();

        Snapshot twoDocuments = snapshot(true, new Health(HealthStatus.HEALTHY, ""),
                service.snapshot.settings(), documents());
        runFx(() -> {
            documentsController.configure(accepted::set, message::set);
            documentsController.apply(twoDocuments);
            documentsController.setScope(Scope.WORKSPACE);
            assertEquals("工作区知识库", label(documentsController, "titleLabel").getText());
            documentsController.setScope(Scope.GLOBAL);
            assertEquals("全局知识库", label(documentsController, "titleLabel").getText());
            documentsController.setScope(Scope.ALL);

            Document global = twoDocuments.documents().get(1);
            invoke(documentsController, "select", new Class<?>[] {Document.class}, global);
            assertEquals("全局", label(documentsController, "detailScopeLabel").getText());
            assertEquals("TXT", label(documentsController, "detailTypeLabel").getText());
            invoke(documentsController, "select", new Class<?>[] {Document.class}, global);
            assertFalse(field(documentsController, "detailDrawer", VBox.class).isVisible());
            invoke(documentsController, "select", new Class<?>[] {Document.class},
                    new Object[] {null});
        });

        Document workspace = twoDocuments.documents().getFirst();
        runFx(() -> invoke(documentsController, "toggle",
                new Class<?>[] {Document.class, boolean.class}, workspace, false));
        awaitFx(() -> service.toggleCalls == 1 && !busy(documentsController));
        assertTrue(message.get().contains("停用"));

        runFx(() -> invoke(documentsController, "setAllEnabled",
                new Class<?>[] {boolean.class}, true));
        awaitFx(() -> service.bulkToggleCalls == 1 && !busy(documentsController));
        runFx(() -> invoke(documentsController, "setAllEnabled",
                new Class<?>[] {boolean.class}, false));
        awaitFx(() -> service.bulkToggleCalls == 2 && !busy(documentsController));

        runFx(() -> invoke(documentsController, "deleteSelected"));
        assertEquals(0, service.deleteCalls);
        interaction.confirmAllowed = false;
        runFx(() -> {
            invoke(documentsController, "select", new Class<?>[] {Document.class}, workspace);
            invoke(documentsController, "deleteSelected");
        });
        awaitFx(() -> !busy(documentsController));
        assertEquals(0, service.deleteCalls);
        interaction.confirmAllowed = true;
        runFx(() -> invoke(documentsController, "deleteSelected"));
        awaitFx(() -> service.deleteCalls == 1 && !busy(documentsController));
        assertTrue(message.get().contains("已删除"));

        service.nextImportResult = new ImportResult(twoDocuments, 2, 0, List.of());
        runFx(() -> invoke(documentsController, "importFiles",
                new Class<?>[] {List.class}, List.of(directory.resolve("a.md"), directory.resolve("b.txt"))));
        awaitFx(() -> service.importFileCalls == 1 && !busy(documentsController));
        assertTrue(message.get().contains("全部导入成功"));

        service.nextImportResult = new ImportResult(twoDocuments, 1, 1, List.of("bad.pdf：损坏"));
        runFx(() -> invoke(documentsController, "importText",
                new Class<?>[] {KnowledgeTextImportDialogFactory.Draft.class},
                new KnowledgeTextImportDialogFactory.Draft("title", "body")));
        awaitFx(() -> service.importTextCalls == 1 && !busy(documentsController));
        assertTrue(message.get().contains("bad.pdf"));

        Path empty = Files.createDirectory(directory.resolve("empty"));
        runFx(() -> invoke(documentsController, "importDirectory",
                new Class<?>[] {Path.class}, empty));
        awaitFx(() -> message.get().contains("没有支持的文档"));

        Path supported = Files.createDirectory(directory.resolve("supported"));
        for (String name : List.of("a.pdf", "b.txt", "c.text", "d.md", "e.markdown",
                "f.log", "g.csv", "h.json", "i.xml", "j.html", "k.htm", "skip.bin")) {
            Files.writeString(supported.resolve(name), "content");
        }
        int importsBeforeDirectory = service.importFileCalls;
        runFx(() -> invoke(documentsController, "importDirectory",
                new Class<?>[] {Path.class}, supported));
        awaitFx(() -> service.importFileCalls > importsBeforeDirectory && !busy(documentsController));

        picker.files = List.of();
        runFx(() -> invoke(documentsController, "importFilesRequested"));
        picker.files = List.of(supported.resolve("a.pdf"));
        runFx(() -> invoke(documentsController, "importFilesRequested"));
        awaitFx(() -> service.importFileCalls > importsBeforeDirectory + 1 && !busy(documentsController));
        picker.directory = Optional.empty();
        runFx(() -> invoke(documentsController, "importDirectoryRequested"));
        picker.directory = Optional.of(supported);
        runFx(() -> invoke(documentsController, "importDirectoryRequested"));
        awaitFx(() -> !busy(documentsController));
        runFx(() -> {
            invoke(documentsController, "importMenuRequested");
            invoke(documentsController, "importMenuRequested");
        });

        service.toggleFailure = new IllegalStateException();
        runFx(() -> invoke(documentsController, "toggle",
                new Class<?>[] {Document.class, boolean.class}, workspace, true));
        awaitFx(() -> message.get().contains("IllegalStateException"));
    }

    @Test
    void settingsAndSearchCoverSuccessCancellationAndFailureStates() throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(KnowledgeCenterViewFactory.class)
                .create(null, () -> { }, () -> { }));
        runFx(view.controller()::prepare);
        awaitFx(() -> documentList().getItems().size() == 1);
        KnowledgeSettingsController settings = field(view.controller(),
                "settingsPanelController", KnowledgeSettingsController.class);
        KnowledgeSearchController search = field(view.controller(),
                "searchPanelController", KnowledgeSearchController.class);
        AtomicReference<Snapshot> accepted = new AtomicReference<>();
        AtomicReference<String> message = new AtomicReference<>();
        AtomicInteger changed = new AtomicInteger();
        AtomicBoolean modelSettings = new AtomicBoolean();

        runFx(() -> {
            settings.configure(accepted::set, changed::incrementAndGet, message::set);
            settings.setOpenModelSettings(() -> modelSettings.set(true));
            settings.openModelSettings();
            assertTrue(modelSettings.get());
            settings.setOpenModelSettings(null);
            settings.openModelSettings();
            for (HealthStatus status : HealthStatus.values()) {
                settings.updateHealth(new Health(status, ""));
            }
            Snapshot blank = snapshot(true, new Health(HealthStatus.UNCONFIGURED, ""),
                    new Settings("Local", "", "", 0, 1, 128, 0), documents());
            settings.apply(blank);
            assertEquals("未配置", label(settings, "modelLabel").getText());
            assertTrue(label(settings, "providerLabel").getText().endsWith("—"));
        });

        int saves = service.saveSettingsCalls;
        runFx(() -> invoke(settings, "saveChunkSettings"));
        assertEquals(saves, service.saveSettingsCalls, "值未变化时不得保存");
        runFx(() -> {
            setField(settings, "applying", true);
            invoke(settings, "saveChunkSettings");
            setField(settings, "applying", false);
            KnowledgeSettingsViewModel model = field(settings,
                    "viewModel", KnowledgeSettingsViewModel.class);
            Settings current = model.settings();
            model.updateSettings(null);
            invoke(settings, "saveChunkSettings");
            model.updateSettings(current);
        });
        assertEquals(saves, service.saveSettingsCalls);

        service.savedSettings = new Settings("OpenAI", "https://example.test", "embed-v4",
                2048, 8, 640, 80);
        runFx(() -> {
            field(settings, "chunkSlider", Slider.class).setValue(640);
            field(settings, "overlapSlider", Slider.class).setValue(80);
            invoke(settings, "saveChunkSettings");
        });
        awaitFx(() -> service.saveSettingsCalls == saves + 1 && !busy(settings));
        assertEquals(1, changed.get());
        assertTrue(message.get().contains("已保存"));

        service.saveSettingsFailure = new IllegalStateException("save offline");
        runFx(() -> {
            field(settings, "chunkSlider", Slider.class).setValue(700);
            invoke(settings, "saveChunkSettings");
        });
        awaitFx(() -> message.get().contains("save offline"));
        service.saveSettingsFailure = null;

        runFx(() -> invoke(settings, "rebuildIndex"));
        awaitFx(() -> service.rebuildCalls == 1 && !busy(settings));
        assertTrue(message.get().contains("重新嵌入"));
        service.rebuildFailure = new IllegalStateException();
        runFx(() -> invoke(settings, "rebuildIndex"));
        awaitFx(() -> message.get().contains("IllegalStateException"));
        service.rebuildFailure = null;

        interaction.confirmAllowed = false;
        runFx(() -> invoke(settings, "clearKnowledge"));
        awaitFx(() -> !busy(settings));
        assertEquals(0, service.clearCalls);
        interaction.confirmAllowed = true;
        runFx(() -> invoke(settings, "clearKnowledge"));
        awaitFx(() -> service.clearCalls == 1 && !busy(settings));
        assertNotNull(accepted.get());
        service.clearFailure = new IllegalStateException("clear denied");
        runFx(() -> invoke(settings, "clearKnowledge"));
        awaitFx(() -> message.get().contains("clear denied"));
        service.clearFailure = null;

        search.configure(message::set);
        runFx(() -> {
            search.apply(snapshot(true, new Health(HealthStatus.HEALTHY, ""),
                    new Settings("OpenAI", "", "", 1024, 2, 512, 64), documents()));
            assertEquals("未配置嵌入模型", label(search, "modelLabel").getText());
            search.apply(service.snapshot);
            textField(search, "queryField").setText("架构");
            invoke(search, "searchRequested");
        });
        awaitFx(() -> service.searchCalls == 1 && !busy(search));
        @SuppressWarnings("unchecked")
        ListView<KnowledgeApplicationService.SearchHit> results =
                field(search, "resultList", ListView.class);
        assertEquals(1, results.getItems().size());

        service.searchFailure = new IllegalStateException();
        runFx(() -> invoke(search, "searchRequested"));
        awaitFx(() -> message.get().contains("IllegalStateException"));
        runFx(() -> search.configure(null));
    }

    private static Snapshot snapshot(
            boolean enabled,
            Health health,
            Settings settings,
            List<Document> documents) {
        return new Snapshot(enabled, "", "研发工作区", settings, health, documents);
    }

    private static List<Document> documents() {
        return List.of(
                new Document("guide.md", Scope.WORKSPACE, true, 3,
                        "2026-08-12 10:00:00", "JavaClaw 架构说明", List.of("预览片段")),
                new Document("README", Scope.GLOBAL, false, 0,
                        "", "全局说明", List.of()));
    }

    private static boolean busy(Object controller) {
        return field(controller, "loadingOverlay", StackPane.class).isVisible();
    }

    private static Label label(Object target, String fieldName) {
        return field(target, fieldName, Label.class);
    }

    private static TextField textField(Object target, String fieldName) {
        return field(target, fieldName, TextField.class);
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

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        service = new FakeService();
        picker = new FakePicker();
        interaction = new FakeInteraction();
        ManagedTaskExecutor executor = new ManagedTaskExecutor();
        FxDispatcher fx = new FxDispatcher();
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
        context.registerBean(KnowledgeImportPicker.class, () -> picker);
        context.registerBean(KnowledgeTextImportDialogFactory.class,
                () -> new KnowledgeTextImportDialogFactory(
                        context.getBean(SpringFxmlLoader.class), fx,
                        new com.javaclaw.app.UIHelper(fx)));
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
        private volatile List<Path> files = List.of();
        private volatile Optional<Path> directory = Optional.empty();

        @Override public List<Path> chooseFiles(javafx.stage.Window owner) { return files; }
        @Override public Optional<Path> chooseDirectory(javafx.stage.Window owner) {
            return directory;
        }
    }

    private static final class FakeInteraction implements UserInteractionPort {
        private volatile boolean confirmAllowed = true;

        @Override public boolean confirm(ConfirmRequest request) { return confirmAllowed; }
        @Override public void notify(ToastRequest request) { }
    }

    private static final class FakeService implements KnowledgeApplicationService {
        private volatile Snapshot snapshot = new Snapshot(true, "", "研发工作区",
                new Settings("OpenAI", "https://example.test", "embed-v3", 1024,
                        6, 512, 64),
                new Health(HealthStatus.HEALTHY, ""),
                List.of(new Document("guide.md", Scope.WORKSPACE, true, 3,
                        "2026-08-12 10:00:00", "JavaClaw 架构说明", List.of("预览片段"))));
        private volatile ImportResult nextImportResult =
                new ImportResult(snapshot, 1, 0, List.of());
        private volatile Settings savedSettings = snapshot.settings();
        private volatile boolean subscriptionClosed;
        private volatile boolean closeSubscriptionThrows;
        private volatile int snapshotCalls;
        private volatile int observeCalls;
        private volatile int searchCalls;
        private volatile int importFileCalls;
        private volatile int importTextCalls;
        private volatile int toggleCalls;
        private volatile int bulkToggleCalls;
        private volatile int deleteCalls;
        private volatile int clearCalls;
        private volatile int rebuildCalls;
        private volatile int saveSettingsCalls;
        private volatile RuntimeException snapshotFailure;
        private volatile RuntimeException searchFailure;
        private volatile RuntimeException importFailure;
        private volatile RuntimeException toggleFailure;
        private volatile RuntimeException clearFailure;
        private volatile RuntimeException rebuildFailure;
        private volatile RuntimeException saveSettingsFailure;
        private volatile HealthListener healthListener;

        @Override public Snapshot snapshot() {
            snapshotCalls++;
            if (snapshotFailure != null) throw snapshotFailure;
            return snapshot;
        }
        @Override public SearchResult search(String query) {
            searchCalls++;
            if (searchFailure != null) throw searchFailure;
            return new SearchResult(query, List.of(new KnowledgeApplicationService.SearchHit(
                    "guide.md", Scope.WORKSPACE, .91, "JavaClaw 架构说明")));
        }
        @Override public ImportResult importFiles(List<Path> files, Scope scope) {
            importFileCalls++;
            if (importFailure != null) throw importFailure;
            return nextImportResult;
        }
        @Override public ImportResult importText(String title, String text, Scope scope) {
            importTextCalls++;
            if (importFailure != null) throw importFailure;
            return nextImportResult;
        }
        @Override public Snapshot setDocumentEnabled(String name, boolean enabled) {
            toggleCalls++;
            if (toggleFailure != null) throw toggleFailure;
            return snapshot;
        }
        @Override public Snapshot setAllEnabled(boolean enabled, Scope scope) {
            bulkToggleCalls++;
            if (toggleFailure != null) throw toggleFailure;
            return snapshot;
        }
        @Override public Snapshot deleteDocument(String name) {
            deleteCalls++;
            return snapshot;
        }
        @Override public Snapshot clear() {
            clearCalls++;
            if (clearFailure != null) throw clearFailure;
            return snapshot;
        }
        @Override public ReindexResult rebuildIndex() {
            rebuildCalls++;
            if (rebuildFailure != null) throw rebuildFailure;
            return new ReindexResult(snapshot, 3);
        }
        @Override public Settings saveChunkSettings(int chunkSize, int chunkOverlap) {
            saveSettingsCalls++;
            if (saveSettingsFailure != null) throw saveSettingsFailure;
            return savedSettings;
        }
        @Override public AutoCloseable observeHealth(HealthListener listener) {
            observeCalls++;
            healthListener = listener;
            return () -> {
                subscriptionClosed = true;
                if (closeSubscriptionThrows) throw new IllegalStateException("close failure");
            };
        }

        private void emitHealth(Health health) {
            HealthListener current = healthListener;
            if (current != null) current.onHealthChanged(health);
        }
    }
}
