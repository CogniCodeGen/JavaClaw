package com.javaclaw.desktop.shell;

import java.util.List;
import java.util.Optional;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.desktop.component.PlatformDialogs;

/** 用户确认 Worker 的脱敏表单引用；密码值不进入 JavaFX 对话框。 */
final class BrowserLoginCaptureDialog {
    private BrowserLoginCaptureDialog() {}

    static Optional<BrowserContracts.CredentialsTarget> choose(Window owner, List<BrowserContracts.LoginForm> forms) {
        return create(owner, forms).showAndWait();
    }

    static Dialog<BrowserContracts.CredentialsTarget> create(Window owner, List<BrowserContracts.LoginForm> forms) {
        Dialog<BrowserContracts.CredentialsTarget> dialog = new Dialog<>();
        dialog.setTitle("保存网页登录");
        Label explanation = new Label(
                forms.isEmpty()
                        ? "没有发现可保存的登录表单。可以回到浏览器控制栏仅保存登录态，或在管理中心手动录入密码。"
                        : "选择你刚使用的登录表单。确认后，用户名密码与当前登录态会保存到该账号的密钥库中。");
        explanation.setWrapText(true);
        ComboBox<BrowserContracts.LoginForm> choices = new ComboBox<>();
        choices.getItems().setAll(forms);
        choices.setMaxWidth(Double.MAX_VALUE);
        choices.setConverter(new StringConverter<>() {
            @Override
            public String toString(BrowserContracts.LoginForm form) {
                return form == null ? "" : form.label() + " · " + form.usernameLabel() + " / " + form.passwordLabel();
            }

            @Override
            public BrowserContracts.LoginForm fromString(String value) {
                throw new UnsupportedOperationException("表单必须从当前观察中选择");
            }
        });
        ButtonType save = new ButtonType("确认保存密码和登录态", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(new VBox(12, explanation, choices));
        dialog.getDialogPane().setPrefWidth(480);
        dialog.getDialogPane()
                .lookupButton(save)
                .disableProperty()
                .bind(choices.valueProperty().isNull());
        dialog.setResultConverter(button -> button == save ? choices.getValue().target() : null);
        PlatformDialogs.style(dialog, owner);
        return dialog;
    }
}
