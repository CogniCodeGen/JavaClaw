package com.javaclaw.ui.javafx.plugin.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.application.plugin.v2.PluginManagementService.*;
import com.javaclaw.application.workspace.WorkspaceApplicationService;
import com.javaclaw.application.inference.InferenceApiServerControlPort;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.application.inference.HuggingFaceModelCatalogPort;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.inference.api.InferenceRuntimeManifest;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true", disabledReason = "需要 JavaFX 显示服务")
class UnifiedPluginCenterFxmlLoadTest {
    private ManagedTaskExecutor tasks;
    private FakeService fake;
    private RecordingInteraction interaction;
    private UnifiedPluginCenterController controller;
    private VBox root;

    @BeforeAll static void startToolkit() throws Exception {
        CompletableFuture<Void> started = new CompletableFuture<>();
        try { Platform.startup(() -> { Platform.setImplicitExit(false); started.complete(null); }); }
        catch (IllegalStateException alreadyStarted) { Platform.runLater(() -> started.complete(null)); }
        started.get(10, TimeUnit.SECONDS);
    }
    @BeforeEach void loadView() throws Exception {
        tasks = new ManagedTaskExecutor(); fake = new FakeService(); interaction = new RecordingInteraction();
        WorkspaceApplicationService workspaces = new WorkspaceApplicationService() {
            public List<WorkspaceSummary> list() { return List.of(); }
            public String currentWorkspaceId() { return "workspace-a"; }
            public WorkspaceSummary create(String name) { throw new UnsupportedOperationException(); }
            public boolean delete(String id) { return false; }
        };
        onFx(() -> {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/plugin/v2/unified-plugin-center.fxml"));
            loader.setControllerFactory(type -> new UnifiedPluginCenterController(fake.proxy(), workspaces,
                    new DialogService(interaction), tasks, new FxDispatcher(), new ObjectMapper(), null));
            root = loader.load(); controller = loader.getController(); new Scene(root, 1100, 760);
            root.applyCss(); root.layout(); return null;
        });
        await(() -> "Alpha".equals(label("pluginName").getText()) && root.lookup("#pluginConfigurationJson") != null);
    }
    @AfterEach void close() throws Exception {
        if (controller != null) onFx(() -> { controller.close(); return null; });
        if (tasks != null) tasks.close();
    }

    @Test void loadsUnifiedHostFxmlAndFiltersCapabilities() throws Exception {
        assertEquals("已启用", onFx(() -> label("enabledLabel").getText()));
        assertEquals("进程：已停止", onFx(() -> label("processLabel").getText()));
        onFx(() -> { text("searchField").setText("BETA"); return null; });
        assertEquals(1, onFx(() -> list().getItems().size()));
        onFx(() -> { text("searchField").clear(); combo("capabilityFilter").setValue("models"); return null; });
        assertEquals("beta", onFx(() -> list().getItems().getFirst().id()));
        assertEquals(List.of("概览", "配置", "权限", "运行与日志"), onFx(() ->
                ((javafx.scene.control.TabPane) root.lookup("#detailTabs")).getTabs().stream().map(javafx.scene.control.Tab::getText).toList()));
    }

    @Test void statusRefreshKeepsDraftAndSecretReplacementAndSavingUsesRevision() throws Exception {
        TextArea original = onFx(() -> area("pluginConfigurationJson"));
        onFx(() -> {
            original.setText("{\"endpoint\":\"changed\"}");
            combo("secretMode-token").getSelectionModel().select(1);
            ((PasswordField) root.lookup("#secretValue-token")).setText("new-secret"); return null;
        });
        int previous = fake.detailCalls;
        fake.emit("workspace-b");
        assertEquals(previous, fake.detailCalls);
        fake.emit("workspace-a");
        await(() -> fake.detailCalls > previous);
        assertSame(original, onFx(() -> area("pluginConfigurationJson")));
        assertEquals("{\"endpoint\":\"changed\"}", onFx(original::getText));
        assertEquals("new-secret", onFx(() -> ((PasswordField) root.lookup("#secretValue-token")).getText()));
        onFx(() -> { button("saveButton").fire(); return null; });
        await(() -> fake.lastUpdate != null && label("revisionLabel").getText().contains("版本 2"));
        assertEquals(1, fake.lastUpdate.expectedRevision());
        assertEquals("{\"endpoint\":\"changed\"}", fake.lastUpdate.configurationJson());
        assertEquals(SecretAction.SET, fake.lastUpdate.secretChanges().get("token").action());
        assertEquals("new-secret", fake.lastUpdate.secretChanges().get("token").value());
        assertEquals("", onFx(() -> ((PasswordField) root.lookup("#secretValue-token")).getText()));
    }

