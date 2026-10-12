package com.javaclaw.ui.javafx.loop;

import com.javaclaw.loop.model.LoopStatus;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.HBox;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

/** 在 JavaFX Application Thread 创建带初始快照的循环状态视图。 */
public final class LoopStatusViewFactory {

    private static final URL TEMPLATE = Objects.requireNonNull(
            LoopStatusViewFactory.class.getResource("/fxml/chat/loop-status.fxml"),
            "缺少循环状态 FXML");

    private final SpringFxmlLoader loader;

    public LoopStatusViewFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public LoopStatusView create(LoopStatus initialStatus) {
        ViewHandle<HBox> handle;
        try {
            handle = loader.load(TEMPLATE);
        } catch (IOException failure) {
            throw new IllegalStateException("加载循环状态 FXML 失败", failure);
        }
        try {
            LoopStatusController controller = handle.controller(LoopStatusController.class);
            LoopStatusView view = new LoopStatusView(handle, controller);
            controller.attach(view);
            controller.update(Objects.requireNonNull(initialStatus, "initialStatus"));
            return view;
        } catch (RuntimeException | Error failure) {
            try {
                handle.close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }
}
