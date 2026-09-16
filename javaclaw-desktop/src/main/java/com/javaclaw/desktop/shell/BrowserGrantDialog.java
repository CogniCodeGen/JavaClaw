package com.javaclaw.desktop.shell;

import java.util.function.BooleanSupplier;

import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Window;

import com.javaclaw.desktop.DesktopBrowserGateway;
import com.javaclaw.desktop.component.PlatformDialogs;

/** 来源授权窗口持有冻结对话面板；关闭后晚到回执不再更新窗口。 */
final class BrowserGrantDialog implements AutoCloseable {
    private final Dialog<Void> dialog = new Dialog<>();
    private final BrowserGrantPane pane;

    BrowserGrantDialog(
            Window owner,
            DesktopBrowserGateway gateway,
            DesktopBrowserGateway.Scope scope,
            BooleanSupplier current,
            Runnable changed,
            Runnable closed) {
        pane = new BrowserGrantPane(gateway, scope, current, changed);
        dialog.setTitle("当前对话 · 来源授权");
        dialog.getDialogPane().setPrefWidth(560);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        dialog.getDialogPane().setContent(pane);
        PlatformDialogs.style(dialog, owner);
        dialog.setOnHidden(event -> {
            pane.close();
            closed.run();
        });
    }

    void show() {
        dialog.show();
        pane.reload();
    }

    @Override
    public void close() {
        pane.close();
        dialog.close();
    }
}
