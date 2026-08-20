package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.inference.InferenceApiServerControlPort;
import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.application.inference.InferenceRuntimePort;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.State;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** 鉴权、限流且只暴露原始模型推理的 OpenAI 兼容 API 配置页。 */
public final class InferenceApiSettingsController implements AutoCloseable {
    @FXML private StackPane root;
    @FXML private ToggleSwitch gatewayEnabledCheck, invocationLoggingSwitch;
    @FXML private TextField bindAddressField, portField, keyStorePathField;
    @FXML private TextField maxRequestBytesField, requestTimeoutField, serviceUrlField;
    @FXML private TextField serviceModelIdentifierField, serviceModelPathField, aliasField;
    @FXML private TextField keyNameField, keyRpmField, keyTpmField, keyConcurrencyField;
    @FXML private CheckBox tlsEnabledCheck, allowInsecureLanCheck;
    @FXML private CheckBox modelsReadScope, chatScope, embeddingsScope;
    @FXML private PasswordField keyStorePasswordField;
    @FXML private Label gatewayEndpointLabel, gatewayStateLabel, apiRoutesLabel;
    @FXML private Label pluginProcessLabel, invocationLoggingSupportLabel, statusLabel, pluginErrorLabel;
    @FXML private Button ejectServiceModelButton, editServiceModelButton, deleteServiceModelButton;
    @FXML private Button processPrimaryButton, processRestartButton, logsCollapseButton;
    @FXML private ListView<InferenceSettingsChoice<InferenceModelPresentation.ServiceModel>>
            serviceModelList;
    @FXML private TextArea serviceModelDetailsArea, routesArea, curlExampleArea, serviceLogsArea;
    @FXML private SplitPane serviceModelSplit;
    @FXML private VBox settingsDrawer, runtimeSettingsHost;
    @FXML private Region settingsScrim;
    @FXML private ComboBox<String> logFilterCombo;
    @FXML private ComboBox<InferenceSettingsChoice<UUID>> publishedProfileCombo;
    @FXML private ListView<InferenceSettingsChoice<String>> publishedList;
    @FXML private ListView<String> keyAliasList;
    @FXML private TextArea createdKeyArea;
    @FXML private ListView<InferenceSettingsChoice<UUID>> apiKeyList;

    private final InferenceManagementApplicationService useCases;
    private final InferenceSettingsUiActions ui;
    private InferenceManagementApplicationService.Snapshot snapshot;
    private Runnable reloadAll = () -> { };
    private Runnable loadModel = () -> { };
    private Consumer<UUID> editModel = ignored -> { };
    private PluginPresentation presentation = new PluginPresentation(State.STOPPED, Map.of());
    private InferenceServiceModelConsole modelConsole;
    private List<String> serviceLogs = List.of();
    private boolean applying;
    private InferenceServiceConsoleChrome chrome;

    public InferenceApiSettingsController(
            InferenceManagementApplicationService useCases, DialogService dialogs,
            ManagedTaskExecutor tasks, FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        ui = new InferenceSettingsUiActions(dialogs, tasks, fx);
    }

    @FXML
    private void initialize() {
        ui.attach(statusLabel);
        chrome = new InferenceServiceConsoleChrome(root, serviceModelSplit, settingsDrawer,
                runtimeSettingsHost, settingsScrim, serviceLogsArea, logsCollapseButton,
                processPrimaryButton, processRestartButton, pluginProcessLabel, pluginErrorLabel);
        modelConsole = new InferenceServiceModelConsole(useCases, ui,
                () -> reloadAll.run(), id -> editModel.accept(id), serviceModelList,
                serviceModelDetailsArea, serviceModelIdentifierField, serviceModelPathField,
                curlExampleArea, ejectServiceModelButton, editServiceModelButton,
                deleteServiceModelButton);
        keyAliasList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        SettingsFieldSupport.validateInteger(portField, 1, 65535);
        SettingsFieldSupport.validateInteger(requestTimeoutField, 1, 3600);
        SettingsFieldSupport.validateInteger(keyRpmField, 1, 100_000);
        SettingsFieldSupport.validateInteger(keyConcurrencyField, 1, 1024);
        gatewayEnabledCheck.selectedProperty().addListener((ignored, before, enabled) -> {
            if (!applying && before != enabled) saveGatewayRequested();
        });
        invocationLoggingSwitch.selectedProperty().addListener((ignored, before, enabled) -> {
            if (!applying && before != enabled) invocationLoggingRequested();
        });
        logFilterCombo.getItems().setAll("全部日志", "调用日志", "运行日志");
        logFilterCombo.setValue("全部日志");
        logFilterCombo.valueProperty().addListener((ignored, previous, selected) -> renderLogs());
    }

