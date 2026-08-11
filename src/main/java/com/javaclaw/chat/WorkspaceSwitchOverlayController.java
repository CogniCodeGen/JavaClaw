package com.javaclaw.chat;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

import java.util.concurrent.atomic.AtomicBoolean;

/** FXML Controller：仅切换工作区遮罩状态，不参与运行时重建。 */
public final class WorkspaceSwitchOverlayController implements AutoCloseable {

    @FXML private StackPane root;
    @FXML private Label messageLabel;

    private final WorkspaceSwitchOverlayViewModel viewModel =
            new WorkspaceSwitchOverlayViewModel();
    private final AtomicBoolean closed = new AtomicBoolean();

    @FXML
    private void initialize() {
        root.visibleProperty().bind(viewModel.visibleProperty());
        root.managedProperty().bind(viewModel.visibleProperty());
        messageLabel.textProperty().bind(viewModel.messageProperty());
    }

    public void show(String message) {
        if (!closed.get()) viewModel.show(message);
    }

    public void hide() {
        if (!closed.get()) viewModel.hide();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) viewModel.hide();
    }

    boolean isClosed() { return closed.get(); }
}
