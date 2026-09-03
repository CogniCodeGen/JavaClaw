package com.javaclaw.desktop.settings;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.SecretStatusField;

/** Provider 密钥状态与一次性编辑控件；PasswordField 内容离开控件后会立即清零。 */
final class ProviderSecretSection {
    private final Consumer<char[]> writer;
    private final PasswordField secret = new PasswordField();
    private final HBox editor = new HBox(8);
    private final SecretStatusField status;
    private final FormSection content;

    ProviderSecretSection(PlatformComponentFactory components, Consumer<char[]> writer, Runnable clearer) {
        PlatformComponentFactory checked = Objects.requireNonNull(components, "components");
        this.writer = Objects.requireNonNull(writer, "writer");
        status = new SecretStatusField(this::open, Objects.requireNonNull(clearer, "clearer"));
        secret.setPromptText("只用于本次写入，不会回读");
        secret.setId("providerSecretInput");
        Button apply = checked.action("写入密钥", ActionStyle.PRIMARY, ActionSize.COMPACT);
        apply.setOnAction(event -> submit());
        Button cancel = checked.action("取消", ActionStyle.GHOST, ActionSize.COMPACT);
        cancel.setOnAction(event -> hide());
        HBox.setHgrow(secret, Priority.ALWAYS);
        editor.getChildren().addAll(secret, apply, cancel);
        content = new FormSection("访问凭据", "密钥会先加密再发送，保存后只显示状态，不能读取、复制或导出明文。");
        content.addField("密钥状态", status);
        content.addFullWidth(editor);
        hide();
    }

    Node content() {
        return content;
    }

    void render(boolean configured, boolean referencedButUnavailable, boolean actionsDisabled) {
        status.setConfigured(configured);
        if (referencedButUnavailable) {
            status.setStatusText("引用缺失或密钥库不可用");
        }
        status.setActionsDisabled(actionsDisabled);
    }

    private void open() {
        secret.clear();
        editor.setVisible(true);
        editor.setManaged(true);
        secret.requestFocus();
    }

    private void hide() {
        secret.clear();
        editor.setVisible(false);
        editor.setManaged(false);
    }

    private void submit() {
        char[] value = secret.getText().toCharArray();
        hide();
        try {
            writer.accept(value);
        } finally {
            Arrays.fill(value, '\0');
        }
    }
}