    void configure(Runnable reload) { reloadAll = Objects.requireNonNull(reload, "reload"); }

    void configure(Runnable reload, PluginPresentation value) {
        configure(reload);
        presentation = Objects.requireNonNull(value, "value");
        chrome.configure(presentation, InferencePluginConfigurationFactory.RuntimeControls.none());
    }

    void configure(Runnable reload, Runnable loadModelAction, PluginPresentation value) {
        configure(reload, value);
        loadModel = Objects.requireNonNull(loadModelAction, "loadModelAction");
    }

    void configure(Runnable reload, Runnable loadModelAction,
                   Consumer<UUID> editModelAction, PluginPresentation value) {
        configure(reload, loadModelAction, value);
        editModel = Objects.requireNonNull(editModelAction, "editModelAction");
    }

    void configure(Runnable reload, Runnable loadModelAction,
                   Consumer<UUID> editModelAction, PluginPresentation value,
                   InferencePluginConfigurationFactory.RuntimeControls runtimeControls) {
        configure(reload, loadModelAction, editModelAction, value);
        chrome.configure(value, runtimeControls);
    }

    void preferServiceProfile(UUID profileId) { modelConsole.prefer(profileId); }

    void apply(InferenceManagementApplicationService.Snapshot value) {
        apply(value, Map.of());
    }

    void apply(InferenceManagementApplicationService.Snapshot value,
               Map<UUID, InferenceRuntimePort.RuntimeProfileStatus> statuses) {
        apply(value, statuses, useCases.modelServiceSnapshot());
    }

    void apply(InferenceManagementApplicationService.Snapshot value,
               Map<UUID, InferenceRuntimePort.RuntimeProfileStatus> statuses,
               InferenceApiServerControlPort.ServiceSnapshot service) {
        snapshot = value;
        List<String> selectedAliases = List.copyOf(keyAliasList.getSelectionModel().getSelectedItems());
        var generation = ready(value, InferenceModelProfile.Kind.GENERATION);
        var embedding = ready(value, InferenceModelProfile.Kind.EMBEDDING);
        publishedProfileCombo.getItems().setAll(concat(generation, embedding));
        publishedList.getItems().setAll(value.publishedModels().stream().map(model ->
                new InferenceSettingsChoice<>(model.alias() + " → " + profileName(model.profileId()),
                        model.alias())).toList());
        keyAliasList.getItems().setAll(value.publishedModels().stream()
                .filter(InferenceCatalogPort.PublishedModel::enabled)
                .map(InferenceCatalogPort.PublishedModel::alias).toList());
        selectedAliases.forEach(alias -> keyAliasList.getSelectionModel().select(alias));
        apiKeyList.getItems().setAll(value.apiKeys().stream().map(key ->
                new InferenceSettingsChoice<>(key.name() + " · " + key.prefix()
                        + (key.revoked() ? " · 已撤销" : ""), key.id())).toList());
        var gateway = value.gateway();
        applying = true;
        gatewayEnabledCheck.setSelected(gateway.enabled());
        bindAddressField.setText(gateway.bindAddress());
        portField.setText(Integer.toString(gateway.port()));
        tlsEnabledCheck.setSelected(gateway.tlsEnabled());
        allowInsecureLanCheck.setSelected(gateway.allowInsecureLanWithoutTls());
        keyStorePathField.setText(gateway.keyStorePath());
        maxRequestBytesField.setText(Long.toString(gateway.maxRequestBytes()));
        requestTimeoutField.setText(Integer.toString(gateway.requestTimeoutSeconds()));
        boolean loggingSupported = service.invocationLoggingSupported();
        invocationLoggingSwitch.setDisable(!loggingSupported);
        invocationLoggingSwitch.setSelected(gateway.invocationLoggingEnabled());
        applying = false;
        String endpoint = service.api().endpoint().isBlank()
                ? useCases.gatewayEndpoint() : service.api().endpoint();
        gatewayEndpointLabel.setText(endpoint.isBlank() ? "API 服务未运行" : "服务地址：" + endpoint);
        serviceUrlField.setText(endpoint);
        chrome.updateProcess(service.processState(), service.pid(), service.activeRequests());
        invocationLoggingSupportLabel.setText(loggingSupported
                ? "仅记录请求元数据，不记录提示词、输出或凭据"
                : "需要 Deliverance 0.0.12-7 / 协议 1.2；当前插件不支持热开关");
        gatewayStateLabel.setText("插件 " + InferenceApiConsoleActions.stateText(presentation.state()) + " · "
                + (gateway.enabled() ? "接口已启用" : "接口未启用") + " · "
                + gateway.bindAddress() + ":" + gateway.port() + " · "
                + (gateway.tlsEnabled() ? "TLS" : "HTTP"));
        apiRoutesLabel.setText(InferenceApiConsoleActions.routes(presentation.endpointCapabilities()));
        routesArea.setText("GET  /v1/models\nGET  /v1/models/{id}\n"
                + "POST /v1/chat/completions\nPOST /v1/embeddings\n"
                + "GET  /openapi.json\nPOST /v1/chat/completions  (stream=true, SSE)");
        modelConsole.apply(value, statuses, endpoint);
        serviceLogs = service.recentLogs();
        renderLogs();
    }

