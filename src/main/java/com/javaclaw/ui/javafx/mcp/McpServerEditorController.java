package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.application.mcp.McpManagementApplicationService;
import com.javaclaw.application.mcp.McpManagementApplicationService.SaveCommand;
import com.javaclaw.application.mcp.McpManagementApplicationService.Server;
import com.javaclaw.application.mcp.McpManagementApplicationService.TestResult;
import com.javaclaw.application.mcp.McpManagementApplicationService.Transport;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MCP 服务器编辑 Controller：字段绑定、复用行生命周期和连接测试。 */
public final class McpServerEditorController implements AutoCloseable {
    @FXML private Label titleLabel;
    @FXML private TextField nameField;
    @FXML private ToggleButton stdioButton;
    @FXML private ToggleButton httpButton;
    @FXML private VBox stdioSection;
    @FXML private TextField commandField;
    @FXML private TextArea argumentsArea;
    @FXML private Label commandPreview;
    @FXML private VBox environmentRows;
    @FXML private VBox httpSection;
    @FXML private TextField urlField;
    @FXML private VBox headerRows;
    @FXML private CheckBox enabledCheck;
    @FXML private Label errorLabel;
    @FXML private Label testResultLabel;
    @FXML private Button testButton;

    private final McpManagementApplicationService useCases;
    private final McpKeyValueRowFactory rows;
    private final UiAsyncAction<TestResult> testAction;
    private final McpServerEditorViewModel viewModel = new McpServerEditorViewModel();
    private final List<McpKeyValueRowFactory.Row> environment = new ArrayList<>();
    private final List<McpKeyValueRowFactory.Row> headers = new ArrayList<>();

    public McpServerEditorController(
            McpManagementApplicationService useCases,
            McpKeyValueRowFactory rows,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = java.util.Objects.requireNonNull(useCases, "useCases");
        this.rows = java.util.Objects.requireNonNull(rows, "rows");
        testAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        nameField.textProperty().bindBidirectional(viewModel.nameProperty());
        commandField.textProperty().bindBidirectional(viewModel.commandProperty());
        argumentsArea.textProperty().bindBidirectional(viewModel.argumentsProperty());
        urlField.textProperty().bindBidirectional(viewModel.urlProperty());
        enabledCheck.selectedProperty().bindBidirectional(viewModel.enabledProperty());
        errorLabel.textProperty().bind(viewModel.errorProperty());
        testResultLabel.textProperty().bind(viewModel.testResultProperty());
        testButton.disableProperty().bind(testAction.busyProperty());
        viewModel.testingProperty().bind(testAction.busyProperty());
        commandField.textProperty().addListener((ignored, previous, value) -> refreshPreview());
        argumentsArea.textProperty().addListener((ignored, previous, value) -> refreshPreview());
    }

    void configure(Server existing) {
        clearRows();
        boolean editing = existing != null;
        titleLabel.setText(editing ? "编辑 MCP 服务器" : "添加 MCP 服务器");
        viewModel.originalNameProperty().set(editing ? existing.name() : "");
        viewModel.nameProperty().set(editing ? existing.name() : "");
        nameField.setDisable(editing);
        viewModel.httpProperty().set(editing && existing.transport() == Transport.HTTP);
        viewModel.commandProperty().set(editing ? existing.command() : "");
        viewModel.argumentsProperty().set(editing ? String.join("\n", existing.arguments()) : "");
        viewModel.urlProperty().set(editing ? existing.url() : "");
        viewModel.enabledProperty().set(!editing || existing.enabled());
        if (editing) {
            existing.environment().forEach((key, value) -> addEnvironment(key, value));
            existing.headers().forEach((key, value) -> addHeader(key, value));
        }
        applyTransport();
        refreshPreview();
    }

    void configure(SaveCommand seed) {
        configure((Server) null);
        if (seed == null) return;
        viewModel.nameProperty().set(seed.name());
        viewModel.httpProperty().set(seed.transport() == Transport.HTTP);
        viewModel.commandProperty().set(seed.command());
        viewModel.argumentsProperty().set(String.join("\n", seed.arguments()));
        viewModel.urlProperty().set(seed.url());
        viewModel.enabledProperty().set(seed.enabled());
        seed.environment().forEach(this::addEnvironment);
        seed.headers().forEach(this::addHeader);
        applyTransport();
        refreshPreview();
    }

    @FXML private void stdioRequested() { viewModel.httpProperty().set(false); applyTransport(); }
    @FXML private void httpRequested() { viewModel.httpProperty().set(true); applyTransport(); }
    @FXML private void addEnvironmentRequested() { addEnvironment("", ""); }
    @FXML private void addHeaderRequested() { addHeader("", ""); }

    @FXML
    private void testRequested() {
        if (!validate()) return;
        SaveCommand command = command();
        viewModel.testResultProperty().set("正在测试 " + command.name() + " …");
        setResultStyle("status-info");
        testAction.execute(TaskSpec.io("mcp-test-" + command.name()),
                context -> useCases.test(command), this::showTestResult,
                failure -> {
                    viewModel.testResultProperty().set("✗ 连接失败：" + failureMessage(failure));
                    setResultStyle("status-error");
                });
    }

    boolean validate() {
        String error = validationMessage();
        viewModel.errorProperty().set(error.isEmpty() ? "" : "⚠ " + error);
        show(errorLabel, !error.isEmpty());
        return error.isEmpty();
    }

    SaveCommand command() {
        return new SaveCommand(viewModel.originalNameProperty().get(), viewModel.nameProperty().get(),
                viewModel.transport(), viewModel.commandProperty().get(), arguments(), values(environment),
                viewModel.urlProperty().get(), values(headers), viewModel.enabledProperty().get());
    }

