package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.EndpointConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ServicePluginInfo;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Standard host editor for descriptor-declared external endpoints. */
final class ServicePluginEndpointConfigurationPane implements AutoCloseable {
    private final ServicePluginInfo plugin;
    private final Consumer<List<EndpointConfiguration>> save;
    private final Consumer<String> rotateKey;
    private final VBox root = new VBox(10);
    private final List<EndpointEditor> editors = new ArrayList<>();
    private final Label validation = new Label();

    ServicePluginEndpointConfigurationPane(
            ServicePluginInfo plugin,
            Consumer<List<EndpointConfiguration>> save,
            Consumer<String> rotateKey) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.save = Objects.requireNonNull(save, "save");
        this.rotateKey = Objects.requireNonNull(rotateKey, "rotateKey");
        build();
    }

    Node root() { return root; }

    private void build() {
        root.getStyleClass().add("service-plugin-endpoints");
        if (plugin.endpoints().isEmpty()) {
            root.getChildren().add(hint("插件当前没有已配置的外部端点。"));
            return;
        }
        if (!plugin.endpointConfigurationManaged()) {
            root.getChildren().add(hint("这些端点由宿主专用能力页面管理，此处只读显示，避免形成第二套配置源。"));
            plugin.endpoints().forEach(endpoint -> root.getChildren().add(readOnly(endpoint)));
            return;
        }
        for (EndpointConfiguration endpoint : plugin.endpoints()) {
            EndpointEditor editor = new EndpointEditor(endpoint);
            editors.add(editor);
            root.getChildren().add(editor.root);
        }
        validation.setWrapText(true);
        validation.getStyleClass().add("service-plugin-error");
        validation.setVisible(false);
        validation.setManaged(false);
        Button button = button(isRunning(plugin) ? "保存并重启" : "保存接口", true,
                this::submit);
        button.setId("servicePluginSaveEndpoints");
        HBox footer = new HBox(8, validation, spacer(), button);
        footer.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(validation, Priority.ALWAYS);
        root.getChildren().add(footer);
    }

    private Node readOnly(EndpointConfiguration endpoint) {
        VBox card = new VBox(6);
        card.getStyleClass().addAll("jc-card", "service-plugin-endpoint-card");
        Label title = new Label(endpoint.id() + " · " + endpoint.protocol());
        title.getStyleClass().add("settings-group-title");
        Label address = hint(endpoint.protocol() + "://" + endpoint.bindAddress() + ":"
                + endpoint.port() + " · " + (endpoint.tlsEnabled() ? "TLS" : "未启用 TLS")
                + " · API Key " + (endpoint.apiKey().isBlank() ? "未配置" : "已配置"));
        card.getChildren().addAll(title, address);
        return card;
    }

    private void submit() {
        try {
            List<EndpointConfiguration> values = editors.stream().map(EndpointEditor::value).toList();
            validation.setVisible(false);
            validation.setManaged(false);
            save.accept(values);
        } catch (RuntimeException invalid) {
            validation.setText(invalid.getMessage() == null ? "接口配置无效" : invalid.getMessage());
            validation.setVisible(true);
            validation.setManaged(true);
        }
    }

    private final class EndpointEditor {
        private final EndpointConfiguration original;
        private final VBox root = new VBox(9);
        private final TextField bindAddress;
        private final TextField port;
        private final CheckBox tls;
        private final CheckBox insecureLan;
        private final TextField keyStorePath;
        private final PasswordField keyStorePassword = new PasswordField();
        private final TextField rpm;
        private final TextField tpm;
        private final TextField concurrent;
        private final TextField connections;
        private final TextField requestMiB;
        private final TextField requestTimeout;

        private EndpointEditor(EndpointConfiguration endpoint) {
            original = endpoint;
            bindAddress = field(endpoint.bindAddress());
            port = field(Integer.toString(endpoint.port()));
            tls = new CheckBox("启用 TLS");
            tls.setSelected(endpoint.tlsEnabled());
            insecureLan = new CheckBox("允许已确认的局域网明文 HTTP");
            insecureLan.setSelected(endpoint.allowInsecureLan());
            keyStorePath = field(endpoint.keyStorePath() == null ? "" : endpoint.keyStorePath().toString());
            keyStorePassword.setPromptText("留空保留现有密码");
            keyStorePassword.getStyleClass().add("settings-field");
            rpm = field(Integer.toString(endpoint.requestsPerMinute()));
            tpm = field(Long.toString(endpoint.tokensPerMinute()));
            concurrent = field(Integer.toString(endpoint.maxConcurrent()));
            connections = field(Integer.toString(endpoint.maxConnections()));
            requestMiB = field(Long.toString(Math.max(1,
                    (endpoint.maxRequestBytes() + 1024 * 1024 - 1) / (1024 * 1024))));
            requestTimeout = field(Integer.toString(endpoint.requestTimeoutSeconds()));
            buildEditor();
        }

        private void buildEditor() {
            root.getStyleClass().addAll("jc-card", "service-plugin-endpoint-card");
            Label title = new Label(original.id() + " · " + original.protocol());
            title.getStyleClass().add("settings-group-title");
            Label auth = hint(original.apiKey().isBlank()
                    ? "API Key 尚未配置" : "API Key 已配置（原文不回显）");
            Button rotate = button("重建 API Key…", false,
                    () -> rotateKey.accept(original.id()));
            HBox key = new HBox(8, auth, spacer(), rotate);
            key.setAlignment(Pos.CENTER_LEFT);
            root.getChildren().addAll(title,
                    fields(fieldBox("监听地址", bindAddress), fieldBox("端口（0 为自动）", port)),
                    new HBox(16, tls, insecureLan),
                    fields(fieldBox("PKCS#12 路径", keyStorePath),
                            fieldBox("证书密码", keyStorePassword)),
                    fields(fieldBox("RPM", rpm), fieldBox("TPM", tpm),
                            fieldBox("请求并发", concurrent), fieldBox("最大连接", connections),
                            fieldBox("最大请求（MiB）", requestMiB),
                            fieldBox("请求超时（秒）", requestTimeout)), key);
        }

        private EndpointConfiguration value() {
            int parsedPort = positiveInt(port, "端口", 0);
            if (parsedPort > 65_535) throw new IllegalArgumentException("端口必须小于等于 65535");
            String address = bindAddress.getText() == null ? "" : bindAddress.getText().strip();
            if (address.isBlank()) throw new IllegalArgumentException("监听地址不能为空");
            long bytes = Math.multiplyExact(positiveLong(requestMiB, "最大请求", 1), 1024L * 1024);
            int timeout = positiveInt(requestTimeout, "请求超时", 1);
            if (timeout > 3_600) throw new IllegalArgumentException("请求超时必须小于等于 3600 秒");
            String path = keyStorePath.getText() == null ? "" : keyStorePath.getText().strip();
            return new EndpointConfiguration(original.id(), original.protocol(), address, parsedPort,
                    tls.isSelected(), insecureLan.isSelected(), path.isBlank() ? null : Path.of(path),
                    keyStorePassword.getText(), original.apiKey(), positiveInt(rpm, "RPM", 1),
                    positiveLong(tpm, "TPM", 1), positiveInt(concurrent, "请求并发", 1),
                    positiveInt(connections, "最大连接", 1), bytes, timeout);
        }
    }

    private static FlowPane fields(Node... values) {
        FlowPane pane = new FlowPane(10, 8, values);
        pane.setPrefWrapLength(720);
        return pane;
    }

    private static VBox fieldBox(String label, Node control) {
        VBox box = new VBox(4, hint(label), control);
        box.getStyleClass().add("service-plugin-field");
        return box;
    }

    private static TextField field(String value) {
        TextField field = new TextField(value);
        field.getStyleClass().add("settings-field");
        return field;
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("settings-hint");
        return label;
    }

    private static Button button(String text, boolean primary, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().addAll("jc-btn", primary ? "jc-btn-primary" : "jc-btn-soft", "jc-btn-sm");
        button.setOnAction(ignored -> action.run());
        return button;
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private static int positiveInt(TextField field, String label, int minimum) {
        long value = positiveLong(field, label, minimum);
        if (value > Integer.MAX_VALUE) throw new IllegalArgumentException(label + "过大");
        return (int) value;
    }

    private static long positiveLong(TextField field, String label, long minimum) {
        try {
            long value = Long.parseLong(field.getText().strip());
            if (value < minimum) throw new NumberFormatException();
            return value;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(label + "必须是大于等于 " + minimum + " 的整数");
        }
    }

    private static boolean isRunning(ServicePluginInfo plugin) {
        return plugin.state() == com.javaclaw.application.serviceplugin
                .ServicePluginManagementApplicationService.State.HEALTHY
                || plugin.state() == com.javaclaw.application.serviceplugin
                .ServicePluginManagementApplicationService.State.DEGRADED;
    }

    @Override
    public void close() {
        editors.forEach(editor -> editor.keyStorePassword.clear());
        validation.setText("");
    }
}
