package com.javaclaw.ui.javafx.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.settings.ModelSettingsApplicationService;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService.DiscoveryRequest;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService.DiscoveryResult;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService.ModelOption;
import com.javaclaw.application.settings.ModelSettingsApplicationService.EmbeddingSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ModelSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ProbeResult;
import com.javaclaw.application.settings.ModelSettingsApplicationService.Snapshot;
import com.javaclaw.application.settings.ModelSettingsApplicationService.Tier;
import com.javaclaw.application.settings.ModelSettingsApplicationService.TierSettings;
import com.javaclaw.application.settings.ModelSettingsPort;
import com.javaclaw.application.settings.ModelSettingsProbePort;
import com.javaclaw.application.settings.ModelSettingsUseCase;
import com.javaclaw.application.settings.DefaultModelProviderCatalog;
import com.javaclaw.application.settings.ModelProviderCatalog;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.desktop.ExternalDirectoryOpener;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.runtime.WorkspaceContext;
import com.javaclaw.application.workspace.WorkspaceApplicationService;
import com.javaclaw.testsupport.EmptyInferenceManagementService;
import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.ui.javafx.plugin.PluginCenterViewFactory;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.Parent;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class SettingsCoreFxmlLoadTest {
    private static final long TIMEOUT_SECONDS = 5;

    private final List<SettingsSectionView<?>> views = new ArrayList<>();
    private final List<ViewHandle<Parent>> inferenceViews = new ArrayList<>();
    private AnnotationConfigApplicationContext context;
    private FakeSettingsPort settings;
    private FakeDiscovery discovery;

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
        runFx(() -> {
            for (int index = inferenceViews.size() - 1; index >= 0; index--) {
                inferenceViews.get(index).close();
            }
            inferenceViews.clear();
            for (int index = views.size() - 1; index >= 0; index--) views.get(index).close();
            views.clear();
        });
        if (context != null) context.close();
    }

    @Test
    void allSectionsLoadIncludesAndPopulateThePersistedSnapshot() throws Exception {
        prepareContext();
        ModelSettingsSectionFactory factory = context.getBean(ModelSettingsSectionFactory.class);
        var model = add(callFx(() -> factory.createModel(ignored -> { })));
        var tiers = add(callFx(() -> factory.createTiers(ignored -> { })));
        var embedding = add(callFx(() -> factory.createEmbedding(ignored -> { })));

        runFx(() -> {
            render(model);
            render(tiers);
            render(embedding);
            model.controller().reload();
            tiers.controller().reload();
            embedding.controller().reload();
        });

        awaitFx(() -> "chat-model".equals(modelText(model, "modelNameField").getText())
                && "normal-model".equals(modelText(tiers, "normalModelNameField").getText())
                && "embedding-model".equals(modelText(embedding, "modelNameField").getText()));
        assertEquals("chat-model", callFx(() -> modelText(model, "modelNameField").getText()));
        assertEquals("normal-model", callFx(() -> modelText(tiers, "normalModelNameField").getText()));
        assertEquals("embedding-model", callFx(() -> modelText(embedding, "modelNameField").getText()));
        PasswordField secret = callFx(() ->
                (PasswordField) model.root().lookup("#secretField"));
        assertNotNull(secret, "fx:include 应加载可复用密钥控件");
        assertEquals("model-key", callFx(secret::getText));

        runFx(model::close);
        views.remove(model);
        assertEquals("", callFx(secret::getText), "页面关闭必须清除嵌套控件中的密钥");
    }

    @Test
    void modelControllerSavesAsynchronouslyAndKeepsValidationFailuresOutOfStorage()
            throws Exception {
        prepareContext();
        AtomicInteger applied = new AtomicInteger();
        var model = add(callFx(() -> context.getBean(ModelSettingsSectionFactory.class)
                .createModel(ignored -> applied.incrementAndGet())));
        runFx(() -> { render(model); model.controller().reload(); });
        awaitFx(() -> "chat-model".equals(modelText(model, "modelNameField").getText()));
        AtomicReference<Throwable> failure = new AtomicReference<>();

        runFx(() -> {
            modelText(model, "modelNameField").setText("updated-model");
            model.controller().save(ignored -> { }, failure::set);
        });
        awaitFx(() -> settings.modelSaves == 1 && applied.get() == 1);
        assertEquals("updated-model", settings.snapshot.model().modelName());

        runFx(() -> {
            text(model, "baseUrlField").setText("file:///tmp/not-http");
            model.controller().save(ignored -> { }, failure::set);
        });
        awaitFx(() -> failure.get() != null);
        assertEquals(1, settings.modelSaves, "校验失败不得写入配置");
        assertEquals("updated-model", settings.snapshot.model().modelName());
    }

    @Test
    void modelChoicesPreserveManualTextAndIgnoreCancelledRequests() throws Exception {
        prepareContext();
        var model = add(callFx(() -> context.getBean(ModelSettingsSectionFactory.class)
                .createModel(ignored -> { })));
        runFx(() -> { render(model); model.controller().reload(); });
        awaitFx(() -> "chat-model".equals(modelText(model, "modelNameField").getText()));

        runFx(() -> {
            modelText(model, "modelNameField").setText("my-custom-model");
            text(model, "baseUrlField").setText("https://slow.example/v1");
        });
        awaitFx(() -> discovery.requests.stream()
                .anyMatch(request -> request.baseUrl().contains("slow.example")));
        runFx(() -> text(model, "baseUrlField").setText("https://fast.example/v1"));
        awaitFx(() -> discovery.requests.stream()
                .anyMatch(request -> request.baseUrl().contains("fast.example"))
                && "已加载".equals(((Label) model.root()
                        .lookup("#modelDiscoveryStatusLabel")).getText()));

        assertEquals("my-custom-model", callFx(() -> modelText(model, "modelNameField").getText()));
        assertTrue(callFx(() -> combo(model, "modelNameField").getItems().isEmpty()),
                "候选项应按手动输入的搜索文字过滤");
        runFx(() -> modelText(model, "modelNameField").setText("fast"));
        assertTrue(callFx(() -> combo(model, "modelNameField").getItems().contains("fast-model")));
        runFx(() -> modelText(model, "modelNameField").setText("my-custom-model"));
        assertFalse(callFx(() -> combo(model, "modelNameField").getItems().contains("listed-model")));
        assertTrue(discovery.interruptions.get() > 0, "修改 URL 应取消旧请求");
        runFx(() -> ((javafx.scene.control.Button) model.root()
                .lookup("#modelDiscoveryRefreshButton")).fire());
        awaitFx(() -> discovery.requests.stream()
                .filter(request -> request.baseUrl().contains("fast.example")).count() >= 2);
        PasswordField key = callFx(() -> (PasswordField) model.root().lookup("#secretField"));
        runFx(() -> { key.setText("key-one"); key.setText("key-two"); });
        awaitFx(() -> discovery.requests.stream()
                .anyMatch(request -> "key-two".equals(request.apiKey())));
        assertFalse(discovery.requests.stream()
                .anyMatch(request -> "key-one".equals(request.apiKey())));
    }

    @Test
    void modelChoiceCellClearsWhenOptionsAreReset() throws Exception {
        prepareContext();
        var model = add(callFx(() -> context.getBean(ModelSettingsSectionFactory.class)
                .createModel(ignored -> { })));
        runFx(() -> { render(model); model.controller().reload(); });
        awaitFx(() -> combo(model, "modelNameField").getItems().contains("listed-model"));

        runFx(() -> {
            ComboBox<String> modelCombo = combo(model, "modelNameField");
            ListView<String> list = new ListView<>(modelCombo.getItems());
            ListCell<String> cell = modelCombo.getCellFactory().call(list);
            cell.updateListView(list);
            cell.updateIndex(0);
            assertEquals("listed-model", cell.getText());

            text(model, "baseUrlField").setText("https://next.example/v1");
            assertTrue(modelCombo.getItems().isEmpty());
            cell.updateIndex(-1);
            assertNull(cell.getText(), "空候选项不得读取名称映射或保留旧文本");
        });
    }

    @Test
    void providerSwitchResetsModelsWithoutSendingPreviousCredentials() throws Exception {
        prepareContext();
        ModelSettingsSectionFactory factory = context.getBean(ModelSettingsSectionFactory.class);
        var model = add(callFx(() -> factory.createModel(ignored -> { })));
        var embedding = add(callFx(() -> factory.createEmbedding(ignored -> { })));
        runFx(() -> {
            render(model);
            render(embedding);
            model.controller().reload();
            embedding.controller().reload();
        });
        awaitFx(() -> "chat-model".equals(modelText(model, "modelNameField").getText())
                && "embedding-model".equals(modelText(embedding, "modelNameField").getText()));

        runFx(() -> {
            combo(model, "providerCombo").setValue("Anthropic");
            combo(embedding, "providerCombo").setValue("DashScope");
        });
        awaitFx(() -> discovery.requests.stream().anyMatch(request ->
                "anthropic".equals(request.provider())
                        && "https://api.anthropic.com".equals(request.baseUrl()))
                && discovery.requests.stream().anyMatch(request ->
                "dashscope".equals(request.provider())
                        && request.baseUrl().contains("dashscope.aliyuncs.com")));
        assertEquals("claude-haiku-4-5-20251001",
                callFx(() -> modelText(model, "modelNameField").getText()));
        assertEquals("text-embedding-v3",
                callFx(() -> modelText(embedding, "modelNameField").getText()));
        assertTrue(discovery.requests.stream().filter(request ->
                "anthropic".equals(request.provider())).allMatch(request -> request.apiKey().isBlank()));
        assertTrue(discovery.requests.stream().filter(request ->
                "dashscope".equals(request.provider())).allMatch(request -> request.apiKey().isBlank()));
        assertEquals("", callFx(() -> ((PasswordField) model.root()
                .lookup("#apiKeyRow").lookup("#secretField")).getText()));
        assertEquals("", callFx(() -> ((PasswordField) embedding.root()
                .lookup("#apiKeyRow").lookup("#secretField")).getText()));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        runFx(() -> model.controller().save(ignored -> { }, failure::set));
        awaitFx(() -> "anthropic".equalsIgnoreCase(settings.snapshot.model().provider())
                || failure.get() != null);
        assertNull(failure.get());
        runFx(() -> embedding.controller().save(ignored -> { }, failure::set));
        awaitFx(() -> "dashscope".equalsIgnoreCase(settings.snapshot.embedding().provider())
                || failure.get() != null);
        assertNull(failure.get());
        assertEquals("", settings.snapshot.model().apiKey());
        assertEquals("", settings.snapshot.embedding().apiKey());
        runFx(() -> { model.controller().reload(); embedding.controller().reload(); });
        awaitFx(() -> "claude-haiku-4-5-20251001".equals(
                modelText(model, "modelNameField").getText()));

        PasswordField key = callFx(() -> (PasswordField) model.root()
                .lookup("#apiKeyRow").lookup("#secretField"));
        runFx(() -> {
            key.setText("anthropic-key");
            text(model, "baseUrlField").setText("https://api.anthropic.com/v1");
        });
        awaitFx(() -> discovery.requests.stream().anyMatch(request ->
                "https://api.anthropic.com/v1".equals(request.baseUrl())
                        && "anthropic-key".equals(request.apiKey())));
        runFx(() -> text(model, "baseUrlField").setText("https://other.example/v1"));
        awaitFx(() -> discovery.requests.stream().anyMatch(request ->
                "https://other.example/v1".equals(request.baseUrl())));
        assertTrue(discovery.requests.stream().filter(request ->
                "https://other.example/v1".equals(request.baseUrl()))
                .allMatch(request -> request.apiKey().isBlank()));
        assertEquals("", callFx(key::getText));
        runFx(() -> model.controller().save(ignored -> { }, failure::set));
        awaitFx(() -> "https://other.example/v1".equals(settings.snapshot.model().baseUrl())
                || failure.get() != null);
        assertNull(failure.get());
        assertEquals("", settings.snapshot.model().apiKey());
        runFx(model.controller()::reload);
        awaitFx(() -> "https://other.example/v1".equals(text(model, "baseUrlField").getText()));
        assertEquals("", callFx(key::getText));

        PasswordField embeddingKey = callFx(() -> (PasswordField) embedding.root()
                .lookup("#apiKeyRow").lookup("#secretField"));
        runFx(() -> {
            embeddingKey.setText("dashscope-key");
            text(embedding, "baseUrlField").setText("https://embedding.example/v1");
        });
        assertEquals("", callFx(embeddingKey::getText));
        runFx(() -> embedding.controller().save(ignored -> { }, failure::set));
        awaitFx(() -> "https://embedding.example/v1".equals(
                settings.snapshot.embedding().baseUrl()) || failure.get() != null);
        assertNull(failure.get());
        assertEquals("", settings.snapshot.embedding().apiKey());
        runFx(embedding.controller()::reload);
        awaitFx(() -> "https://embedding.example/v1".equals(
                text(embedding, "baseUrlField").getText()));
        assertEquals("", callFx(embeddingKey::getText));
    }

    @Test
    void tierDiscoveryInheritsHighCredentialOnlyAtTheSameProviderAndOrigin() throws Exception {
        prepareContext();
        var tiers = add(callFx(() -> context.getBean(ModelSettingsSectionFactory.class)
                .createTiers(ignored -> { })));
        runFx(() -> { render(tiers); tiers.controller().reload(); });
        awaitFx(() -> "normal-model".equals(modelText(tiers, "normalModelNameField").getText()));

        runFx(() -> combo(tiers, "normalProviderCombo").setValue("Anthropic"));
        awaitFx(() -> discovery.requests.stream().anyMatch(request ->
                "anthropic".equals(request.provider())
                        && "https://api.anthropic.com".equals(request.baseUrl())));
        assertEquals("claude-haiku-4-5-20251001",
                callFx(() -> modelText(tiers, "normalModelNameField").getText()));
        assertTrue(discovery.requests.stream().filter(request ->
                "anthropic".equals(request.provider())).allMatch(request -> request.apiKey().isBlank()));
        PasswordField normalKey = callFx(() -> (PasswordField) tiers.root()
                .lookup("#normalApiKeyRow").lookup("#secretField"));
        assertEquals("", callFx(normalKey::getText));
        runFx(() -> {
            normalKey.setText("anthropic-key");
            text(tiers, "normalBaseUrlField").setText("https://different.example/v1");
        });
        assertEquals("", callFx(normalKey::getText));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runFx(() -> tiers.controller().save(ignored -> { }, failure::set));
        awaitFx(() -> "https://different.example/v1".equals(
                settings.snapshot.tiers().normal().baseUrl()) || failure.get() != null);
        assertNull(failure.get());
        assertEquals("", settings.snapshot.tiers().normal().apiKey());
        runFx(tiers.controller()::reload);
        awaitFx(() -> "https://different.example/v1".equals(
                text(tiers, "normalBaseUrlField").getText()));

        runFx(() -> {
            ((ToggleSwitch) tiers.root().lookup("#lightEnabledCheck")).setSelected(true);
            combo(tiers, "lightProviderCombo").setValue("OpenAI");
            text(tiers, "lightBaseUrlField").clear();
        });
        awaitFx(() -> discovery.requests.stream().anyMatch(request ->
                "openai".equals(request.provider())
                        && "https://api.example/v1".equals(request.baseUrl())
                        && "model-key".equals(request.apiKey())));

        PasswordField lightKey = callFx(() -> (PasswordField) tiers.root()
                .lookup("#lightApiKeyRow").lookup("#secretField"));
        runFx(() -> lightKey.setText("light-key"));
        runFx(() -> combo(tiers, "lightProviderCombo").setValue("DashScope"));
        awaitFx(() -> discovery.requests.stream().anyMatch(request ->
                "dashscope".equals(request.provider())
                        && request.baseUrl().contains("dashscope.aliyuncs.com")));
        assertEquals("qwen-turbo",
                callFx(() -> modelText(tiers, "lightModelNameField").getText()));
        assertTrue(discovery.requests.stream().filter(request ->
                "dashscope".equals(request.provider())).allMatch(request -> request.apiKey().isBlank()));
        assertEquals("", callFx(lightKey::getText));
    }

    @Test
    void typingAnUnlistedModelMarksTheSettingsSectionDirty() throws Exception {
        prepareContext();
        var model = add(callFx(() -> context.getBean(ModelSettingsSectionFactory.class)
                .createModel(ignored -> { })));
        runFx(() -> { render(model); model.controller().reload(); });
        awaitFx(() -> "chat-model".equals(modelText(model, "modelNameField").getText()));
        AtomicInteger changes = new AtomicInteger();
        SettingsDirtyTracker tracker = new SettingsDirtyTracker();
        try {
            runFx(() -> {
                model.controller().deactivate();
                combo(model, "modelNameField").getItems().clear();
                tracker.watch(model.root(), changes::incrementAndGet);
                modelText(model, "modelNameField").setText("my-unlisted-model");
            });
            assertTrue(changes.get() > 0,
                    "手输列表外模型时应通知设置页启用保存按钮");
        } finally {
            runFx(tracker::close);
        }
    }

    @Test
    void tierAndEmbeddingEditableModelsSaveReloadAndInheritHighCredentials() throws Exception {
        prepareContext();
        ModelSettingsSectionFactory factory = context.getBean(ModelSettingsSectionFactory.class);
        var tiers = add(callFx(() -> factory.createTiers(ignored -> { })));
        var embedding = add(callFx(() -> factory.createEmbedding(ignored -> { })));
        runFx(() -> {
            render(tiers);
            render(embedding);
            tiers.controller().reload();
            embedding.controller().reload();
        });
        awaitFx(() -> "normal-model".equals(modelText(tiers, "normalModelNameField").getText())
                && "embedding-model".equals(modelText(embedding, "modelNameField").getText()));
        awaitFx(() -> discovery.requests.stream().anyMatch(request ->
                "normal-key".equals(request.apiKey())
                        && "https://api.example/v1".equals(request.baseUrl())));
        AtomicReference<Throwable> failure = new AtomicReference<>();

        runFx(() -> {
            modelText(tiers, "normalModelNameField").setText("custom-normal");
            ((ToggleSwitch) tiers.root().lookup("#lightEnabledCheck")).setSelected(true);
            combo(tiers, "lightProviderCombo").setValue("OpenAI");
            text(tiers, "lightBaseUrlField").clear();
            modelText(tiers, "lightModelNameField").setText("custom-light");
            tiers.controller().save(ignored -> { }, failure::set);
        });
        awaitFx(() -> "custom-light".equals(settings.snapshot.tiers().light().modelName())
                || failure.get() != null);
        assertNull(failure.get());
        runFx(() -> {
            modelText(embedding, "modelNameField").setText("custom-embedding");
            embedding.controller().save(ignored -> { }, failure::set);
        });
        awaitFx(() -> "custom-embedding".equals(settings.snapshot.embedding().modelName())
                || failure.get() != null);
        assertNull(failure.get());

        runFx(() -> { tiers.controller().reload(); embedding.controller().reload(); });
        awaitFx(() -> "custom-normal".equals(modelText(tiers, "normalModelNameField").getText())
                && "custom-light".equals(modelText(tiers, "lightModelNameField").getText())
                && "custom-embedding".equals(modelText(embedding, "modelNameField").getText()));
    }

    @Test
    void deliveranceModelAndServiceConsoleFxmlLoadsThroughSpringControllers() throws Exception {
        prepareContext();
        SpringFxmlLoader loader = context.getBean(SpringFxmlLoader.class);
        for (String resource : List.of(
                "/fxml/settings/local-inference-assets.fxml",
                "/fxml/settings/local-inference-profiles.fxml",
                "/fxml/settings/local-inference-api.fxml")) {
            ViewHandle<Parent> handle = callFx(() -> loader.load(
                    SettingsCoreFxmlLoadTest.class.getResource(resource)));
            inferenceViews.add(handle);
            runFx(() -> render(handle.root()));
            assertNotNull(callFx(handle::primaryController));
        }
        assertNotNull(callFx(() -> inferenceViews.get(0).root().lookup("#onlineModelList")));
        assertNotNull(callFx(() -> inferenceViews.get(0).root().lookup("#modelSourceTabs")));
        assertNotNull(callFx(() -> inferenceViews.get(0).root().lookup("#onlineModelTitleLabel")));
        assertNotNull(callFx(() -> inferenceViews.get(1).root().lookup("#parametersPanel")));
        assertNotNull(callFx(() -> inferenceViews.get(2).root().lookup("#ejectServiceModelButton")));
        assertNotNull(callFx(() -> inferenceViews.get(2).root().lookup("#serviceModelIdentifierField")));
        assertNotNull(callFx(() -> inferenceViews.get(2).root().lookup("#routesArea")));
        assertEquals(330.0, callFx(() -> ((javafx.scene.layout.Region) inferenceViews.get(2)
                .root().lookup(".service-plugin-service-split")).getMaxHeight()));
        assertNotNull(callFx(() -> ((javafx.scene.control.ListView<?>) inferenceViews.get(2)
                .root().lookup("#serviceModelList")).getCellFactory()));
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        settings = new FakeSettingsPort(snapshot());
        discovery = new FakeDiscovery();
        context.registerBean(ModelSettingsPort.class, () -> settings);
        context.registerBean(ModelProviderCatalog.class, DefaultModelProviderCatalog::new);
        context.registerBean(InferenceManagementApplicationService.class,
                EmptyInferenceManagementService::new);
        context.registerBean(ObjectMapper.class,
                () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(WorkspaceApplicationService.class, EmptyWorkspaces::new);
        Path root = Path.of("target", "settings-core-fxml").toAbsolutePath();
        context.registerBean(WorkspaceContext.class, () -> new WorkspaceContext(
                "test", root, root, root.resolve("browser"), root.resolve("screens"),
                root.resolve("logs")));
        context.registerBean(ModelSettingsProbePort.class, FakeProbePort::new);
        context.registerBean(ModelDiscoveryApplicationService.class, () -> discovery);
        context.registerBean(ModelSettingsApplicationService.class, () -> new ModelSettingsUseCase(
                context.getBean(ModelSettingsPort.class),
                context.getBean(ModelSettingsProbePort.class)));
        context.registerBean(UserInteractionPort.class, AllowInteraction::new);
        context.registerBean(DialogService.class,
                () -> new DialogService(context.getBean(UserInteractionPort.class)));
        context.registerBean(ManagedTaskExecutor.class, () -> new ManagedTaskExecutor(),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(ExternalDirectoryOpener.class,
                () -> new ExternalDirectoryOpener(context.getBean(ManagedTaskExecutor.class)));
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(ModelSettingsSectionFactory.class,
                () -> new ModelSettingsSectionFactory(context.getBean(SpringFxmlLoader.class)));
        context.registerBean(PluginCenterViewFactory.class,
                () -> new PluginCenterViewFactory(context.getBean(SpringFxmlLoader.class)));
        context.refresh();
    }

    private <T extends SettingsSectionView<?>> T add(T view) {
        views.add(view);
        return view;
    }

    private static void render(SettingsSectionView<?> view) {
        new Scene((javafx.scene.Parent) view.root(), 800, 620);
        view.root().applyCss();
        view.root().autosize();
    }

    private static void render(Parent root) {
        new Scene(root, 900, 700);
        root.applyCss();
        root.autosize();
    }

    private static TextField text(SettingsSectionView<?> view, String id) {
        return (TextField) view.root().lookup("#" + id);
    }

    @SuppressWarnings("unchecked")
    private static TextField modelText(SettingsSectionView<?> view, String id) {
        return ((ComboBox<String>) view.root().lookup("#" + id)).getEditor();
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<String> combo(SettingsSectionView<?> view, String id) {
        return (ComboBox<String>) view.root().lookup("#" + id);
    }

    private static Snapshot snapshot() {
        return new Snapshot(
                new ModelSettings("OpenAI", "https://api.example/v1", "chat-model",
                        "model-key", true, 4096, "HTTP_2", 10, 120, 30,
                        30, 15, 15, 5, 0.92, 4.0, 2),
                new TierSettings(
                        new Tier(true, "OpenAI", "", "normal-model", "normal-key", false),
                        new Tier(false, "", "", "", "", false)),
                new EmbeddingSettings(true, "OpenAI", "https://api.example/v1",
                        "embedding-key", "embedding-model", 1024, 5, 0.35),
                "fake-config.json");
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

    private static final class FakeSettingsPort implements ModelSettingsPort {
        private volatile Snapshot snapshot;
        private volatile int modelSaves;

        private FakeSettingsPort(Snapshot snapshot) { this.snapshot = snapshot; }
        @Override public Snapshot load() { return snapshot; }
        @Override public void saveModel(ModelSettings value) {
            modelSaves++;
            snapshot = new Snapshot(value, snapshot.tiers(), snapshot.embedding(),
                    snapshot.storageDescription());
        }
        @Override public void resetModel() { }
        @Override public void saveTiers(TierSettings value) {
            snapshot = new Snapshot(snapshot.model(), value, snapshot.embedding(),
                    snapshot.storageDescription());
        }
        @Override public void saveEmbedding(EmbeddingSettings value) {
            snapshot = new Snapshot(snapshot.model(), snapshot.tiers(), value,
                    snapshot.storageDescription());
        }
    }

    private static final class EmptyWorkspaces implements WorkspaceApplicationService {
        @Override public List<WorkspaceSummary> list() { return List.of(); }
        @Override public String currentWorkspaceId() { return "test"; }
        @Override public WorkspaceSummary create(String name) {
            return new WorkspaceSummary("test", name, "");
        }
        @Override public boolean delete(String workspaceId) { return false; }
    }

    private static final class FakeProbePort implements ModelSettingsProbePort {
        @Override public ProbeResult probeModel(ModelSettings settings) {
            return new ProbeResult(true, "ok");
        }
        @Override public ProbeResult probeEmbedding(
                EmbeddingSettings form, EmbeddingSettings persisted) {
            return new ProbeResult(true, "ok");
        }
    }

    private static final class FakeDiscovery implements ModelDiscoveryApplicationService {
        private final List<DiscoveryRequest> requests = new CopyOnWriteArrayList<>();
        private final AtomicInteger interruptions = new AtomicInteger();

        @Override public DiscoveryResult discover(DiscoveryRequest request)
                throws InterruptedException {
            requests.add(request);
            if (request.baseUrl().contains("slow.example")) {
                try { Thread.sleep(5_000); }
                catch (InterruptedException cancelled) {
                    interruptions.incrementAndGet();
                    throw cancelled;
                }
            }
            String name = request.baseUrl().contains("fast.example") ? "fast-model" : "listed-model";
            return new DiscoveryResult(true, List.of(new ModelOption(name, name)), "已加载");
        }
    }

    private static final class AllowInteraction implements UserInteractionPort {
        @Override public boolean confirm(ConfirmRequest request) { return true; }
        @Override public void notify(ToastRequest request) { }
    }
}
