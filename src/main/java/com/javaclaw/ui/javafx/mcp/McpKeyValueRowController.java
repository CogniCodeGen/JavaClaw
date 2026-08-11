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