    @Test void previewsLocalPackageConfirmsHashAndInstallsDisabled() throws Exception {
        onFx(() -> { controller.setPackagePicker(ignored -> Path.of("/tmp/example.zip")); button("addLocalButton").fire(); return null; });
        await(() -> fake.installCalls == 1 && "Gamma".equals(label("pluginName").getText()));
        assertTrue(interaction.requests.getFirst().description().contains("b".repeat(64)));
        assertTrue(interaction.requests.getFirst().description().contains("默认不启用"));
        assertEquals("已停用", onFx(() -> label("enabledLabel").getText()));
    }

    @Test void globalUninstallRefreshesTheCurrentWorkspaceAndClearsRemovedDetails() throws Exception {
        synchronized (fake) { fake.plugins.remove("alpha"); }

        fake.emit("");

        await(() -> list().getItems().size() == 1 && !root.lookup("#detailPane").isVisible());
        assertEquals("beta", onFx(() -> list().getItems().getFirst().id()));
        assertTrue(onFx(() -> root.lookup("#emptyDetail").isVisible()));
    }

    @Test void forceStopRequiresKeywordConfirmationAndDescribesActiveRunTermination() throws Exception {
        onFx(() -> { button("forceStopButton").fire(); return null; });
        await(() -> fake.forceStopCalls == 1);
        var approval = interaction.requests.getFirst();
        assertEquals(com.javaclaw.api.interaction.ConfirmKind.DOUBLE_CONFIRM, approval.kind());
        assertEquals("alpha", approval.keyword());
        assertTrue(approval.description().contains("所有活跃执行"));
        assertTrue(approval.description().contains("不会自动重放"));
    }

    @Test void versionSelectionIsExplicitAndUsesWorkspaceRevision() throws Exception {
        await(() -> ((ComboBox<?>) root.lookup("#installedPluginVersions")).getItems().size() == 2);
        onFx(() -> {
            ((ComboBox<?>) root.lookup("#installedPluginVersions")).getSelectionModel().select(1);
            button("selectPluginVersionButton").fire(); return null;
        });
        await(() -> fake.selectedVersion != null && label("pluginIdentity").getText().contains("1.1.0"));
        assertEquals("d".repeat(64), fake.selectedVersion);
        assertEquals(1, fake.selectedRevision);
        assertTrue(interaction.requests.getFirst().description().contains("已有执行继续使用原工件"));
        assertTrue(interaction.requests.getFirst().description().contains("d".repeat(64)));
    }

    @Test void permissionDraftSurvivesRefreshGuardsLeavingAndUsesItsOriginalRevision() throws Exception {
        PluginDetails old = fake.plugins.get("alpha");
        fake.plugins.put("alpha", new PluginDetails(old.summary(), old.manifestJson(), old.configuration(), Set.of("network.http"), old.contributions()));
        fake.emit("workspace-a");
        await(() -> permissionBox() != null);
        onFx(() -> { permissionBox().setSelected(true); return null; });
        assertTrue(onFx(() -> label("dirtyLabel").isVisible()));
        interaction.allow = false; AtomicBoolean accepted = new AtomicBoolean();
        onFx(() -> { controller.requestClose(() -> accepted.set(true)); return null; });
        await(() -> !interaction.requests.isEmpty()); assertFalse(accepted.get());
        var next = new ConfigurationSnapshot(2, "{}", "{}", Map.of("token", true), Set.of());
        fake.plugins.put("alpha", new PluginDetails(old.summary(), "{}", next, Set.of("network.http"), List.of()));
        int calls = fake.detailCalls; fake.emit("workspace-a"); await(() -> fake.detailCalls > calls);
        assertTrue(onFx(() -> permissionBox().isSelected()));
        interaction.allow = true; onFx(() -> { button("grantButton").fire(); return null; });
        await(() -> fake.lastUpdate != null && label("errorLabel").isVisible());
        assertEquals(1, fake.lastUpdate.expectedRevision());
        assertEquals(Set.of("network.http"), fake.lastUpdate.grantedPermissions());
        assertTrue(onFx(() -> permissionBox().isSelected()));
    }