    @FXML
    private void invocationLoggingRequested() {
        boolean requested = invocationLoggingSwitch.isSelected();
        ui.run(requested ? "启用模型调用日志" : "关闭模型调用日志", context -> {
            useCases.setInvocationLogging(requested);
            return null;
        }, ignored -> {
            reloadAll.run();
            ui.status(requested ? "调用日志已即时启用，插件进程未重启" : "调用日志已即时关闭");
        });
    }

    @FXML private void refreshLogsRequested() { refreshServiceLogs(); }
    @FXML private void processPrimaryRequested() { chrome.processPrimary(); }
    @FXML private void processRestartRequested() { chrome.restart(); }
    @FXML private void openServiceSettingsRequested() { chrome.openDrawer(); }
    @FXML private void closeServiceSettingsRequested() { chrome.closeDrawer(); }
    @FXML private void toggleLogsRequested() { chrome.toggleLogs(); }
    @FXML private void loadModelRequested() { loadModel.run(); }
    @FXML private void copyServiceUrlRequested() { copy(serviceUrlField.getText(), "服务 URL"); }
    @FXML private void copyRoutesRequested() { copy(routesArea.getText(), "接口路由"); }
    @FXML private void copyModelIdentifierRequested() { copy(serviceModelIdentifierField.getText(), "模型名称"); }
    @FXML private void copyModelPathRequested() { copy(serviceModelPathField.getText(), "模型路径"); }
    @FXML private void copyCurlRequested() { copy(curlExampleArea.getText(), "cURL 示例"); }

    @FXML private void ejectServiceModelRequested() {
        modelConsole.toggleRuntime();
    }

    @FXML private void editServiceModelRequested() {
        modelConsole.edit();
    }

    @FXML private void deleteServiceModelRequested() {
        modelConsole.deleteConfiguration();
    }

    private void copy(String value, String label) { InferenceApiConsoleActions.copy(value, label, ui); }

