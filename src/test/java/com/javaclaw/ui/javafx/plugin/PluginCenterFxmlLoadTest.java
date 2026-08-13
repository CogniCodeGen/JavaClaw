package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.plugin.AgentExtensionManagementApplicationService;
import com.javaclaw.application.plugin.AgentExtensionManagementApplicationService.AgentExtension;
import com.javaclaw.application.plugin.AgentExtensionManagementApplicationService.InstallPreview;
import com.javaclaw.application.plugin.PluginManagementApplicationService;
import com.javaclaw.platform.desktop.ExternalDirectoryOpener;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        interaction = new FakeInteraction();
        context.registerBean(PluginManagementApplicationService.class, () -> service);
        context.registerBean(AgentExtensionManagementApplicationService.class,
                () -> extensionService);
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
        context.refresh();
    }

    private Button button(String id) { return (Button) handle.root().lookup("#" + id); }
    private ToggleButton toggleButton(String id) {
        return (ToggleButton) handle.root().lookup("#" + id);
    }
    private TextField text(String id) { return (TextField) handle.root().lookup("#" + id); }
    private FlowPane cardGrid() { return (FlowPane) handle.root().lookup("#cardGrid"); }
    private VBox extensionView() { return (VBox) handle.root().lookup("#agentExtensionView"); }
    private VBox extensionList() { return (VBox) handle.root().lookup("#agentExtensionList"); }
    private Label extensionCount() { return (Label) handle.root().lookup("#agentExtensionCount"); }
    private Button extensionToggle(int index) {
        HBox row = (HBox) extensionList().getChildren().get(index);
        return (Button) row.getChildren().get(1);
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

        @Override
        public boolean confirm(ConfirmRequest request) {
            confirmed = true;
            return true;
        }

        @Override public void notify(ToastRequest request) {}
    }

    private static final class FakePluginService
            implements PluginManagementApplicationService {
        private final List<Plugin> plugins = new ArrayList<>();
        private volatile int toggleCalls;
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
}