    @Test void refusesInsecureUrlAndGuardsUnsavedCloseAndClosesSubscription() throws Exception {
        onFx(() -> { text("downloadUrl").setText("http://example.test/plugin.zip"); button("addUrlButton").fire(); return null; });
        await(() -> label("errorLabel").isVisible());
        assertEquals(0, fake.inspectCalls);
        AtomicBoolean accepted = new AtomicBoolean(); interaction.allow = false;
        onFx(() -> { area("pluginConfigurationJson").setText("{\"endpoint\":\"draft\"}"); controller.requestClose(() -> accepted.set(true)); return null; });
        await(() -> !interaction.requests.isEmpty());
        assertFalse(accepted.get());
        onFx(() -> { controller.close(); return null; });
        assertTrue(fake.subscriptionClosed);
        assertTrue(controller.isClosed());
    }

    @Test void secretClearIsExplicitAndRawJsonCannotSmuggleSecret() throws Exception {
        PluginConfigurationEditor editor = onFx(() -> new PluginConfigurationEditor(new ObjectMapper()));
        onFx(() -> {
            editor.load(7, "{}", "{}", "{\"endpoint\":\"a\"}", Map.of("token", true));
            ((ComboBox<?>) editor.root().lookup("#secretMode-token")).getSelectionModel().select(2);
            assertEquals(PluginConfigurationEditor.SecretAction.CLEAR, editor.secretChanges().get("token").action());
            ((TextArea) editor.root().lookup("#pluginConfigurationJson")).setText("{\"token\":\"plain-secret\"}");
            assertThrows(IllegalArgumentException.class, editor::configurationJson);
            editor.close(); return null;
        });
    }

    @Test void validatedUiSchemaRendersOnlyHostReferenceControlsAndPreservesSecrets() throws Exception {
        synchronized (fake) {
            PluginDetails previous = fake.plugins.get("alpha");
            String schema = "{\"type\":\"object\",\"properties\":{\"endpoint\":{\"type\":\"string\"}}}";
            String uiSchema = "{\"script\":\"throw new Error()\",\"properties\":{"
                    + "\"endpoint\":{\"ui:widget\":\"tool-ref\",\"ui:options\":[\"tool-a\",\"tool-b\"]},"
                    + "\"unlisted\":{\"ui:widget\":\"tool-ref\",\"ui:options\":[\"hidden-tool\"]}}}";
            fake.plugins.put("alpha", new PluginDetails(previous.summary(), previous.manifestJson(),
                    new ConfigurationSnapshot(1, schema, "{\"endpoint\":\"tool-a\"}", Map.of("token", true), Set.of()),
                    previous.requiredPermissions(), previous.contributions(), uiSchema));
        }

        onFx(() -> { controller.openPlugin("alpha", "configuration"); return null; });

        await(() -> root.lookup("#pluginConfigurationJson") == null);
        onFx(() -> {
            var host = (VBox) root.lookup("#configurationHost");
            var references = host.lookupAll(".combo-box").stream()
                    .filter(node -> node instanceof ComboBox<?> box && box.getItems().contains("tool-a"))
                    .map(node -> (ComboBox<?>) node).toList();
            assertEquals(1, references.size());
            assertEquals(List.of("tool-a", "tool-b"), references.getFirst().getItems());
            assertTrue(references.getFirst().isEditable());
            assertEquals("tool-a", references.getFirst().getValue());
            assertNotNull(host.lookup("#secretMode-token"));
            assertEquals("", ((PasswordField) host.lookup("#secretValue-token")).getText());
            assertTrue(host.lookupAll(".combo-box").stream().noneMatch(node ->
                    node instanceof ComboBox<?> box && box.getItems().contains("hidden-tool")));
            return null;
        });
    }

