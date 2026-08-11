package com.javaclaw.chat;

import com.javaclaw.app.UIHelper;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.control.Alert;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Loads the shortcut-help content from FXML and owns its controller until the dialog closes. */
public final class ChatShortcutHelpFactory {

    private static final URL VIEW = Objects.requireNonNull(
            ChatShortcutHelpFactory.class.getResource("/fxml/chat/chat-shortcut-help.fxml"),
            "缺少 chat-shortcut-help.fxml");

    private final SpringFxmlLoader loader;

    public ChatShortcutHelpFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /** Must run on the JavaFX application thread; the FXML handle is closed with the modal dialog. */
    public void show(Window owner, String shortcutKey) {
        ViewHandle<VBox> handle;
        try {
            handle = loader.load(VIEW);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载快捷键帮助 FXML 失败", failure);
        }
        AtomicBoolean released = new AtomicBoolean();
        try {
            handle.controller(ChatShortcutHelpController.class).configure(shortcutKey);
            Alert dialog = new Alert(Alert.AlertType.INFORMATION);
            dialog.setTitle("键盘快捷键");
            dialog.setHeaderText("JavaClaw 快捷键");
            dialog.getDialogPane().setContent(handle.root());
            if (owner != null) {
                dialog.initOwner(owner);
            }
            dialog.setOnHidden(event -> release(handle, released));
            UIHelper.styleAlert(dialog);
            dialog.showAndWait();
            release(handle, released);
        } catch (RuntimeException | Error failure) {
            release(handle, released);
            throw failure;
        }
    }

    private static void release(ViewHandle<?> handle, AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            handle.close();
        }
    }
}
