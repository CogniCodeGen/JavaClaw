package com.javaclaw.ui.javafx.mcp;

import javafx.fxml.FXML;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;

/** 可复用的 MCP key/value FXML 编辑行。 */
public final class McpKeyValueRowController implements AutoCloseable {
    @FXML private TextField keyField;
    @FXML private TextField plainValueField;
    @FXML private PasswordField secretValueField;
    @FXML private ToggleButton secretButton;

    private final McpKeyValueViewModel viewModel = new McpKeyValueViewModel();
    private Runnable removeAction = () -> { };

    @FXML
    private void initialize() {
        keyField.textProperty().bindBidirectional(viewModel.keyProperty());
        plainValueField.textProperty().bindBidirectional(viewModel.valueProperty());
        secretValueField.textProperty().bindBidirectional(viewModel.valueProperty());
        viewModel.secretProperty().addListener((ignored, previous, secret) -> applySecret(secret));
        viewModel.keyProperty().addListener((ignored, previous, key) -> {
            // 新增或修改敏感字段名后先遮罩；用户之后仍可显式切换为明文。
            if (isLikelySecret(key)) viewModel.secretProperty().set(true);
        });
    }

    static boolean isLikelySecret(String key) {
        String upper = key == null ? "" : key.strip().toUpperCase(java.util.Locale.ROOT);
        // MCP HTTP 的 Authorization 携带访问令牌，应与环境密钥使用相同的默认遮罩。
        return upper.equals("AUTHORIZATION") || upper.contains("KEY") || upper.contains("TOKEN")
                || upper.contains("SECRET") || upper.contains("PASSWORD") || upper.contains("PASSWD");
    }

    void configure(String key, String value, boolean secret, Runnable removeAction) {
        viewModel.keyProperty().set(key == null ? "" : key);
        viewModel.valueProperty().set(value == null ? "" : value);
        viewModel.secretProperty().set(secret);
        this.removeAction = java.util.Objects.requireNonNull(removeAction, "removeAction");
        applySecret(secret);
    }

    String key() { return viewModel.keyProperty().get(); }
    String value() { return viewModel.valueProperty().get(); }

    @FXML
    private void secretRequested() {
        viewModel.secretProperty().set(secretButton.isSelected());
    }

    @FXML private void removeRequested() { removeAction.run(); }

    private void applySecret(boolean secret) {
        secretButton.setSelected(secret);
        secretButton.setText(secret ? "🔒" : "👁");
        plainValueField.setVisible(!secret);
        plainValueField.setManaged(!secret);
        secretValueField.setVisible(secret);
        secretValueField.setManaged(secret);
    }

    @Override
    public void close() {
        keyField.textProperty().unbindBidirectional(viewModel.keyProperty());
        plainValueField.textProperty().unbindBidirectional(viewModel.valueProperty());
        secretValueField.textProperty().unbindBidirectional(viewModel.valueProperty());
        removeAction = () -> { };
        viewModel.valueProperty().set("");
    }
}
