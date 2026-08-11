package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.application.mcp.McpManagementApplicationService;
import com.javaclaw.application.mcp.McpManagementApplicationService.ImportPreview;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

/** MCP JSON 导入预览 Controller；解析为纯内存操作，不访问数据库或运行时。 */
public final class McpImportDialogController {
    @FXML private TextField nameField;
    @FXML private TextArea jsonArea;
    @FXML private Label previewLabel;

    private final McpManagementApplicationService useCases;

    public McpImportDialogController(McpManagementApplicationService useCases) {
        this.useCases = java.util.Objects.requireNonNull(useCases, "useCases");
    }

    @FXML
    private void initialize() {
        nameField.textProperty().addListener((ignored, previous, value) -> refreshPreview());
        jsonArea.textProperty().addListener((ignored, previous, value) -> refreshPreview());
    }

    boolean validate() {
        try {
            ImportPreview preview = useCases.previewImport(json(), fallbackName());
            if (preview.servers().isEmpty()) throw new IllegalArgumentException("JSON 中未发现任何 MCP 服务器");
            showPreview(preview);
            return true;
        } catch (RuntimeException failure) {
            previewLabel.setText("⚠ " + failure.getMessage());
            setStyle("status-error");
            return false;
        }
    }

    String json() { return jsonArea.getText(); }
    String fallbackName() { return nameField.getText() == null ? "" : nameField.getText().strip(); }

    private void refreshPreview() {
        if (json() == null || json().isBlank()) {
            previewLabel.setText("");
            setStyle(null);
            return;
        }
        validate();
    }

    private void showPreview(ImportPreview preview) {
        StringBuilder text = new StringBuilder("将导入 ").append(preview.servers().size())
                .append(" 个服务器：\n");
        preview.servers().forEach(server -> text.append("  • ").append(server.name())
                .append("  →  ").append(server.transport() == McpManagementApplicationService.Transport.HTTP
                        ? server.url() : server.command() + " " + String.join(" ", server.arguments()))
                .append('\n'));
        previewLabel.setText(text.toString().strip());
        setStyle("status-success");
    }

    private void setStyle(String style) {
        previewLabel.getStyleClass().removeAll("status-success", "status-error");
        if (style != null) previewLabel.getStyleClass().add(style);
    }
}
