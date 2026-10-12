package com.javaclaw.ui.javafx.diagnostics;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;

/** 创建独立诊断窗口；每次打开都获得新的 prototype Controller 与关闭边界。 */
public final class DiagnosticsViewFactory {

    private static final URL VIEW = Objects.requireNonNull(
            DiagnosticsViewFactory.class.getResource("/fxml/diagnostics/diagnostics-view.fxml"),
            "缺少 diagnostics-view.fxml");

    private final SpringFxmlLoader loader;

    public DiagnosticsViewFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /** 必须在 FX 线程调用；创建失败时会立即销毁已经创建的 Controller。 */
    public DiagnosticsView open(Window owner) {
        ViewHandle<BorderPane> handle;
        try {
            handle = loader.load(VIEW);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载诊断窗口 FXML 失败", failure);
        }
        try {
            Stage stage = new Stage();
            if (owner != null) stage.initOwner(owner);
            stage.setTitle("诊断面板");
            Scene scene = new Scene(handle.root(), 960, 640);
            URL css = DiagnosticsViewFactory.class.getResource("/css/chat.css");
            if (css != null) scene.getStylesheets().add(css.toExternalForm());
            stage.setScene(scene);
            DiagnosticsView view = new DiagnosticsView(stage, handle);
            view.show();
            return view;
        } catch (RuntimeException | Error failure) {
            try {
                handle.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }
}
