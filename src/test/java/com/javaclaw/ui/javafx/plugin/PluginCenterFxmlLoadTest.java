package com.javaclaw.ui.javafx.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.plugin.AgentExtensionManagementApplicationService;
import com.javaclaw.application.plugin.AgentExtensionManagementApplicationService.AgentExtension;
import com.javaclaw.application.plugin.AgentExtensionManagementApplicationService.InstallPreview;
import com.javaclaw.application.plugin.PluginManagementApplicationService;
import com.javaclaw.application.plugin.PluginManagementApplicationService.ApprovalResult;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ServicePluginInfo;
import com.javaclaw.application.inference.InferenceAssetPreparationPort;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.inference.HuggingFaceModelCatalogPort;
import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.application.inference.InferenceRuntimePort;
import com.javaclaw.application.inference.InferenceSystemProfilePort;
import com.javaclaw.application.workspace.WorkspaceApplicationService;
import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.inference.api.InferenceRuntimeManifest;
import com.javaclaw.platform.desktop.ExternalDirectoryOpener;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.testsupport.EmptyInferenceManagementService;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import com.javaclaw.ui.javafx.settings.InferencePluginConfigurationFactory;
import com.javaclaw.plugin.api.PluginDescriptor;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.URL;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class PluginCenterFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private ViewHandle<HBox> handle;
    private FakePluginService service;
    private FakeAgentExtensionService extensionService;
    private FakeServicePluginService servicePluginService;
    private RecordingInferenceService inferenceService;
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
            Platform.runLater(() -> {
                Platform.setImplicitExit(false);
                started.countDown();
            });
        }
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (handle != null) runFx(handle::close);
        if (context != null) context.close();
    }

    @Test
    void rendersCardsDetailsDynamicRowsAndConfiguration() throws Exception {
        loadView(Optional.empty());
        awaitFx(() -> cardGrid().getChildren().size() == 2);

        runFx(() -> text("searchField").setText("search"));
        awaitFx(() -> cardGrid().getChildren().size() == 1);
        assertEquals("Beta Search", callFx(() ->
                label(cardGrid().getChildren().getFirst(), "nameLabel").getText()));

        runFx(() -> text("searchField").clear());
        awaitFx(() -> cardGrid().getChildren().size() == 2);
        runFx(() -> {
            HBox header = (HBox) cardGrid().getChildren().getFirst().lookup("#headerRow");
            header.getOnMouseClicked().handle(null);
        });
        awaitFx(() -> "Alpha Plugin".equals(detailLabel("nameLabel").getText()));

        assertEquals(1, callFx(() -> detailBox("permissionsBox").getChildren().size()));
        assertEquals(1, callFx(() -> detailBox("skillsItems").getChildren().size()));
        assertEquals(1, callFx(() -> detailBox("toolsItems").getChildren().size()));
        assertEquals(2, callFx(() -> detailBox("configFields").getChildren().size()));

        runFx(() -> detailToggle().setSelected(false));
        awaitFx(() -> service.toggleCalls == 1);
        assertFalse(service.catalog().require("alpha").active());

        runFx(() -> {
            VBox firstField = (VBox) detailBox("configFields").getChildren().getFirst();
            ((TextField) firstField.lookup("#textField")).setText("changed");
            detailButton("saveConfigButton").fire();
        });
        awaitFx(() -> service.savedConfig != null);
        assertEquals("changed", service.savedConfig.get("endpoint"));
    }

    @Test
    void installsUninstallsAndReleasesWindowSubscription() throws Exception {
        loadView(Optional.of(Path.of("/tmp/installed.jar")));
        awaitFx(() -> cardGrid().getChildren().size() == 2);

        runFx(() -> button("installButton").fire());
        awaitFx(() -> "Installed Plugin".equals(detailLabel("nameLabel").getText()));
        assertEquals(3, service.catalog().plugins().size());

        runFx(() -> detailButton("uninstallButton").fire());
        awaitFx(() -> service.catalog().plugins().size() == 2);
        awaitFx(() -> ((VBox) handle.root().lookup("#listView")).isVisible());
        assertTrue(interaction.confirmed);

        PluginCenterController controller = handle.controller(PluginCenterController.class);
        runFx(handle::close);
        handle = null;
        assertTrue(controller.isClosed());
        assertTrue(service.subscriptionClosed);
    }

    @Test
    void factoryPreservesModalWindowContract() throws Exception {
        prepareContext(Optional.empty());
        PluginCenterViewFactory factory = new PluginCenterViewFactory(
                context.getBean(SpringFxmlLoader.class));
        PluginCenterView view = callFx(() -> factory.create(null));
        PluginCenterController controller = callFx(view::controller);

        runFx(view::show);
        assertEquals("插件中心", callFx(() -> view.stage().getTitle()));
        assertEquals(960.0, callFx(() -> view.stage().getScene().getWidth()));
        assertEquals(680.0, callFx(() -> view.stage().getScene().getHeight()));
        assertTrue(callFx(() -> view.stage().isShowing()));

        runFx(view::close);
        assertTrue(controller.isClosed());
        assertTrue(service.subscriptionClosed);
    }

    @Test
    void previewsInstallsRefreshesAndTogglesAgentExtensions() throws Exception {
        Path selectedJar = Path.of("/tmp/agent-extension.jar");
        loadView(Optional.of(selectedJar));
        awaitFx(() -> cardGrid().getChildren().size() == 2);

        runFx(() -> toggleButton("agentExtensionsTab").fire());
        awaitFx(() -> extensionList().getChildren().size() == 1);
        assertTrue(callFx(() -> extensionView().isVisible()));
        assertEquals("1 个 Agent 扩展", callFx(() -> extensionCount().getText()));

        runFx(() -> extensionToggle(0).fire());
        awaitFx(() -> extensionService.toggleCalls == 1
                && !extensionService.require("agent.alpha").enabledForNewRuns());
        assertEquals("启用", callFx(() -> extensionToggle(0).getText()));

        runFx(() -> extensionToggle(0).fire());
        awaitFx(() -> extensionService.toggleCalls == 2
                && extensionService.require("agent.alpha").enabledForNewRuns());
        assertEquals("停用", callFx(() -> extensionToggle(0).getText()));

        int listCalls = extensionService.installedCalls;
        runFx(() -> button("refreshButton").fire());
        awaitFx(() -> extensionService.installedCalls > listCalls);

        runFx(() -> button("installAgentExtensionButton").fire());
        awaitFx(() -> extensionService.installCalls == 1
                && extensionList().getChildren().size() == 2);
        assertEquals(selectedJar, extensionService.previewPath);
        assertEquals(extensionService.lastPreview, extensionService.installedPreview);
        assertTrue(interaction.confirmed);
        assertEquals("2 个 Agent 扩展", callFx(() -> extensionCount().getText()));
        awaitFx(() -> handle.root().lookupAll("#statusLabel").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .anyMatch(label -> label.getText().contains("已安装并启用")));
    }

    @Test
    void servicePluginsLoadOnlyWhenTheirTabIsOpenedAndCanBeStarted() throws Exception {
        loadView(Optional.empty());
        awaitFx(() -> cardGrid().getChildren().size() == 2);
        assertEquals(0, servicePluginService.listCalls);

        runFx(() -> toggleButton("servicePluginsTab").fire());
        awaitFx(() -> servicePluginService.listCalls > 0
                && servicePluginList().getChildren().size() == 1);
        VBox row = (VBox) servicePluginList().getChildren().getFirst();
        assertTrue(((Label) row.lookup("#servicePluginMetadata")).getText()
                .contains("/tmp/plugins/deliverance/deliverance.jar"));
        assertTrue(((Label) row.lookup("#servicePluginDescription")).getText().contains("Deliverance"));
        Button start = (Button) ((HBox) row.lookup("#servicePluginActions"))
                .getChildren().getFirst();
        runFx(start::fire);
        awaitFx(() -> servicePluginService.startCalls == 1);

        VBox runningRow = callFx(() -> (VBox) servicePluginList().getChildren().getFirst());
        Button configure = callFx(() -> runningRow.lookupAll(".button").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> "配置与日志".equals(button.getText())).findFirst().orElseThrow());
        runFx(configure::fire);
        awaitFx(() -> handle.root().lookup("#servicePluginPageTab-host-runtime") != null);
        assertFalse(callFx(() -> servicePluginView().isVisible()));
        assertTrue(callFx(() -> handle.root().lookup(".service-plugin-configuration-page").isVisible()));
        runFx(() -> ((ToggleButton) handle.root()
                .lookup("#servicePluginPageTab-host-runtime")).fire());
        TextField heap = callFx(() -> (TextField) handle.root().lookup("#serviceHeapMiBField"));
        assertEquals("4096", heap.getText());
        runFx(() -> {
            heap.setText("5120");
            ((Button) handle.root().lookup("#servicePluginSaveResources")).fire();
        });
        awaitFx(() -> servicePluginService.configurationCalls == 1);
        assertEquals(5120, servicePluginService.resources.heapMiB());
        runFx(() -> ((Button) handle.root().lookup("#servicePluginConfigBack")).fire());
        awaitFx(() -> servicePluginView().isVisible());
    }

    @Test
    void servicePluginEmptyStateExplainsRuntimeDiscoveryAndCanRetry() throws Exception {
        loadView(Optional.empty());
        awaitFx(() -> cardGrid().getChildren().size() == 2);
        servicePluginService.empty = true;

        runFx(() -> toggleButton("servicePluginsTab").fire());
        awaitFx(() -> servicePluginService.listCalls > 0
                && servicePluginList().getChildren().size() == 1);

        VBox empty = callFx(() -> (VBox) servicePluginList().getChildren().getFirst());
        assertEquals("尚未发现服务插件", callFx(() -> ((Label) empty.getChildren().get(1)).getText()));
        assertTrue(callFx(() -> ((Label) empty.getChildren().get(2)).getText()
                .contains("签名 JAR")));
        assertEquals("0 个", callFx(() -> ((Label) handle.root()
                .lookup("#servicePluginCount")).getText()));

        int calls = servicePluginService.listCalls;
        runFx(() -> ((Button) empty.lookup("#servicePluginRetryButton")).fire());
        awaitFx(() -> servicePluginService.listCalls > calls);
    }

    @Test
    void servicePluginDirectRouteUsesDefaultPageAndMissingPluginFallsBackToApproval() throws Exception {
        loadView(Optional.empty());
        PluginCenterController controller = handle.controller(PluginCenterController.class);

        runFx(() -> controller.openServicePluginConfiguration("deliverance", null));
        awaitFx(() -> handle.root().lookup("#servicePluginPageTab-configuration") != null);
        assertTrue(callFx(() -> ((ToggleButton) handle.root()
                .lookup("#servicePluginPageTab-configuration")).isSelected()));

        runFx(() -> controller.openServicePluginConfiguration("missing-deliverance", "models"));
        awaitFx(() -> toggleButton("installedTab").isSelected());
        awaitFx(() -> statusContains("请先批准并注册"));
    }

    @Test
    void deliveranceDualPagesRenderInManifestOrderWithoutASeparateRuntimeTab() throws Exception {
        loadView(Optional.empty());
        servicePluginService.dualUi = true;

        runFx(() -> handle.controller(PluginCenterController.class)
                .openServicePluginConfiguration("deliverance", "models"));

        awaitFx(() -> handle.root().lookup("#servicePluginPageTab-models") != null);
        HBox tabs = callFx(() -> (HBox) handle.root().lookup("#pageTabs"));
        assertEquals(List.of("模型", "服务"), callFx(() -> tabs.getChildren().stream()
                .map(ToggleButton.class::cast).map(ToggleButton::getText).toList()));
        assertTrue(callFx(() -> handle.root().lookup("#servicePluginSection-model-catalog") != null));
        assertTrue(callFx(() -> handle.root().lookup("#servicePluginSection-limits") == null));
        assertTrue(callFx(() -> handle.root().lookup("#servicePluginPageTab-host-runtime") == null));
        runFx(() -> ((ToggleButton) handle.root().lookup("#servicePluginPageTab-service")).fire());
        awaitFx(() -> handle.root().lookup("#serviceSettingsButton") != null
                || handle.root().lookup("#servicePluginInferenceError") != null);
        assertTrue(callFx(() -> handle.root().lookup("#servicePluginSection-model-service") != null));
        assertTrue(callFx(() -> handle.root().lookup("#processPrimaryButton") != null));
        assertTrue(callFx(() -> handle.root().lookup("#servicePluginRuntimeDisclosure") == null));
        runFx(() -> button("serviceSettingsButton").fire());
        assertTrue(callFx(() -> handle.root().lookup("#settingsDrawer").isVisible()));
        awaitFx(() -> {
            handle.root().applyCss();
            handle.root().layout();
            return handle.root().lookup("#serviceHeapMiBField") != null;
        });
        runFx(() -> {
            StackPane console = (StackPane) handle.root().lookup("#settingsDrawer").getParent();
            console.resize(820, 800);
        });
        assertEquals(Orientation.VERTICAL, callFx(() -> ((javafx.scene.control.SplitPane)
                handle.root().lookup("#serviceModelSplit")).getOrientation()));
        runFx(() -> button("logsCollapseButton").fire());
        assertFalse(callFx(() -> handle.root().lookup("#serviceLogsArea").isVisible()));
    }

    @Test
    void modelCatalogNeverLoadsAndTheServiceUsesALocalOnlyPicker()
            throws Exception {
        loadView(Optional.empty());
        servicePluginService.dualUi = true;
        inferenceService.snapshot = categorizedModels();

        runFx(() -> handle.controller(PluginCenterController.class)
                .openServicePluginConfiguration("deliverance", "models"));
        awaitFx(() -> handle.root().lookup("#modelSourceTabs") != null);
        runFx(() -> ((VBox) handle.root().lookup("#modelSourceTabs").getParent()).resize(820, 800));
        assertEquals(Orientation.VERTICAL, callFx(() -> ((javafx.scene.control.SplitPane)
                handle.root().lookup("#onlineCatalogSplit")).getOrientation()));
        runFx(() -> selectModelSourceTab(1));
        assertFalse(callFx(() -> button("loadSelectedAssetButton").isVisible()));
        assertFalse(callFx(() -> button("loadSelectedAssetButton").isManaged()));
        assertFalse(callFx(() -> button("unloadSelectedAssetButton").isVisible()));
        assertFalse(callFx(() -> button("unloadSelectedAssetButton").isManaged()));
        runFx(() -> selectModelSourceTab(1));
        awaitFx(() -> handle.root().lookup("#assetList") instanceof ListView<?>
                && listLabels("assetList").stream().anyMatch(label -> label.contains("MiniLM")));
        runFx(() -> ((ToggleButton) handle.root()
                .lookup("#servicePluginPageTab-service")).fire());
        awaitFx(() -> handle.root().lookup("#serviceLoadModelButton") != null);
        runFx(() -> button("serviceLoadModelButton").fire());
        awaitFx(() -> handle.root().lookup("#assetList") != null
                || handle.root().lookup("#servicePluginInferenceError") != null);
        Node pickerError = callFx(() -> handle.root().lookup("#servicePluginInferenceError"));
        assertTrue(callFx(() -> handle.root().lookup("#assetList") != null),
                pickerError == null ? "本地选择器未挂载"
                        : callFx(() -> pickerError.lookupAll(".label").stream()
                        .filter(Label.class::isInstance).map(Label.class::cast)
                        .map(Label::getText).toList().toString()));
        assertTrue(callFx(() -> handle.root().lookup("#modelSourceTabs") == null));
        assertTrue(callFx(() -> handle.root().lookup("#onlineModelList") == null));
        awaitFx(() -> handle.root().lookup("#assetList") instanceof ListView<?>
                && listLabels("assetList").stream().anyMatch(label -> label.contains("MiniLM")));
        runFx(() -> {
            ListView<?> list = (ListView<?>) handle.root().lookup("#assetList");
            for (int index = 0; index < list.getItems().size(); index++) {
                if (list.getItems().get(index).toString().contains("MiniLM")) {
                    list.getSelectionModel().select(index);
                    break;
                }
            }
        });
        awaitFx(() -> !button("loadSelectedAssetButton").isDisabled());
        runFx(() -> button("loadSelectedAssetButton").fire());

        awaitFx(() -> ((ToggleButton) handle.root()
                .lookup("#servicePluginPageTab-service")).isSelected());
        awaitFx(() -> handle.root().lookup("#parametersPanel") != null
                && handle.root().lookup("#parametersPanel").isVisible());
        assertTrue(callFx(() -> ((ComboBox<?>) handle.root().lookup("#profileAssetCombo"))
                .getValue().toString().startsWith("MiniLM ·")));
        assertEquals("向量化模型", callFx(() -> ((Label) handle.root()
                .lookup("#profilePurposeLabel")).getText()));
        runFx(() -> button("backToModelsFromParametersButton").fire());
        awaitFx(() -> handle.root().lookup("#serviceLoadModelButton") != null);
        awaitFx(() -> {
            handle.root().applyCss();
            handle.root().layout();
            return handle.root().lookup("#serviceModelList") != null;
        });
        assertEquals(2, callFx(() -> ((ListView<?>) handle.root()
                .lookup("#serviceModelList")).getItems().size()));
        assertTrue(callFx(() -> handle.root().lookup("#editServiceModelButton") != null));
        assertTrue(callFx(() -> handle.root().lookup("#deleteServiceModelButton") != null));
    }

    @Test
    void serviceLoadFlowReturnsWithTheNewProfileMarkedLoaded() throws Exception {
        loadView(Optional.empty());
        servicePluginService.dualUi = true;
        inferenceService.snapshot = categorizedModels();

        runFx(() -> handle.controller(PluginCenterController.class)
                .openServicePluginConfiguration("deliverance", "service"));
        awaitFx(() -> handle.root().lookup("#serviceLoadModelButton") != null);
        runFx(() -> button("serviceLoadModelButton").fire());
        awaitFx(() -> handle.root().lookup("#assetList") instanceof ListView<?> list
                && list.getItems().stream().anyMatch(item -> item.toString().contains("MiniLM")));
        runFx(() -> {
            ListView<?> list = (ListView<?>) handle.root().lookup("#assetList");
            for (int index = 0; index < list.getItems().size(); index++) {
                if (list.getItems().get(index).toString().contains("MiniLM")) {
                    list.getSelectionModel().select(index);
                    break;
                }
            }
        });
        awaitFx(() -> !button("loadSelectedAssetButton").isDisabled());
        runFx(() -> button("loadSelectedAssetButton").fire());
        awaitFx(() -> handle.root().lookup("#parametersPanel") != null
                && handle.root().lookup("#parametersPanel").isVisible()
                && !button("parameterSaveProfileButton").isDisabled());
        runFx(() -> button("parameterSaveProfileButton").fire());

        awaitFx(() -> inferenceService.saveCalls == 1 && inferenceService.startCalls == 1);
        awaitFx(() -> handle.root().lookup("#serviceLoadModelButton") != null
                && handle.root().lookup("#parametersPanel") == null);
        runFx(() -> {
            handle.root().applyCss();
            handle.root().layout();
        });
        awaitFx(() -> handle.root().lookup("#serviceModelList") instanceof ListView<?> list
                && !list.getItems().isEmpty());
        assertTrue(callFx(() -> listLabels("serviceModelList").stream()
                        .anyMatch(label -> label.startsWith("已加载  MiniLM"))),
                callFx(() -> listLabels("serviceModelList").toString()));
        assertEquals("卸载", callFx(() -> button("ejectServiceModelButton").getText()));
    }

    @Test
    void modelsPageLoadsFromRootContextAndGuidesAnEmptyCatalogToAssetImport() throws Exception {
        loadView(Optional.empty());
        servicePluginService.declarativeUi = true;

        runFx(() -> handle.controller(PluginCenterController.class)
                .openServicePluginConfiguration("deliverance", "models"));

        awaitFx(() -> handle.root().lookup("#emptyProfileState") != null
                && handle.root().lookup("#emptyProfileState").isVisible());
        runFx(() -> {
            handle.root().resize(960, 680);
            handle.root().applyCss();
            handle.root().layout();
        });
        assertTrue(callFx(() -> handle.root().lookup("#servicePluginSection-limits")
                .getBoundsInParent().getWidth() > 0));
        assertEquals(Region.USE_PREF_SIZE, callFx(() -> ((Region) handle.root()
                .lookup("#servicePluginSection-limits")).getMaxHeight()));
        assertTrue(callFx(() -> handle.root().lookup("#servicePluginSection-limits")
                .getStyleClass().contains("service-plugin-compact-config-section")));
        assertTrue(callFx(() -> handle.root().lookup("#servicePluginSection-limits")
                .lookup(".service-plugin-schema-form-compact") != null));
        assertTrue(callFx(() -> handle.root().lookup("#servicePluginSection-model-management")
                .getBoundsInParent().getHeight() > 0));
        assertTrue(callFx(() -> handle.root().lookup("#inferenceModelToolbar") != null));
        assertTrue(callFx(() -> handle.root().lookup("#servicePluginSection-model-management")
                .getStyleClass().contains("service-plugin-model-workbench")));
        assertTrue(callFx(() -> handle.root().lookup("#servicePluginSection-model-management")
                .lookupAll(".label").stream().filter(Label.class::isInstance).map(Label.class::cast)
                .noneMatch(label -> "模型文件与运行设置".equals(label.getText()))));
        assertTrue(callFx(() -> button("inferenceLoadModelButton").isVisible()));
        assertFalse(callFx(() -> button("inferenceBackToModelsButton").isVisible()));
        assertFalse(callFx(() -> handle.root().lookup("#profileList").isManaged()));
        assertTrue(callFx(() -> handle.root().lookup("#emptyProfileState")
                .lookupAll(".button").isEmpty()));
        assertTrue(callFx(() -> handle.root().lookup("#inferenceConfiguredModelsTab") == null));

        ScrollPane pageScroll = callFx(() -> (ScrollPane) handle.root()
                .lookup("#servicePluginPageScroll"));
        runFx(() -> pageScroll.setVvalue(pageScroll.getVmax()));
        runFx(() -> button("inferenceLoadModelButton").fire());
        awaitFx(() -> handle.root().lookup("#modelSourceTabs") != null);
        runFx(() -> selectModelSourceTab(0));
        awaitFx(() -> handle.root().lookup("#onlineModelList") != null
                || handle.root().lookup("#servicePluginInferenceError") != null);
        Node inferenceError = callFx(() -> handle.root().lookup("#servicePluginInferenceError"));
        assertTrue(callFx(() -> handle.root().lookup("#onlineModelList") != null),
                inferenceError == null ? "模型目录未挂载"
                        : callFx(() -> inferenceError.lookupAll(".label").stream()
                                .filter(Label.class::isInstance).map(Label.class::cast)
                                .map(Label::getText).toList().toString()));
        assertTrue(callFx(() -> button("onlineDownloadButton").isDisabled()));
        runFx(() -> selectModelSourceTab(1));
        awaitFx(() -> handle.root().lookup("#assetList") != null);
        awaitFx(() -> pageScroll.getVvalue() == pageScroll.getVmin());
        runFx(() -> {
            handle.root().applyCss();
            handle.root().layout();
        });
        assertTrue(callFx(() -> ((ListView<?>) handle.root().lookup("#assetList"))
                .getPlaceholder() != null));
        assertTrue(callFx(() -> button("loadSelectedAssetButton").isDisabled()));
        assertTrue(callFx(() -> ((Button) handle.root().lookup("#deleteAssetButton")).isDisabled()));
        assertTrue(callFx(() -> ((TabPane) handle.root().lookup("#modelSourceTabs"))
                .getSelectionModel().getSelectedItem().getText().startsWith("本地模型")));
        assertTrue(callFx(() -> button("inferenceBackToModelsButton").isVisible()));
        assertFalse(callFx(() -> button("inferenceLoadModelButton").isVisible()));
    }

    @Test
    void modelsAreClassifiedByPurposeAndDualPurposeFilesAppearInBothCategories() throws Exception {
        loadView(Optional.empty());
        servicePluginService.declarativeUi = true;
        inferenceService.snapshot = categorizedModels();

        runFx(() -> handle.controller(PluginCenterController.class)
                .openServicePluginConfiguration("deliverance", "models"));

        awaitFx(() -> handle.root().lookup("#inferenceGenerationKindTab") != null);
        assertEquals("推理模型", callFx(() -> ((ToggleButton) handle.root()
                .lookup("#inferenceGenerationKindTab")).getText()));
        assertEquals("向量化模型", callFx(() -> ((ToggleButton) handle.root()
                .lookup("#inferenceEmbeddingKindTab")).getText()));
        assertEquals(List.of("Qwen 运行设置 · 可用"), callFx(() -> listLabels("profileList")));
        assertFalse(callFx(() -> ((TitledPane) handle.root()
                .lookup("#workspaceRoutingPane")).isExpanded()));
        expandTitledPane("workspaceRoutingPane");
        awaitFx(() -> handle.root().lookup("#bindingStatusLabel") != null);
        assertTrue(callFx(() -> handle.root().lookup("#profileActionsMenu") != null));
        assertEquals(1L, callFx(() -> handle.root()
                .lookup("#servicePluginSection-model-management").lookupAll(".button").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> "加载模型".equals(button.getText())).count()));
        assertEquals("更改后自动保存", callFx(() -> ((Label) handle.root()
                .lookup("#bindingStatusLabel")).getText()));
        assertTrue(callFx(() -> ((Button) handle.root().lookup("#editProfileButton")).isDisabled()));
        runFx(() -> {
            ((ListView<?>) handle.root().lookup("#profileList")).getSelectionModel().selectFirst();
            ((Button) handle.root().lookup("#editProfileButton")).fire();
        });
        assertTrue(callFx(() -> handle.root().lookup("#parametersPanel").isVisible()));
        assertFalse(callFx(() -> ((TitledPane) handle.root()
                .lookup("#profileAdvancedPane")).isExpanded()));
        runFx(() -> button("backToModelsFromParametersButton").fire());
        assertTrue(callFx(() -> handle.root().lookup("#catalogPanel").isVisible()));

        runFx(() -> button("inferenceLoadModelButton").fire());
        awaitFx(() -> handle.root().lookup("#modelSourceTabs") != null);
        runFx(() -> selectModelSourceTab(1));
        awaitFx(() -> handle.root().lookup("#assetList") != null);
        assertTrue(callFx(() -> listLabels("assetList").stream()
                .anyMatch(label -> label.contains("Qwen"))));
        assertTrue(callFx(() -> listLabels("assetList").stream()
                .anyMatch(label -> label.contains("Hybrid"))));

        runFx(() -> ((ToggleButton) handle.root().lookup("#inferenceEmbeddingKindTab")).fire());
        awaitFx(() -> handle.root().lookup("#modelSourceTabs") != null);
        runFx(() -> selectModelSourceTab(1));
        awaitFx(() -> listLabels("assetList").stream().anyMatch(label -> label.contains("MiniLM")));
        assertTrue(callFx(() -> listLabels("assetList").stream()
                .anyMatch(label -> label.contains("Hybrid"))));

        runFx(() -> button("inferenceBackToModelsButton").fire());
        awaitFx(() -> handle.root().lookup("#profileList") != null);
        assertEquals(List.of("MiniLM 运行设置 · 可用"), callFx(() -> listLabels("profileList")));
        expandTitledPane("workspaceRoutingPane");
        awaitFx(() -> handle.root().lookup("#embeddingBindingCombo") != null);
        assertFalse(callFx(() -> handle.root().lookup("#generationBindingsBox").isVisible()));
        assertTrue(callFx(() -> handle.root().lookup("#embeddingBindingBox").isVisible()));
        runFx(() -> ((ComboBox<?>) handle.root().lookup("#embeddingBindingCombo")).setValue(null));
        awaitFx(() -> inferenceService.bindingCalls == 1);
        assertEquals(Set.of(InferenceCatalogPort.ModelTier.NORMAL),
                inferenceService.lastBindings.keySet());
        assertEquals("已自动保存", callFx(() -> ((Label) handle.root()
                .lookup("#bindingStatusLabel")).getText()));
        runFx(() -> {
            ((ListView<?>) handle.root().lookup("#profileList")).getSelectionModel().selectFirst();
            button("editProfileButton").fire();
        });
        assertEquals(1, callFx(() -> ((ComboBox<?>) handle.root()
                .lookup("#profileRuntimeCombo")).getItems().size()));
        assertFalse(callFx(() -> handle.root().lookup("#generationParametersSection").isVisible()));
        assertTrue(callFx(() -> handle.root().lookup("#embeddingDimensionsBox").isVisible()));
    }

    @Test
    void addingASinglePurposeModelMovesToItsDetectedCategory() throws Exception {
        loadView(Optional.empty());
        servicePluginService.declarativeUi = true;
        inferenceService.snapshot = categorizedModels();
        inferenceService.downloadedModel = model("Downloaded MiniLM", "bert", "e");

        runFx(() -> handle.controller(PluginCenterController.class)
                .openServicePluginConfiguration("deliverance", "models"));
        awaitFx(() -> handle.root().lookup("#inferenceLoadModelButton") != null);
        runFx(() -> button("inferenceLoadModelButton").fire());
        awaitFx(() -> handle.root().lookup("#modelSourceTabs") != null);
        runFx(() -> selectModelSourceTab(0));
        awaitFx(() -> handle.root().lookup("#onlineModelList") instanceof ListView<?> list
                && list.getItems().size() == 1);
        runFx(() -> ((ListView<?>) handle.root().lookup("#onlineModelList"))
                .getSelectionModel().selectFirst());
        awaitFx(() -> !button("onlineDownloadButton").isDisabled());
        runFx(() -> button("onlineDownloadButton").fire());

        awaitFx(() -> inferenceService.snapshot.assets().stream()
                .anyMatch(asset -> "Downloaded MiniLM".equals(asset.displayName())));
        runFx(() -> ((ToggleButton) handle.root()
                .lookup("#inferenceEmbeddingKindTab")).fire());
        awaitFx(() -> handle.root().lookup("#modelSourceTabs") != null);
        runFx(() -> selectModelSourceTab(1));
        awaitFx(() -> listLabels("assetList").stream()
                .anyMatch(label -> label.contains("Downloaded MiniLM")));
        runFx(() -> {
            ListView<?> list = (ListView<?>) handle.root().lookup("#assetList");
            for (int index = 0; index < list.getItems().size(); index++) {
                if (list.getItems().get(index).toString().contains("Downloaded MiniLM")) {
                    list.getSelectionModel().select(index);
                    break;
                }
            }
        });
        awaitFx(() -> !button("loadSelectedAssetButton").isDisabled());
        runFx(() -> button("loadSelectedAssetButton").fire());
        awaitFx(() -> handle.root().lookup("#parametersPanel") != null
                && handle.root().lookup("#parametersPanel").isVisible());
        expandTitledPane("profileAdvancedPane");
        awaitFx(() -> handle.root().lookup("#profileAssetCombo") != null);
        assertTrue(callFx(() -> ((ComboBox<?>) handle.root().lookup("#profileAssetCombo"))
                .getValue().toString().startsWith("Downloaded MiniLM ·")));
        assertTrue(interaction.confirmed);
        runFx(() -> button("parameterSaveProfileButton").fire());
        awaitFx(() -> inferenceService.saveCalls == 1 && inferenceService.startCalls == 1);
        awaitFx(() -> handle.root().lookup("#catalogPanel").isVisible());
        assertEquals(List.of("MiniLM 运行设置 · 可用", "Downloaded MiniLM · 已加载"),
                callFx(() -> listLabels("profileList")));
        assertEquals("1 个已加载 · 2 个已配置", callFx(() -> ((Label) handle.root()
                .lookup("#inferenceModelCount")).getText()));
        assertEquals("服务运行中", callFx(() -> ((Label) handle.root()
                .lookup("#inferenceServiceState")).getText()));
        runFx(() -> ((ListView<?>) handle.root().lookup("#profileList"))
                .getSelectionModel().selectLast());
        assertEquals("卸载", callFx(() -> button("runtimeProfileButton").getText()));
        runFx(() -> button("runtimeProfileButton").fire());
        awaitFx(() -> inferenceService.stopCalls == 1);
        awaitFx(() -> listLabels("profileList").getLast().endsWith("· 可用"));
    }

    @Test
    void aFailedDeclarativeSectionStaysVisibleAndRetryRestoresItWithoutHidingSiblings()
            throws Exception {
        loadView(Optional.empty());
        servicePluginService.declarativeUi = true;
        servicePluginService.invalidSchema = true;

        runFx(() -> handle.controller(PluginCenterController.class)
                .openServicePluginConfiguration("deliverance", "models"));

        awaitFx(() -> handle.root().lookup("#servicePluginSectionError-limits") != null);
        assertTrue(callFx(() -> handle.root().lookup("#emptyProfileState").isVisible()));
        assertEquals("加载插件配置区块失败", callFx(() -> interaction.lastToast.title()));
        assertTrue(callFx(() -> interaction.lastToast.message().contains("Unrecognized token")));

        servicePluginService.invalidSchema = false;
        runFx(() -> ((Button) handle.root().lookup("#servicePluginSectionRetry-limits")).fire());
        awaitFx(() -> handle.root().lookup("#servicePluginSaveSchemaConfiguration") != null);
        assertTrue(callFx(() -> handle.root().lookup("#servicePluginSectionError-limits") == null));
        assertTrue(callFx(() -> handle.root().lookup("#emptyProfileState").isVisible()));
    }

    @Test
    void successfulBindingSaveNotifiesRuntimeRebuildButFailureDoesNot() throws Exception {
        loadView(Optional.empty());
        servicePluginService.declarativeUi = true;
        inferenceService.snapshot = categorizedModels();
        AtomicInteger rebuilds = new AtomicInteger();
        PluginCenterController controller = handle.controller(PluginCenterController.class);
        runFx(() -> {
            controller.configure(rebuilds::incrementAndGet);
            controller.openServicePluginConfiguration("deliverance", "models");
        });
        awaitFx(() -> handle.root().lookup("#workspaceRoutingPane") != null);
        expandTitledPane("workspaceRoutingPane");
        awaitFx(() -> handle.root().lookup("#normalBindingCombo") != null);

        runFx(() -> {
            ((ComboBox<?>) handle.root().lookup("#normalBindingCombo")).setValue(null);
            ((ComboBox<?>) handle.root().lookup("#highBindingCombo"))
                    .getSelectionModel().selectFirst();
        });
        awaitFx(() -> inferenceService.bindingCalls == 1 && rebuilds.get() == 1);
        assertEquals("test", inferenceService.lastBindingWorkspace);
        assertEquals(Set.of(InferenceCatalogPort.ModelTier.HIGH,
                        InferenceCatalogPort.ModelTier.EMBEDDING),
                inferenceService.lastBindings.keySet());

        inferenceService.failBindings = true;
        runFx(() -> {
            ComboBox<?> normal = (ComboBox<?>) handle.root().lookup("#normalBindingCombo");
            normal.getSelectionModel().selectFirst();
            ((ComboBox<?>) handle.root().lookup("#highBindingCombo")).setValue(null);
        });
        awaitFx(() -> inferenceService.bindingCalls == 2);
        assertEquals(1, rebuilds.get());
        awaitFx(() -> ((Button) handle.root().lookup("#retryBindingsButton")).isVisible());
        assertTrue(callFx(() -> ((Label) handle.root().lookup("#bindingStatusLabel"))
                .getText().contains("自动保存失败")));
    }

    @Test
    void pendingServicePluginCanBeApprovedFromDetailsAndThenMovesToServiceTab() throws Exception {
        loadView(Optional.empty());
        awaitFx(() -> cardGrid().getChildren().size() == 2);
        service.addPendingService();
        service.listener.run();
        awaitFx(() -> cardGrid().getChildren().size() == 3);

        VBox pendingCard = callFx(() -> cardGrid().getChildren().stream()
                .map(VBox.class::cast)
                .filter(card -> "Pending Service".equals(
                        ((Label) card.lookup("#nameLabel")).getText()))
                .findFirst().orElseThrow());
        assertFalse(callFx(() -> pendingCard.lookup("#enabledToggle").isVisible()));
        assertTrue(callFx(() -> pendingCard.lookup("#approvalButton").isVisible()));

        runFx(() -> ((HBox) pendingCard.lookup("#headerRow")).getOnMouseClicked().handle(null));
        awaitFx(() -> "Pending Service".equals(detailLabel("nameLabel").getText()));
        assertFalse(callFx(() -> detailToggle().isVisible()));
        assertTrue(callFx(() -> detailButton("approvalButton").isVisible()));

        service.approvalAllowed = false;
        runFx(() -> detailButton("approvalButton").fire());
        awaitFx(() -> service.approvalCalls == 1);
        awaitFx(() -> detailButton("approvalButton").isVisible());
        awaitFx(() -> statusContains("仍处于待批准状态"));

        service.approvalFails = true;
        runFx(() -> detailButton("approvalButton").fire());
        awaitFx(() -> service.approvalCalls == 2);
        awaitFx(() -> detailButton("approvalButton").isVisible());
        awaitFx(() -> statusContains("插件 JAR 在批准后被修改"));
        awaitFx(() -> interaction.lastToast != null);
        assertEquals("服务插件批准失败", interaction.lastToast.title());
        assertEquals("插件 JAR 在批准后被修改", interaction.lastToast.message());

        service.approvalFails = false;
        service.approvalAllowed = true;
        runFx(() -> detailButton("approvalButton").fire());
        awaitFx(() -> service.approvalCalls == 3 && servicePluginView().isVisible());
        assertTrue(callFx(() -> toggleButton("servicePluginsTab").isSelected()));
        awaitFx(() -> statusContains("等待手动启动"));
    }

    private void loadView(Optional<Path> selectedJar) throws Exception {
        prepareContext(selectedJar);
        URL resource = getClass().getResource("/fxml/plugin/plugin-center.fxml");
        handle = callFx(() -> context.getBean(SpringFxmlLoader.class).load(resource));
        runFx(() -> {
            new Scene(handle.root());
            handle.root().applyCss();
        });
    }

    private void prepareContext(Optional<Path> selectedJar) {
        context = new AnnotationConfigApplicationContext();
        service = new FakePluginService();
        extensionService = new FakeAgentExtensionService();
        servicePluginService = new FakeServicePluginService();
        inferenceService = new RecordingInferenceService();
        interaction = new FakeInteraction();
        context.registerBean(PluginManagementApplicationService.class, () -> service);
        context.registerBean(AgentExtensionManagementApplicationService.class,
                () -> extensionService);
        context.registerBean(ServicePluginManagementApplicationService.class,
                () -> servicePluginService);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(InferenceManagementApplicationService.class, () -> inferenceService);
        context.registerBean(WorkspaceApplicationService.class, FakeWorkspaceService::new);
        context.registerBean(PluginJarPicker.class, () -> owner -> selectedJar);
        context.registerBean(UserInteractionPort.class, () -> interaction);
        context.registerBean(DialogService.class,
                () -> new DialogService(context.getBean(UserInteractionPort.class)));
        context.registerBean(ManagedTaskExecutor.class, () -> new ManagedTaskExecutor(),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(ExternalDirectoryOpener.class,
                () -> new ExternalDirectoryOpener(context.getBean(ManagedTaskExecutor.class)));
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(PluginComponentFactory.class,
                () -> new PluginComponentFactory(context.getBean(SpringFxmlLoader.class)));
        context.registerBean(InferencePluginConfigurationFactory.class,
                () -> new InferencePluginConfigurationFactory(
                        context.getBean(InferenceManagementApplicationService.class),
                        context.getBean(WorkspaceApplicationService.class),
                        context.getBean(SpringFxmlLoader.class),
                        context.getBean(ManagedTaskExecutor.class),
                        context.getBean(FxDispatcher.class)));
        context.refresh();
    }

    private Button button(String id) { return (Button) handle.root().lookup("#" + id); }
    private void expandTitledPane(String id) throws Exception {
        runFx(() -> {
            ((TitledPane) handle.root().lookup("#" + id)).setExpanded(true);
            handle.root().applyCss();
            handle.root().layout();
        });
    }
    private ToggleButton toggleButton(String id) {
        return (ToggleButton) handle.root().lookup("#" + id);
    }
    private TextField text(String id) { return (TextField) handle.root().lookup("#" + id); }
    private FlowPane cardGrid() { return (FlowPane) handle.root().lookup("#cardGrid"); }
    private VBox extensionView() { return (VBox) handle.root().lookup("#agentExtensionView"); }
    private VBox extensionList() { return (VBox) handle.root().lookup("#agentExtensionList"); }
    private VBox servicePluginList() { return (VBox) handle.root().lookup("#servicePluginList"); }
    private VBox servicePluginView() { return (VBox) handle.root().lookup("#servicePluginView"); }
    private Label extensionCount() { return (Label) handle.root().lookup("#agentExtensionCount"); }
    private boolean statusContains(String text) {
        return handle.root().lookupAll("#statusLabel").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .anyMatch(label -> label.getText().contains(text));
    }
    private Button extensionToggle(int index) {
        HBox row = (HBox) extensionList().getChildren().get(index);
        return (Button) row.getChildren().get(1);
    }
    private List<String> listLabels(String id) {
        return ((ListView<?>) handle.root().lookup("#" + id)).getItems().stream()
                .map(Object::toString).toList();
    }
    private void selectModelSourceTab(int index) {
        TabPane tabs = (TabPane) handle.root().lookup("#modelSourceTabs");
        tabs.getSelectionModel().clearSelection();
        tabs.getSelectionModel().select(index);
        handle.root().applyCss();
        handle.root().layout();
    }
    private ScrollPane detail() {
        return handle.controller(PluginCenterController.class).detailRoot();
    }
    private Label detailLabel(String id) { return (Label) detail().lookup("#" + id); }
    private VBox detailBox(String id) { return (VBox) detail().lookup("#" + id); }
    private Button detailButton(String id) { return (Button) detail().lookup("#" + id); }
    private ToggleSwitch detailToggle() {
        return (ToggleSwitch) detail().lookup("#enabledToggle");
    }

    private static Label label(Node root, String id) {
        return (Label) root.lookup("#" + id);
    }

    private static InferenceManagementApplicationService.Snapshot categorizedModels() {
        var runtimeManifest = new InferenceRuntimeManifest("deliverance-runtime", "deliverance",
                "0.0.12", "2", new InferenceRuntimeManifest.ProtocolVersion(1, 1),
                "test", "test", 25, Set.of(
                        "model-type:generation:qwen3", "model-type:embedding:bert",
                        "model-type:generation:hybrid", "model-type:embedding:hybrid"),
                Map.of(), List.of(), true);
        var runtime = new InferenceCatalogPort.RuntimeInstallation(runtimeManifest, "/tmp/runtime",
                InferenceCatalogPort.RuntimeState.ACTIVE, true, Instant.now());
        var generationOnlyManifest = new InferenceRuntimeManifest(
                "older-generation-runtime", "deliverance", "0.0.11", "1",
                new InferenceRuntimeManifest.ProtocolVersion(1, 1), "test", "test", 25,
                Set.of("model-type:generation:qwen3"), Map.of(), List.of(), true);
        var generationOnlyRuntime = new InferenceCatalogPort.RuntimeInstallation(
                generationOnlyManifest, "/tmp/older-runtime",
                InferenceCatalogPort.RuntimeState.INSTALLED, false, Instant.now());
        var qwen = model("Qwen", "qwen3", "a");
        var mini = model("MiniLM", "bert", "b");
        var hybrid = model("Hybrid", "hybrid", "c");
        var legacy = model("Legacy", "unknown", "d");
        var qwenSetting = setting("Qwen 运行设置", InferenceModelProfile.Kind.GENERATION,
                qwen.id(), runtimeManifest.runtimeId(), 0);
        var miniSetting = setting("MiniLM 运行设置", InferenceModelProfile.Kind.EMBEDDING,
                mini.id(), runtimeManifest.runtimeId(), 384);
        return new InferenceManagementApplicationService.Snapshot(List.of(runtime, generationOnlyRuntime),
                List.of(qwen, mini, hybrid, legacy), List.of(qwenSetting, miniSetting),
                Map.of(InferenceCatalogPort.ModelTier.NORMAL, qwenSetting.id(),
                        InferenceCatalogPort.ModelTier.EMBEDDING, miniSetting.id()),
                List.of(), InferenceCatalogPort.GatewayConfiguration.defaults(), List.of());
    }

    private static InferenceModelAsset model(String name, String type, String hash) {
        return new InferenceModelAsset(UUID.randomUUID(), InferenceModelAsset.Source.LOCAL_DIRECTORY,
                name, type, hash.repeat(64), "/tmp/" + name, "", "", List.of(), 1,
                InferenceModelAsset.State.READY, "", Instant.now());
    }

    private static InferenceModelProfile setting(
            String name, InferenceModelProfile.Kind kind, UUID modelId,
            String runtimeId, int dimensions) {
        return new InferenceModelProfile(UUID.randomUUID(), name, kind, modelId, runtimeId,
                Map.of(), Map.of(), 2048, dimensions, InferenceModelProfile.State.READY,
                "", Instant.now(), Instant.now());
    }

    private void awaitFx(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (callFx(condition)) return;
            Thread.sleep(10);
        }
        assertTrue(callFx(condition), "等待 JavaFX 状态更新超时");
    }

    private static void runFx(ThrowingRunnable action) throws Exception {
        callFx(() -> { action.run(); return null; });
    }

    private static <T> T callFx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.call();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (failure.get() instanceof Exception exception) throw exception;
        if (failure.get() instanceof Error error) throw error;
        return result.get();
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    private static final class FakeInteraction implements UserInteractionPort {
        private volatile boolean confirmed;
        private volatile ToastRequest lastToast;

        @Override
        public boolean confirm(ConfirmRequest request) {
            confirmed = true;
            return true;
        }

        @Override public void notify(ToastRequest request) { lastToast = request; }
    }

    private static final class FakePluginService
            implements PluginManagementApplicationService {
        private final List<Plugin> plugins = new ArrayList<>();
        private volatile int toggleCalls;
        private volatile int approvalCalls;
        private volatile boolean approvalAllowed = true;
        private volatile boolean approvalFails;
        private volatile Map<String, String> savedConfig;
        private volatile boolean subscriptionClosed;
        private Runnable listener;

        FakePluginService() {
            plugins.add(alpha(State.ACTIVE));
            plugins.add(new Plugin("beta", "Beta Search", "2.0", "Search provider",
                    List.of(), List.of(), List.of(), List.of(), State.STOPPED, ""));
        }

        @Override public synchronized Catalog snapshot() { return catalog(); }
        @Override public synchronized Catalog refresh() { return catalog(); }

        @Override
        public synchronized Catalog setEnabled(String pluginId, boolean enabled) {
            toggleCalls++;
            for (int index = 0; index < plugins.size(); index++) {
                Plugin old = plugins.get(index);
                if (!old.id().equals(pluginId)) continue;
                plugins.set(index, new Plugin(old.id(), old.name(), old.version(),
                        old.description(), old.permissions(), old.config(), old.skills(), old.tools(),
                        enabled ? State.ACTIVE : State.STOPPED, ""));
            }
            return catalog();
        }

        @Override
        public synchronized ApprovalResult approveServicePlugin(String pluginId) {
            approvalCalls++;
            if (approvalFails) throw new IllegalStateException("approval wrapper",
                    new SecurityException("插件 JAR 在批准后被修改"));
            if (approvalAllowed) {
                plugins.removeIf(plugin -> plugin.id().equals(pluginId));
            }
            return new ApprovalResult(catalog(), approvalAllowed);
        }

        @Override
        public synchronized InstallResult install(Path jar) {
            Plugin installed = new Plugin("installed", "Installed Plugin", "3.0", "Installed",
                    List.of(), List.of(), List.of(), List.of(), State.STOPPED, "");
            plugins.add(installed);
            return new InstallResult(installed.id(), catalog());
        }

        @Override
        public synchronized Catalog uninstall(String pluginId) {
            plugins.removeIf(plugin -> plugin.id().equals(pluginId));
            return catalog();
        }

        @Override
        public synchronized Details details(String pluginId) {
            Plugin plugin = catalog().require(pluginId);
            return new Details(plugin, plugin.id().equals("alpha")
                    ? Map.of("endpoint", "https://example.test", "token", "secret") : Map.of());
        }

        @Override
        public void saveConfig(String pluginId, Map<String, String> values) {
            savedConfig = Map.copyOf(new LinkedHashMap<>(values));
        }

        @Override public Path pluginsDirectory() { return Path.of("/tmp/plugins"); }

        @Override
        public AutoCloseable onCatalogChanged(Runnable listener) {
            this.listener = listener;
            return () -> subscriptionClosed = true;
        }

        synchronized Catalog catalog() { return new Catalog(List.copyOf(plugins)); }

        synchronized void addPendingService() {
            plugins.add(new Plugin("pending-service", "Pending Service", "1.0",
                    "Service awaiting approval", List.of(), List.of(), List.of(), List.of(),
                    State.PENDING_APPROVAL, "服务插件已验证，等待用户批准并注册"));
        }

        private static Plugin alpha(State state) {
            return new Plugin(
                    "alpha", "Alpha Plugin", "1.0", "Messaging integration",
                    List.of(new Permission("发送消息", true)),
                    List.of(new ConfigField("endpoint", "服务地址", false),
                            new ConfigField("token", "Token", true)),
                    List.of(new NamedItem("send-message", "发送一条消息")),
                    List.of(new NamedItem("message_send", "发送工具")),
                    state, "");
        }
    }

    private static final class FakeAgentExtensionService
            implements AgentExtensionManagementApplicationService {
        private final List<AgentExtension> extensions = new ArrayList<>(List.of(
                new AgentExtension("agent.alpha", "[1.0.0]", "[aaaa]", true)));
        private volatile int installedCalls;
        private volatile int installCalls;
        private volatile int toggleCalls;
        private volatile Path previewPath;
        private volatile InstallPreview lastPreview;
        private volatile InstallPreview installedPreview;

        @Override
        public synchronized InstallPreview preview(Path jar) {
            previewPath = jar;
            lastPreview = new InstallPreview(jar, "a".repeat(64), 128);
            return lastPreview;
        }

        @Override
        public synchronized List<AgentExtension> installed() {
            installedCalls++;
            return List.copyOf(extensions);
        }

        @Override
        public synchronized List<AgentExtension> install(InstallPreview preview) {
            installCalls++;
            installedPreview = preview;
            extensions.add(new AgentExtension(
                    "agent.installed", "[3.0.0]", "[" + preview.sha256() + "]", true));
            return List.copyOf(extensions);
        }

        @Override
        public synchronized List<AgentExtension> setEnabled(String extensionId, boolean enabled) {
            toggleCalls++;
            for (int index = 0; index < extensions.size(); index++) {
                AgentExtension current = extensions.get(index);
                if (!current.id().equals(extensionId)) continue;
                extensions.set(index, new AgentExtension(
                        current.id(), current.versions(), current.artifactHashes(), enabled));
            }
            return List.copyOf(extensions);
        }

        synchronized AgentExtension require(String id) {
            return extensions.stream()
                    .filter(extension -> extension.id().equals(id))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static final class FakeServicePluginService
            implements ServicePluginManagementApplicationService {
        private volatile State state = State.STOPPED;
        private volatile StartupPolicy policy = StartupPolicy.MANUAL;
        private volatile int listCalls;
        private volatile int startCalls;
        private volatile int configurationCalls;
        private volatile boolean empty;
        private volatile boolean declarativeUi;
        private volatile boolean dualUi;
        private volatile boolean invalidSchema;
        private volatile ResourceConfiguration resources =
                new ResourceConfiguration(4096, 4096, 6, 64, 256);
        private final List<EndpointConfiguration> endpoints = List.of(
                new EndpointConfiguration("openai", Protocol.HTTP, "127.0.0.1", 18080,
                        false, false, null, "", "********", 60, 100_000,
                        1, 64, 16L * 1024 * 1024));

        @Override public List<ServicePluginInfo> list() {
            listCalls++;
            if (empty) return List.of();
            return List.of(new ServicePluginInfo("deliverance", "Deliverance", "0.0.12",
                    "JavaClaw", true, Path.of("/tmp/plugins/deliverance/deliverance.jar"), true,
                    policy, state, state == State.HEALTHY ? 1234 : 0,
                    state == State.HEALTHY ? Instant.now() : null,
                    resources, endpoints, true,
                    java.util.Set.of("deliverance/chat"), 0, 0, 8192, 6,
                    0, List.of(), "", List.of(), Map.of(),
                    "本地 Deliverance 推理服务",
                    invalidSchema ? "not-json" : "{\"type\":\"object\",\"properties\":{"
                            + "\"maxGenerationResident\":{\"type\":\"integer\","
                            + "\"minimum\":1,\"maximum\":8,\"default\":1}}}",
                    Map.of("maxGenerationResident", "1"),
                    dualUi ? dualConfigurationUi() : declarativeUi ? configurationUi() : null,
                    inference(),
                    Map.of("openai", java.util.Set.of("models", "chat", "sse"))));
        }

        private static PluginDescriptor.Inference inference() {
            return new PluginDescriptor.Inference("deliverance", "0.0.12", "2", 1, 1,
                    Set.of("chat", "embeddings", "model-type:generation:qwen3",
                            "model-type:embedding:bert"), "{\"type\":\"object\"}");
        }

        private static PluginDescriptor.ConfigurationUi configurationUi() {
            return new PluginDescriptor.ConfigurationUi(1, List.of(
                    new PluginDescriptor.ConfigurationPage("models", "模型", "模型配置", List.of(
                            new PluginDescriptor.ConfigurationSection("limits",
                                    PluginDescriptor.ConfigurationSectionType.SCHEMA_FORM,
                                    "驻留数量", "", List.of("maxGenerationResident")),
                            new PluginDescriptor.ConfigurationSection("model-management",
                                    PluginDescriptor.ConfigurationSectionType.INFERENCE_MODELS,
                                    "模型文件与运行设置", "", List.of()))),
                    new PluginDescriptor.ConfigurationPage("api", "对外接口", "接口信息", List.of(
                            new PluginDescriptor.ConfigurationSection("api-info",
                                    PluginDescriptor.ConfigurationSectionType.INFO,
                                    "OpenAI API", "由宿主管理", List.of())))));
        }

        private static PluginDescriptor.ConfigurationUi dualConfigurationUi() {
            return new PluginDescriptor.ConfigurationUi(1, List.of(
                    new PluginDescriptor.ConfigurationPage("models", "模型", "在线与本地模型", List.of(
                            new PluginDescriptor.ConfigurationSection("model-catalog",
                                    PluginDescriptor.ConfigurationSectionType.INFERENCE_CATALOG,
                                    "在线与本地模型", "", List.of()))),
                    new PluginDescriptor.ConfigurationPage("service", "服务", "模型服务", List.of(
                            new PluginDescriptor.ConfigurationSection("service-runtime",
                                    PluginDescriptor.ConfigurationSectionType.SERVICE_RUNTIME,
                                    "进程与资源", "", List.of()),
                            new PluginDescriptor.ConfigurationSection("model-service",
                                    PluginDescriptor.ConfigurationSectionType.INFERENCE_SERVICE,
                                    "模型服务", "", List.of())))));
        }

        @Override public void start(String pluginId) { startCalls++; state = State.HEALTHY; }
        @Override public void stop(String pluginId) { state = State.STOPPED; }
        @Override public void restart(String pluginId) { state = State.HEALTHY; }
        @Override public void setStartupPolicy(String pluginId, StartupPolicy value) { policy = value; }
        @Override public void updateResources(String pluginId, ResourceConfiguration resources) { }
        @Override public void updateEndpoint(String pluginId, EndpointConfiguration endpoint) { }
        @Override public void updateConfigurationAndRestart(String pluginId,
                                                            ResourceConfiguration value,
                                                            List<EndpointConfiguration> values) {
            configurationCalls++;
            resources = value;
        }
        @Override public void updateConfigurationAndRestart(String pluginId,
                                                            ResourceConfiguration value,
                                                            List<EndpointConfiguration> values,
                                                            Map<String, String> pluginConfiguration) {
            configurationCalls++;
            resources = value;
        }
        @Override public void unquarantine(String pluginId) { state = State.STOPPED; }
    }

    private static final class RecordingInferenceService extends EmptyInferenceManagementService {
        private volatile InferenceManagementApplicationService.Snapshot snapshot =
                new InferenceManagementApplicationService.Snapshot(
                        List.of(), List.of(), List.of(), Map.of(), List.of(),
                        InferenceCatalogPort.GatewayConfiguration.defaults(), List.of());
        private volatile int bindingCalls;
        private volatile boolean failBindings;
        private volatile String lastBindingWorkspace;
        private volatile Map<InferenceCatalogPort.ModelTier, UUID> lastBindings = Map.of();
        private volatile InferenceModelAsset downloadedModel;
        private final Set<UUID> runningProfiles = new java.util.LinkedHashSet<>();
        private volatile int saveCalls;
        private volatile int startCalls;
        private volatile int stopCalls;

        @Override public InferenceManagementApplicationService.Snapshot snapshot(String workspaceId) {
            return snapshot;
        }

        @Override
        public Map<UUID, InferenceRuntimePort.RuntimeProfileStatus> runtimeStatuses(Set<UUID> ids) {
            return runningProfiles.stream().filter(ids::contains).collect(
                    java.util.stream.Collectors.toUnmodifiableMap(id -> id,
                            id -> new InferenceRuntimePort.RuntimeProfileStatus(
                                    id, true, 2048, 384, "test", Set.of())));
        }

        @Override
        public HuggingFaceModelCatalogPort.SearchPage searchOnlineModels(
                HuggingFaceModelCatalogPort.SearchRequest request,
                BooleanSupplier cancelled) {
            return downloadedModel == null
                    ? new HuggingFaceModelCatalogPort.SearchPage(List.of(), "")
                    : new HuggingFaceModelCatalogPort.SearchPage(List.of(onlineSummary()), "");
        }

        @Override
        public HuggingFaceModelCatalogPort.ModelDetail onlineModelDetail(
                String repository, BooleanSupplier cancelled) {
            return new HuggingFaceModelCatalogPort.ModelDetail(onlineSummary(), "apache-2.0",
                    List.of("BertModel"), 4, 8192, "https://huggingface.co/" + repository);
        }

        private HuggingFaceModelCatalogPort.ModelSummary onlineSummary() {
            long size = downloadedModel.artifactMetadata().quantizedSizeBytes() > 0
                    ? downloadedModel.artifactMetadata().quantizedSizeBytes()
                    : downloadedModel.sizeBytes();
            return new HuggingFaceModelCatalogPort.ModelSummary(
                    "owner/minilm", "f".repeat(40), downloadedModel.modelType(),
                    downloadedModel.quantizationType(), downloadedModel.sourceSizeBytes(),
                    size, Instant.now(), false);
        }

        @Override
        public RecommendedProfile recommendedProfile(
                UUID assetId, InferenceModelProfile.Kind kind,
                BooleanSupplier cancelled) {
            var capacity = new InferenceSystemProfilePort.SystemCapacity(
                    8, 16_000, 8_000, 9_600, 3_360, 6_240, 6_240, 7, true, true);
            var memory = new InferenceSystemProfilePort.MemoryAssessment(
                    1, 1, false, false, "");
            return new RecommendedProfile("deliverance-runtime", 2048,
                    Map.of(), Map.of(), capacity, memory);
        }

        @Override
        public InferenceAssetPreparationPort.HuggingFacePreview previewHuggingFace(
                InferenceAssetPreparationPort.HuggingFaceRequest request,
                BooleanSupplier cancelled) {
            if (downloadedModel == null) throw new UnsupportedOperationException("no download fixture");
            return new InferenceAssetPreparationPort.HuggingFacePreview(
                    request.repository(), "f".repeat(40), downloadedModel.sizeBytes(),
                    "apache-2.0", false, 2, downloadedModel.modelType());
        }

        @Override
        public InferenceModelAsset downloadHuggingFace(
                InferenceAssetPreparationPort.HuggingFaceRequest request,
                Consumer<InferenceAssetPreparationPort.Progress> progress,
                BooleanSupplier cancelled) {
            if (downloadedModel == null) throw new UnsupportedOperationException("no download fixture");
            List<InferenceModelAsset> models = new ArrayList<>(snapshot.assets());
            models.add(downloadedModel);
            snapshot = new InferenceManagementApplicationService.Snapshot(
                    snapshot.runtimes(), models, snapshot.profiles(), snapshot.bindings(),
                    snapshot.publishedModels(), snapshot.gateway(), snapshot.apiKeys());
            return downloadedModel;
        }

        @Override
        public InferenceModelProfile saveAndVerifyProfile(
                ProfileDraft draft, BooleanSupplier cancelled) {
            saveCalls++;
            Instant now = Instant.now();
            InferenceModelProfile profile = new InferenceModelProfile(
                    draft.profileId(), draft.name(), draft.kind(), draft.assetId(), draft.runtimeId(),
                    draft.loadParameters(), draft.defaultParameters(), 2048,
                    draft.kind() == InferenceModelProfile.Kind.EMBEDDING ? 384 : 0,
                    InferenceModelProfile.State.READY, "", now, now);
            List<InferenceModelProfile> profiles = new ArrayList<>(snapshot.profiles());
            profiles.removeIf(value -> value.id().equals(profile.id()));
            profiles.add(profile);
            snapshot = new InferenceManagementApplicationService.Snapshot(
                    snapshot.runtimes(), snapshot.assets(), profiles, snapshot.bindings(),
                    snapshot.publishedModels(), snapshot.gateway(), snapshot.apiKeys());
            return profile;
        }

        @Override public void startProfile(UUID profileId) {
            startCalls++;
            runningProfiles.add(profileId);
        }

        @Override public void stopProfile(UUID profileId) {
            stopCalls++;
            runningProfiles.remove(profileId);
        }

        @Override
        public void saveBindings(
                String workspaceId,
                Map<com.javaclaw.application.inference.InferenceCatalogPort.ModelTier, java.util.UUID> bindings) {
            bindingCalls++;
            lastBindingWorkspace = workspaceId;
            if (failBindings) throw new IllegalStateException("binding persistence failed");
            lastBindings = Map.copyOf(bindings);
        }
    }

    private static final class FakeWorkspaceService implements WorkspaceApplicationService {
        @Override public List<WorkspaceSummary> list() {
            return List.of(new WorkspaceSummary("test", "Test", ""));
        }
        @Override public String currentWorkspaceId() { return "test"; }
        @Override public WorkspaceSummary create(String name) {
            return new WorkspaceSummary("created", name, "");
        }
        @Override public boolean delete(String workspaceId) { return false; }
    }
}
