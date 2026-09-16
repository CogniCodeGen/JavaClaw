package com.javaclaw.desktop.settings;

import java.util.Objects;

import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ScrollPane;

import com.javaclaw.desktop.component.PlatformDialogs;

/** 共享凭据由独立平台窗口管理，关闭或切换作用域时清空临时秘密。 */
final class SiteCredentialSettingsDialog {
    private final SiteCredentialSettingsSection section;
    private Dialog<Void> dialog;

    SiteCredentialSettingsDialog(SiteCredentialSettingsSection section) {
        this.section = Objects.requireNonNull(section, "section");
    }

    void show(Node owner) {
        if (dialog != null) {
            return;
        }
        Dialog<Void> window = new Dialog<>();
        dialog = window;
        window.setTitle("管理共享凭据");
        window.setHeaderText("共享 HTTP 凭据库");
        window.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ScrollPane scroll = new ScrollPane(section.content());
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(620);
        scroll.setPrefViewportHeight(480);
        window.getDialogPane().setContent(scroll);
        window.setResizable(true);
        window.setOnCloseRequest(event -> {
            if (section.pending() || (section.dirty() && !ViewSchemaConfirmation.discard(section.content()))) {
                event.consume();
            }
        });
        window.setOnHidden(event -> {
            section.deactivate();
            scroll.setContent(null);
            dialog = null;
        });
        PlatformDialogs.style(window, owner);
        window.show();
        section.activate();
    }

    /** 页面释放时只清理本地窗口；未知写入结果不通过关闭动作重新发送。 */
    void dispose() {
        if (dialog != null) {
            dialog.setOnCloseRequest(null);
            dialog.close();
        }
        section.deactivate();
    }
}