    private String validationMessage() {
        if (text(viewModel.nameProperty().get()).isEmpty()) return "请填写服务器名称。";
        if (viewModel.httpProperty().get()) {
            String url = text(viewModel.urlProperty().get());
            if (url.isEmpty()) return "请填写 MCP 端点 URL。";
            try {
                URI uri = URI.create(url);
                if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme()))) {
                    return "MCP 端点需为有效的 http:// 或 https:// URL。";
                }
            } catch (IllegalArgumentException failure) {
                return "MCP 端点 URL 格式不正确。";
            }
            String duplicate = duplicate(headers);
            return duplicate.isEmpty() ? "" : "Header 名称「" + duplicate + "」重复。";
        }
        if (text(viewModel.commandProperty().get()).isEmpty()) return "请填写 stdio 服务的启动命令。";
        String duplicate = duplicate(environment);
        return duplicate.isEmpty() ? "" : "环境变量名称「" + duplicate + "」重复。";
    }

    private void showTestResult(TestResult result) {
        if (!result.success()) {
            viewModel.testResultProperty().set("✗ 连接失败：" + result.errorMessage());
            setResultStyle("status-error");
            return;
        }
        String tools = result.tools().stream().limit(8).map(tool -> tool.name())
                .collect(java.util.stream.Collectors.joining(", "));
        String suffix = tools.isBlank() ? "" : "：\n  " + tools
                + (result.tools().size() > 8 ? ", ..." : "");
        viewModel.testResultProperty().set("✓ 连接成功（" + result.elapsedMs() + "ms），发现 "
                + result.tools().size() + " 个工具" + suffix);
        setResultStyle("status-success");
    }

    private void addEnvironment(String key, String value) {
        addRow(environment, environmentRows, key, value);
    }

    private void addHeader(String key, String value) {
        addRow(headers, headerRows, key, value);
    }

    private void addRow(List<McpKeyValueRowFactory.Row> target, VBox container,
                        String key, String value) {
        McpKeyValueRowFactory.Row[] holder = new McpKeyValueRowFactory.Row[1];
        McpKeyValueRowFactory.Row row = rows.create(key, value, isLikelySecret(key), () -> {
            target.remove(holder[0]);
            container.getChildren().remove(holder[0].root());
            holder[0].close();
        });
        holder[0] = row;
        target.add(row);
        container.getChildren().add(row.root());
    }

    private void applyTransport() {
        boolean http = viewModel.httpProperty().get();
        httpButton.setSelected(http);
        stdioButton.setSelected(!http);
        show(stdioSection, !http);
        show(httpSection, http);
    }

    private void refreshPreview() {
        String command = text(viewModel.commandProperty().get());
        String joined = String.join(" ", arguments());
        commandPreview.setText("$ " + (command.isEmpty() ? "<command>" : command)
                + (joined.isEmpty() ? "" : " " + joined));
    }

    private List<String> arguments() {
        String source = viewModel.argumentsProperty().get();
        if (source == null || source.isBlank()) return List.of();
        return source.lines().map(String::strip).filter(value -> !value.isEmpty()).toList();
    }

    private static Map<String, String> values(List<McpKeyValueRowFactory.Row> source) {
        Map<String, String> result = new LinkedHashMap<>();
        for (McpKeyValueRowFactory.Row row : source) {
            String key = text(row.controller().key());
            if (!key.isEmpty()) result.put(key, row.controller().value());
        }
        return result;
    }

    private static String duplicate(List<McpKeyValueRowFactory.Row> source) {
        Set<String> keys = new HashSet<>();
        for (McpKeyValueRowFactory.Row row : source) {
            String key = text(row.controller().key());
            if (!key.isEmpty() && !keys.add(key)) return key;
        }
        return "";
    }

    private void setResultStyle(String style) {
        testResultLabel.getStyleClass().removeAll("status-info", "status-success", "status-error");
        testResultLabel.getStyleClass().add(style);
    }

    private void clearRows() {
        closeRows(environment, environmentRows);
        closeRows(headers, headerRows);
    }

    private static void closeRows(List<McpKeyValueRowFactory.Row> source, VBox container) {
        for (int index = source.size() - 1; index >= 0; index--) source.get(index).close();
        source.clear();
        if (container != null) container.getChildren().clear();
    }

    private static boolean isLikelySecret(String key) {
        String upper = key == null ? "" : key.toUpperCase(java.util.Locale.ROOT);
        return upper.contains("KEY") || upper.contains("TOKEN") || upper.contains("SECRET")
                || upper.contains("PASSWORD") || upper.contains("PASSWD");
    }

    private static String text(String value) { return value == null ? "" : value.strip(); }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static String failureMessage(Throwable failure) {
        return failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                ? "未知错误" : failure.getMessage();
    }

    @Override
    public void close() {
        testAction.close();
        clearRows();
        nameField.textProperty().unbindBidirectional(viewModel.nameProperty());
        commandField.textProperty().unbindBidirectional(viewModel.commandProperty());
        argumentsArea.textProperty().unbindBidirectional(viewModel.argumentsProperty());
        urlField.textProperty().unbindBidirectional(viewModel.urlProperty());
        enabledCheck.selectedProperty().unbindBidirectional(viewModel.enabledProperty());
        errorLabel.textProperty().unbind();
        testResultLabel.textProperty().unbind();
        testButton.disableProperty().unbind();
        viewModel.testingProperty().unbind();
    }
}
