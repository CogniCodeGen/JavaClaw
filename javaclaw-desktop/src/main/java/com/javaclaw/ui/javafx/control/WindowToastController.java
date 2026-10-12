package com.javaclaw.ui.javafx.control;

import com.javaclaw.platform.fx.FxDispatcher;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.util.Duration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Window Toast 的 FXML Controller，拥有单条动画及其取消边界。 */
public final class WindowToastController implements AutoCloseable {

    @FXML private Label messageLabel;

    private final FxDispatcher fx;
    private final WindowToastViewModel viewModel = new WindowToastViewModel();
    private final AtomicBoolean closed = new AtomicBoolean();
    private SequentialTransition animation;

    public WindowToastController(FxDispatcher fx) {
        this.fx = Objects.requireNonNull(fx, "fx");
    }

    @FXML
    private void initialize() {
        messageLabel.textProperty().bind(viewModel.messageProperty());
        messageLabel.visibleProperty().bind(viewModel.visibleProperty());
    }

    /** 非阻塞显示一条窗口内通知；可从任意线程调用。 */
    public void show(String text) {
        if (text == null || text.isBlank() || closed.get()) return;
        fx.dispatch(() -> animate(text));
    }

    private void animate(String text) {
        if (closed.get()) return;
        if (animation != null) animation.stop();
        viewModel.messageProperty().set(text);
        messageLabel.setOpacity(0);
        viewModel.visibleProperty().set(true);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(140), messageLabel);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        PauseTransition hold = new PauseTransition(Duration.seconds(2.4));
        FadeTransition fadeOut = new FadeTransition(Duration.millis(280), messageLabel);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);
        fadeOut.setOnFinished(event -> viewModel.visibleProperty().set(false));
        animation = new SequentialTransition(fadeIn, hold, fadeOut);
        animation.play();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        fx.dispatch(() -> {
            if (animation != null) animation.stop();
            animation = null;
            if (messageLabel != null) {
                messageLabel.textProperty().unbind();
                messageLabel.visibleProperty().unbind();
                messageLabel.setVisible(false);
                messageLabel.setText("");
            }
            viewModel.visibleProperty().set(false);
            viewModel.messageProperty().set("");
        });
    }

    boolean isClosed() { return closed.get(); }
}