    @Test void modelPagesBindTheProviderAndKeepTheirDraftOnStatusRefresh() throws Exception {
        var own = modelProfile("alpha"); var other = modelProfile("beta");
        var runtime = new InferenceRuntimeManifest("alpha", "example-engine", "1", "2",
                new InferenceRuntimeManifest.ProtocolVersion(1, 2), "any", "any", 25,
                Set.of("chat", "model-type:generation:llama"), Map.of(), List.of(), true);
        var snapshot = new InferenceManagementApplicationService.Snapshot(List.of(new InferenceCatalogPort.RuntimeInstallation(
                runtime, "plugin:alpha/hash", InferenceCatalogPort.RuntimeState.ACTIVE, true, Instant.now())),
                List.of(), List.of(own, other), Map.of(), List.of(new InferenceCatalogPort.PublishedModel(
                        "alpha-model", own.id(), true, Instant.EPOCH, Instant.EPOCH)),
                InferenceCatalogPort.GatewayConfiguration.defaults().withProvider("beta"), List.of());
        var modelGateway = new java.util.concurrent.atomic.AtomicReference<>(snapshot.gateway());
        InferenceManagementApplicationService inference = (InferenceManagementApplicationService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{InferenceManagementApplicationService.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "snapshot" -> new InferenceManagementApplicationService.Snapshot(snapshot.runtimes(), snapshot.assets(), snapshot.profiles(),
                            snapshot.bindings(), snapshot.publishedModels(), modelGateway.get(), snapshot.apiKeys());
                    case "saveGateway" -> { modelGateway.set((InferenceCatalogPort.GatewayConfiguration) args[0]); yield null; }
                    case "runtimeStatuses" -> Map.of();
                    case "modelServiceSnapshot" -> new InferenceApiServerControlPort.ServiceSnapshot(
                            new InferenceApiServerControlPort.ApiServerStatus(InferenceApiServerControlPort.State.AVAILABLE,
                                    "http://127.0.0.1:19001/v1", ""), "READY", 222, 0,
                            List.of(modelGateway.get().providerId() + "-endpoint-log"), true);
                    case "searchOnlineModels" -> new HuggingFaceModelCatalogPort.SearchPage(List.of(), "");
                    case "gatewayEndpoint" -> "";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        WorkspaceApplicationService workspaces = (WorkspaceApplicationService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WorkspaceApplicationService.class}, (proxy, method, args) -> "workspace-a");
        PluginDetails original = fake.plugins.get("alpha");
        PluginDetails details = new PluginDetails(original.summary(), original.manifestJson(), original.configuration(),
                original.requiredPermissions(), List.of(new ContributionInfo("inference", "MODEL", "2.0", "模型", "", "{}",
                "{\"capabilities\":[\"chat\"],\"endpointCapabilities\":{\"openai\":[\"models\",\"chat\",\"embeddings\",\"sse\"]}}", "{}")));
        AtomicBoolean failed = new AtomicBoolean();
        PluginInferencePanel panel = onFx(() -> new PluginInferencePanel(details, "workspace-a", fake.proxy(), inference,
                workspaces, new DialogService(interaction), tasks, new FxDispatcher(), new ObjectMapper(), () -> { },
                problem -> failed.set(true), (accepted, rejected) -> accepted.run()));
        try {
            onFx(() -> { new Scene((VBox) panel.root(), 1100, 760); panel.openPage("profiles"); panel.root().applyCss(); ((VBox) panel.root()).layout(); return null; });
            await(() -> ((ListView<?>) panel.root().lookup("#profileList")).getItems().size() == 1);
            assertFalse(failed.get());
            assertEquals("alpha", panel.root().getUserData());
            assertTrue(onFx(() -> ((ComboBox<?>) panel.root().lookup("#profileRuntimeCombo")).isDisabled()));
            onFx(() -> {
                ((TextField) panel.root().lookup("#profileNameField")).setText("draft-name");
                panel.update(details); return null;
            });
            assertTrue(onFx(panel::isDirty));
            assertEquals("draft-name", onFx(() -> ((TextField) panel.root().lookup("#profileNameField")).getText()));
            onFx(() -> { panel.openPage("service"); ((VBox) panel.root()).layout(); return null; });
            assertTrue(onFx(() -> panel.root().lookup("#endpointSettingsBox").isDisabled()));
            assertTrue(onFx(() -> ((Label) panel.root().lookup("#endpointOwnerLabel")).getText().contains("beta")));
            onFx(() -> {
                assertEquals("", ((TextField) panel.root().lookup("#serviceUrlField")).getText());
                String curl = ((TextArea) panel.root().lookup("#curlExampleArea")).getText();
                assertTrue(curl.contains("beta")); assertFalse(curl.contains("curl "));
                assertEquals("", ((TextArea) panel.root().lookup("#serviceLogsArea")).getText());
                String routes = ((TextArea) panel.root().lookup("#routesArea")).getText();
                assertTrue(routes.contains("/chat/completions"));
                assertFalse(routes.contains("/embeddings")); assertFalse(routes.contains("SSE"));
                assertFalse(((Label) panel.root().lookup("#pluginProcessLabel")).getText().contains("222"));
                return null;
            });
            onFx(() -> { ((Button) panel.root().lookup("#takeEndpointButton")).fire(); return null; });
            await(() -> "alpha".equals(modelGateway.get().providerId()) && !panel.root().lookup("#endpointSettingsBox").isDisabled());
            assertTrue(interaction.requests.getLast().description().contains("先关闭旧实例的监听"));
            assertTrue(onFx(() -> ((TextArea) panel.root().lookup("#curlExampleArea")).getText().contains("\"model\":\"alpha-model\"")));
            onFx(() -> { panel.update(original); return null; });
            assertFalse(onFx(() -> ((Label) panel.root().lookup("#apiRoutesLabel")).getText().contains("/chat/completions")));
        } finally { onFx(() -> { panel.close(); return null; }); }
    }
    private static InferenceModelProfile modelProfile(String plugin) {
        return new InferenceModelProfile(UUID.randomUUID(), plugin, InferenceModelProfile.Kind.GENERATION, UUID.randomUUID(), plugin,
                Map.of(), Map.of(), 4096, 0, InferenceModelProfile.State.READY, "", Instant.now(), Instant.now());
    }

    private Label label(String id) { return (Label) root.lookup("#" + id); }
    private javafx.scene.control.CheckBox permissionBox() {
        return root.lookupAll(".check-box").stream().filter(node -> node instanceof javafx.scene.control.CheckBox box && "network.http".equals(box.getText()))
                .map(node -> (javafx.scene.control.CheckBox) node).findFirst().orElse(null);
    }
    private Button button(String id) { return (Button) root.lookup("#" + id); }
    private TextField text(String id) { return (TextField) root.lookup("#" + id); }
    private TextArea area(String id) { return (TextArea) root.lookup("#" + id); }
    @SuppressWarnings("unchecked") private ComboBox<String> combo(String id) { return (ComboBox<String>) root.lookup("#" + id); }
    @SuppressWarnings("unchecked") private ListView<PluginSummary> list() { return (ListView<PluginSummary>) root.lookup("#pluginList"); }
    private static <T> T onFx(Callable<T> task) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> { try { result.complete(task.call()); } catch (Throwable failure) { result.completeExceptionally(failure); } });
        return result.get(10, TimeUnit.SECONDS);
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!onFx(condition::getAsBoolean)) {
            if (System.nanoTime() >= until) fail("等待界面状态超时");
            Thread.sleep(20);
        }
    }
    private static final class RecordingInteraction implements UserInteractionPort {
        volatile boolean allow = true;
        final List<ConfirmRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        public boolean confirm(ConfirmRequest request) { requests.add(request); return allow; }
        public void notify(ToastRequest request) { }
    }
    private static final class FakeService implements InvocationHandler {
        final Map<String, PluginDetails> plugins = new LinkedHashMap<>();
        volatile int detailCalls, installCalls, inspectCalls, forceStopCalls;
        volatile boolean subscriptionClosed;
        volatile ConfigurationUpdate lastUpdate;
        volatile String selectedVersion;
        volatile long selectedRevision;
        volatile Consumer<PluginStatusEvent> observer;
        FakeService() {
            plugins.put("alpha", details("alpha", "Alpha", Set.of("tools"), true, 1, "{\"endpoint\":\"initial\"}"));
            plugins.put("beta", details("beta", "Beta", Set.of("models"), false, 1, "{}"));
        }
        PluginManagementService proxy() {
            return (PluginManagementService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PluginManagementService.class}, this);
        }
        @Override public synchronized Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "list" -> plugins.values().stream().map(PluginDetails::summary).toList();
                case "details" -> { detailCalls++; yield plugins.get(arguments[1]); }
                case "versions" -> { var value = plugins.get(arguments[1]).summary(); yield List.of(
                        new PluginVersion(value.id(), value.version(), value.artifactHash(), value.publisher(), value.signatureVerified(), true),
                        new PluginVersion(value.id(), "1.1.0", "d".repeat(64), "新发布者", false, false)); }
                case "selectVersion" -> {
                    selectedVersion = (String) arguments[2]; selectedRevision = (long) arguments[3];
                    PluginDetails old = plugins.get(arguments[1]); PluginSummary value = old.summary();
                    var replacement = new PluginDetails(new PluginSummary(value.id(), value.name(), "1.1.0", value.description(),
                            "新发布者", selectedVersion, false, value.enabled(), value.startupPolicy(), value.state(),
                            selectedRevision + 1, value.capabilities(), ""), old.manifestJson(),
                            new ConfigurationSnapshot(selectedRevision + 1, "{}", old.configuration().configurationJson(),
                                    old.configuration().configuredSecrets(), old.configuration().grantedPermissions()), old.requiredPermissions(), old.contributions());
                    plugins.put(value.id(), replacement); yield replacement;
                }
                case "observeStatus" -> { observer = (Consumer<PluginStatusEvent>) arguments[0]; yield (AutoCloseable) () -> subscriptionClosed = true; }
                case "diagnostics" -> new Diagnostics(State.STOPPED, "instance-a", 123, "", List.of("启动完成"), Map.of("ready", "true"));
                case "forceStop" -> { forceStopCalls++; yield null; }
                case "inspectPackage" -> { inspectCalls++; yield new PackagePreview("preview", "gamma", "Gamma", "1.0.0", "新增插件", "发布者", "c".repeat(64),
                        "b".repeat(64), "{}", "{}", Set.of(), Set.of("tools"), List.of(), true, 42, arguments[0].toString()); }
                case "install" -> { installCalls++; var value = details("gamma", "Gamma", Set.of("tools"), false, 1, "{}"); plugins.put("gamma", value); yield value; }
                case "updateConfiguration" -> {
                    lastUpdate = (ConfigurationUpdate) arguments[2];
                    PluginDetails old = plugins.get(arguments[1]);
                    if (old.configuration().revision() != lastUpdate.expectedRevision()) throw new PluginException("CONFIGURATION_CONFLICT", "配置已变更，请保留草稿并刷新");
                    var value = details(old.summary().id(), old.summary().name(), old.summary().capabilities(), old.summary().enabled(),
                            lastUpdate.expectedRevision() + 1, lastUpdate.configurationJson());
                    plugins.put(value.summary().id(), value); yield value;
                }
                case "toString" -> "FakePluginManagementService";
                case "close", "discardPackagePreview" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
        void emit(String workspace) { if (observer != null) observer.accept(new PluginStatusEvent(workspace, "alpha", State.READY, 1, "状态改变", Instant.now())); }
        private static PluginDetails details(String id, String name, Set<String> capabilities, boolean enabled, long revision, String configuration) {
            return new PluginDetails(new PluginSummary(id, name, "1.0.0", "插件描述", "发布者", "a".repeat(64), true,
                    enabled, StartupPolicy.MANUAL, State.STOPPED, revision, capabilities, ""), "{}",
                    new ConfigurationSnapshot(revision, "{}", configuration, Map.of("token", true), Set.of()), Set.of(), List.of());
        }
    }
}