    @FXML
    private void chooseKeyStoreRequested() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 PKCS#12 TLS 证书");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PKCS#12 (*.p12, *.pfx)", "*.p12", "*.pfx"));
        var selected = chooser.showOpenDialog(root.getScene().getWindow());
        if (selected != null) keyStorePathField.setText(selected.getAbsolutePath());
    }

    @FXML
    private void saveGatewayRequested() {
        if (snapshot == null) return;
        var current = snapshot.gateway();
        char[] password = keyStorePasswordField.getText().toCharArray();
        keyStorePasswordField.clear();
        var command = new InferenceCatalogPort.GatewayConfiguration(gatewayEnabledCheck.isSelected(),
                SettingsFieldSupport.text(bindAddressField),
                SettingsFieldSupport.integer(portField, 1, 65535, "监听端口"),
                tlsEnabledCheck.isSelected(), allowInsecureLanCheck.isSelected(),
                SettingsFieldSupport.text(keyStorePathField),
                current.encryptedKeyStorePassword(),
                SettingsFieldSupport.longInteger(maxRequestBytesField, 1024,
                        64L * 1024 * 1024, "请求体上限"),
                SettingsFieldSupport.integer(requestTimeoutField, 1, 3600, "请求超时"),
                current.invocationLoggingEnabled(), Instant.now());
        ui.run("保存推理 API 服务", context -> {
            useCases.saveGateway(command, password);
            return null;
        }, ignored -> { reloadAll.run(); ui.status("API 服务配置已原子重载"); });
    }

    @FXML
    private void publishRequested() {
        var profile = publishedProfileCombo.getValue();
        if (profile == null) return;
        ui.run("发布本地模型别名", context -> {
            useCases.publish(SettingsFieldSupport.text(aliasField), profile.value());
            return null;
        }, ignored -> { reloadAll.run(); ui.status("模型别名已发布"); });
    }

    @FXML
    private void unpublishRequested() {
        var selected = publishedList.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        ui.run("取消发布模型", context -> { useCases.unpublish(selected.value()); return null; },
                ignored -> reloadAll.run());
    }

    @FXML
    private void createKeyRequested() {
        Set<InferenceCatalogPort.ApiScope> scopes = EnumSet.noneOf(InferenceCatalogPort.ApiScope.class);
        if (modelsReadScope.isSelected()) scopes.add(InferenceCatalogPort.ApiScope.MODELS_READ);
        if (chatScope.isSelected()) scopes.add(InferenceCatalogPort.ApiScope.CHAT_INVOKE);
        if (embeddingsScope.isSelected()) scopes.add(InferenceCatalogPort.ApiScope.EMBEDDINGS_INVOKE);
        Set<String> aliases = Set.copyOf(keyAliasList.getSelectionModel().getSelectedItems());
        ui.run("创建推理 API Key", context -> useCases.createApiKey(
                SettingsFieldSupport.text(keyNameField), scopes, aliases,
                SettingsFieldSupport.integer(keyRpmField, 1, 100_000, "RPM"),
                SettingsFieldSupport.longInteger(keyTpmField, 1, Long.MAX_VALUE, "TPM"),
                SettingsFieldSupport.integer(keyConcurrencyField, 1, 1024, "并发")), result -> {
            var created = (InferenceManagementApplicationService.CreatedApiKey) result;
            reloadAll.run();
            createdKeyArea.setText(created.secret());
            ui.status("API Key 只显示这一次，请立即安全保存");
        });
    }

    @FXML
    private void revokeKeyRequested() {
        var selected = apiKeyList.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        ui.confirm("撤销 API Key", "撤销立即生效且不可恢复，确定继续？",
                context -> { useCases.revokeApiKey(selected.value()); return null; },
                ignored -> reloadAll.run());
    }

    private String profileName(UUID id) {
        return snapshot.profiles().stream().filter(profile -> profile.id().equals(id))
                .map(InferenceModelProfile::name).findFirst().orElse(id.toString());
    }

    private void refreshServiceLogs() {
        if (snapshot == null) return;
        var service = useCases.modelServiceSnapshot();
        serviceLogs = service.recentLogs().isEmpty()
                ? presentation.recentLogs() : service.recentLogs();
        renderLogs();
    }

    private void renderLogs() {
        if (serviceLogsArea == null) return;
        String filter = logFilterCombo == null ? "全部日志" : logFilterCombo.getValue();
        serviceLogsArea.setText(InferenceModelPresentation.filterLogs(serviceLogs, filter));
    }

    private static List<InferenceSettingsChoice<UUID>> ready(
            InferenceManagementApplicationService.Snapshot value, InferenceModelProfile.Kind kind) {
        return value.profiles().stream().filter(profile -> profile.kind() == kind
                && profile.state() == InferenceModelProfile.State.READY)
                .map(profile -> new InferenceSettingsChoice<>(profile.name(), profile.id())).toList();
    }

    private static <T> List<T> concat(List<T> left, List<T> right) {
        ArrayList<T> result = new ArrayList<>(left);
        result.addAll(right);
        return List.copyOf(result);
    }

    void cancel() { ui.cancel(); }

    @Override
    public void close() {
        loadModel = () -> { };
        editModel = ignored -> { };
        modelConsole.close();
        chrome.close();
        createdKeyArea.clear();
        keyStorePasswordField.clear();
        ui.close();
    }

    record PluginPresentation(State state, Map<String, Set<String>> endpointCapabilities,
                              long pid, List<String> recentLogs, String lastError) {
        PluginPresentation {
            state = state == null ? State.STOPPED : state;
            endpointCapabilities = endpointCapabilities == null ? Map.of() : endpointCapabilities;
            recentLogs = recentLogs == null ? List.of() : List.copyOf(recentLogs);
            lastError = lastError == null ? "" : lastError;
        }

        PluginPresentation(State state, Map<String, Set<String>> endpointCapabilities) {
            this(state, endpointCapabilities, 0, List.of(), "");
        }

        PluginPresentation(State state, Map<String, Set<String>> endpointCapabilities,
                           long pid, List<String> recentLogs) {
            this(state, endpointCapabilities, pid, recentLogs, "");
        }
    }
}
