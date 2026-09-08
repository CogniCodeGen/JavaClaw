package com.javaclaw.desktop.settings;

import java.lang.ref.WeakReference;
import java.util.function.BooleanSupplier;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.util.Duration;

/** 可见页面15秒低频对账；通知负责及时失效，计时器只补偿漏通知且不会持有已离页的控件。 */
final class ViewPageReconciler {
    private ViewPageReconciler() {}

    static void install(Node node, BooleanSupplier allowed, Runnable action) {
        WeakReference<Node> owner = new WeakReference<>(node);
        WeakReference<BooleanSupplier> condition = new WeakReference<>(allowed);
        WeakReference<Runnable> callback = new WeakReference<>(action);
        // 页面拥有回调而计时器仅持有弱引用，避免关闭窗口后保活整棵控制器树。
        node.getProperties().put(ViewPageReconciler.class, new Object[] {allowed, action});
        Timeline timer = new Timeline();
        timer.getKeyFrames().add(new KeyFrame(Duration.seconds(15), event -> {
            Node current = owner.get();
            BooleanSupplier permitted = condition.get();
            Runnable refresh = callback.get();
            if (current == null || permitted == null || refresh == null) {
                timer.stop();
            } else if (visible(current) && permitted.getAsBoolean()) {
                refresh.run();
            }
        }));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private static boolean visible(Node node) {
        if (node.getScene() == null
                || node.getScene().getWindow() == null
                || !node.getScene().getWindow().isShowing()) {
            return false;
        }
        for (Node current = node; current != null; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
        }
        return true;
    }
}
