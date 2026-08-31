package com.javaclaw.desktop;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.util.Duration;

/** 复用 509f197 情感语法的克制微动效；同一节点重复事件只保留最后一次动画。 */
final class UiMotion {
    private static final String ACTIVE = "javaclaw.ui-motion.active";
    private static final Duration FAST = Duration.millis(150);
    private static final Duration BASE = Duration.millis(220);
    private static final Interpolator EASE_OUT = Interpolator.SPLINE(0.16, 1, 0.3, 1);

    private UiMotion() {}

    static void fadeIn(Node node) {
        if (reduced(node)) {
            return;
        }
        stop(node);
        node.setOpacity(0.82);
        node.setTranslateY(4);
        var fade = new FadeTransition(BASE, node);
        fade.setToValue(1);
        fade.setInterpolator(EASE_OUT);
        var move = new TranslateTransition(BASE, node);
        move.setToY(0);
        move.setInterpolator(EASE_OUT);
        play(node, new ParallelTransition(fade, move), () -> {
            node.setOpacity(1);
            node.setTranslateY(0);
        });
    }

    static void success(Node node) {
        if (reduced(node)) {
            return;
        }
        stop(node);
        var scale = new ScaleTransition(FAST, node);
        scale.setFromX(1);
        scale.setFromY(1);
        scale.setToX(1.04);
        scale.setToY(1.04);
        scale.setAutoReverse(true);
        scale.setCycleCount(2);
        scale.setInterpolator(EASE_OUT);
        play(node, scale, () -> {
            node.setScaleX(1);
            node.setScaleY(1);
        });
    }

    static void error(Node node) {
        if (reduced(node)) {
            return;
        }
        stop(node);
        var shake = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(node.translateXProperty(), 0)),
                new KeyFrame(Duration.millis(55), new KeyValue(node.translateXProperty(), 5)),
                new KeyFrame(Duration.millis(110), new KeyValue(node.translateXProperty(), -4)),
                new KeyFrame(Duration.millis(165), new KeyValue(node.translateXProperty(), 3)),
                new KeyFrame(BASE, new KeyValue(node.translateXProperty(), 0)));
        play(node, shake, () -> node.setTranslateX(0));
    }

    private static boolean reduced(Node node) {
        if (node == null) {
            return true;
        }
        if (Boolean.getBoolean("javaclaw.ui.reduceMotion")) {
            node.setOpacity(1);
            node.setTranslateX(0);
            node.setTranslateY(0);
            node.setScaleX(1);
            node.setScaleY(1);
            return true;
        }
        return false;
    }

    private static void play(Node node, Animation animation, Runnable finish) {
        node.getProperties().put(ACTIVE, animation);
        animation.setOnFinished(ignored -> {
            if (node.getProperties().get(ACTIVE) == animation) {
                node.getProperties().remove(ACTIVE);
                finish.run();
            }
        });
        animation.play();
    }

    private static void stop(Node node) {
        Object previous = node.getProperties().remove(ACTIVE);
        if (previous instanceof Animation running) {
            running.stop();
        }
        node.setOpacity(1);
        node.setTranslateX(0);
        node.setTranslateY(0);
        node.setScaleX(1);
        node.setScaleY(1);
    }
}
