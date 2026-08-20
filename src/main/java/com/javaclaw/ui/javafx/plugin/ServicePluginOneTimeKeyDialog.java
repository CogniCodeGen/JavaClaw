package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ApiKeyRotation;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Presents a newly rotated secret once without leaking it into persistent UI state. */
final class ServicePluginOneTimeKeyDialog {
    private ServicePluginOneTimeKeyDialog() { }

    static void show(VBox owner, String pluginName, ApiKeyRotation rotation) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("新 API Key");
        Scene scene = owner.getScene();
        if (scene != null && scene.getWindow() != null) dialog.initOwner(scene.getWindow());
        TextField key = new TextField(rotation.apiKey());
        key.setEditable(false);
        key.getStyleClass().addAll("settings-field", "service-plugin-secret");
        Button copy = new Button("复制");
        copy.getStyleClass().addAll("jc-btn", "jc-btn-primary", "jc-btn-sm");
        copy.setOnAction(ignored -> copy(rotation.apiKey()));
        HBox row = new HBox(8, key, copy);
        HBox.setHgrow(key, Priority.ALWAYS);
        dialog.getDialogPane().setContent(new VBox(10,
                hint(pluginName + " / " + rotation.endpointId() + " 的新密钥只显示这一次。"), row));
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        if (scene != null) dialog.getDialogPane().getStylesheets().addAll(scene.getStylesheets());
        dialog.showAndWait();
        key.clear();
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("settings-hint");
        return label;
    }

    private static void copy(String value) {
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
