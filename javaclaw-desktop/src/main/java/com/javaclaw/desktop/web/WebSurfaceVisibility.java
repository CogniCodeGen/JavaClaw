package com.javaclaw.desktop.web;

import java.util.ArrayList;
import java.util.List;

import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;

/** 只通过层级、尺寸和窗口事件唤醒宿主；隐藏页面不保留轮询计时器。 */
final class WebSurfaceVisibility implements AutoCloseable {
    private final Node owner;
    private final Runnable changed;
    private final List<Observable> observed = new ArrayList<>();
    private final InvalidationListener invalidated = ignored -> watch();

    WebSurfaceVisibility(Node owner, Runnable changed) {
        this.owner = owner;
        this.changed = changed;
    }

    void watch() {
        observed.forEach(value -> value.removeListener(invalidated));
        observed.clear();
        observe(owner.sceneProperty());
        observe(owner.layoutBoundsProperty());
        for (Node node = owner; node != null; node = node.getParent()) {
            observe(node.visibleProperty());
            observe(node.parentProperty());
        }
        Scene scene = owner.getScene();
        if (scene != null) {
            observe(scene.windowProperty());
            Window window = scene.getWindow();
            if (window != null) {
                observe(window.showingProperty());
                if (window instanceof Stage stage) {
                    observe(stage.iconifiedProperty());
                }
            }
        }
        changed.run();
    }

    boolean visible() {
        Scene scene = owner.getScene();
        if (scene == null
                || scene.getWindow() == null
                || !scene.getWindow().isShowing()
                || owner.getLayoutBounds().getWidth() <= 0
                || owner.getLayoutBounds().getHeight() <= 0) {
            return false;
        }
        if (scene.getWindow() instanceof Stage stage && stage.isIconified()) {
            return false;
        }
        for (Node node = owner; node != null; node = node.getParent()) {
            if (!node.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private void observe(Observable value) {
        observed.add(value);
        value.addListener(invalidated);
    }

    @Override
    public void close() {
        observed.forEach(value -> value.removeListener(invalidated));
        observed.clear();
    }
}
