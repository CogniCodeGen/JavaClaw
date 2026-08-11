package com.javaclaw.ui.javafx.image;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 图片查看窗口与对应 FXML Controller 的统一关闭边界。 */
public final class ImageViewerView implements AutoCloseable {

    private final Stage stage;
    private final ViewHandle<BorderPane> handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    ImageViewerView(Stage stage, ViewHandle<BorderPane> handle) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.handle = Objects.requireNonNull(handle, "handle");
        stage.setOnHidden(event -> close());
    }

    public void show() {
        if (closed.get()) throw new IllegalStateException("图片查看窗口已关闭");
        stage.show();
    }

    public Stage stage() { return stage; }

    ImageViewerController controller() {
        return handle.controller(ImageViewerController.class);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stage.setOnHidden(null);
        if (stage.isShowing()) stage.hide();
        handle.close();
    }
}
