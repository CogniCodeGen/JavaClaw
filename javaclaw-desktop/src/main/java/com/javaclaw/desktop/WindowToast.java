package com.javaclaw.desktop;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.scene.control.Label;
import javafx.util.Duration;

/** 当前窗口内的非阻断提示；新提示会替换旧动画，不抢占键盘焦点。 */
final class WindowToast {
    private final Label label;
    private SequentialTransition active;

    WindowToast(Label label) {
        this.label = java.util.Objects.requireNonNull(label, "label");
        label.setMouseTransparent(true);
        label.setVisible(false);
        label.setManaged(false);
    }

    void info(String message) {
        show(message, "window-toast-info");
    }

    void success(String message) {
        show(message, "window-toast-success");
    }

    void error(String message) {
        show(message, "window-toast-error");
    }

    private void show(String message, String kind) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (active != null) {
            active.stop();
        }
        label.getStyleClass().removeAll("window-toast-info", "window-toast-success", "window-toast-error");
        label.getStyleClass().add(kind);
        label.setText(message);
        label.setAccessibleText("提示：" + message);
        label.setOpacity(1);
        label.setVisible(true);
        label.setManaged(true);
        if (Boolean.getBoolean("javaclaw.ui.reduceMotion")) {
            active = new SequentialTransition(new PauseTransition(Duration.seconds(2)));
        } else {
            var appear = new FadeTransition(Duration.millis(120), label);
            appear.setFromValue(0.72);
            appear.setToValue(1);
            var stay = new PauseTransition(Duration.seconds(1.8));
            var disappear = new FadeTransition(Duration.millis(180), label);
            disappear.setFromValue(1);
            disappear.setToValue(0);
            active = new SequentialTransition(appear, stay, disappear);
        }
        SequentialTransition shown = active;
        shown.setOnFinished(ignored -> {
            if (active == shown) {
                label.setVisible(false);
                label.setManaged(false);
                label.setOpacity(1);
                active = null;
            }
        });
        shown.play();
    }
}
