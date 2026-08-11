package com.javaclaw.ui.javafx.control;

import javafx.animation.PauseTransition;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.util.Duration;

/** 可复用密钥输入控件的 FXML Controller；关闭时解除绑定并清除敏感文本。 */
public final class SecretFieldController implements AutoCloseable {

    @FXML private PasswordField secretField;
    @FXML private TextField plainField;
    @FXML private Button revealButton;
    @FXML private Button copyButton;

    private final PauseTransition copiedReset = new PauseTransition(Duration.millis(1200));

    @FXML
    private void initialize() {
        plainField.textProperty().bindBidirectional(secretField.textProperty());
        plainField.promptTextProperty().bind(secretField.promptTextProperty());
    }

    @FXML
    private void revealRequested() {
        boolean reveal = !plainField.isVisible();
        plainField.setVisible(reveal);
        plainField.setManaged(reveal);
        secretField.setVisible(!reveal);
        secretField.setManaged(!reveal);
        revealButton.setText(reveal ? "隐藏" : "显示");
        (reveal ? plainField : secretField).requestFocus();
    }

    @FXML
    private void copyRequested() {
        var content = new javafx.scene.input.ClipboardContent();
        content.putString(text());
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        copyButton.setText("已复制");
        copyButton.getStyleClass().add("field-adorn-active");
        copiedReset.stop();
        copiedReset.setOnFinished(event -> {
            copyButton.setText("复制");
            copyButton.getStyleClass().remove("field-adorn-active");
        });
        copiedReset.playFromStart();
    }

    public String text() {
        String value = secretField.getText();
        return value == null ? "" : value;
    }

    public void setText(String value) {
        secretField.setText(value == null ? "" : value);
    }

    public StringProperty textProperty() {
        return secretField.textProperty();
    }

    public void setPromptText(String value) {
        secretField.setPromptText(value == null ? "" : value);
    }

    @Override
    public void close() {
        copiedReset.stop();
        plainField.textProperty().unbindBidirectional(secretField.textProperty());
        plainField.promptTextProperty().unbind();
        secretField.clear();
        plainField.clear();
    }
}
