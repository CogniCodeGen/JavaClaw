package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.application.plugin.PluginManagementApplicationService.ConfigField;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/** 动态插件配置字段的 FXML Controller；秘密值只进入 PasswordField。 */
public final class PluginConfigFieldController {

    @FXML private Label fieldLabel;
    @FXML private TextField textField;
    @FXML private PasswordField secretField;

    private ConfigField field;

    void configure(ConfigField value, String current) {
        field = java.util.Objects.requireNonNull(value, "value");
        fieldLabel.setText(value.label() + (value.secret() ? "（加密存储）" : ""));
        textField.setPromptText(value.key());
        secretField.setPromptText(value.key());
        textField.setVisible(!value.secret());
        textField.setManaged(!value.secret());
        secretField.setVisible(value.secret());
        secretField.setManaged(value.secret());
        activeField().setText(current == null ? "" : current);
    }

    String key() { return field.key(); }

    String value() { return activeField().getText(); }

    private TextField activeField() { return field.secret() ? secretField : textField; }
}
