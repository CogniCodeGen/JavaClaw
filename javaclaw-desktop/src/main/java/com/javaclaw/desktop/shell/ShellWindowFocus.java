package com.javaclaw.desktop.shell;

import java.util.Objects;
import java.util.function.Function;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.Scene;
import javafx.stage.Window;

/** 跟随 Scene 所属窗口的生命周期，在主窗口重新获得焦点时刷新配置；构造和关闭均由 JavaFX 线程执行。 */
final class ShellWindowFocus implements AutoCloseable {
    private final Scene scene;
    private final Runnable refresh;
    private final Function<Window, ObservableBooleanValue> focusSource;
    private final ChangeListener<Window> windowListener = (ignored, previous, current) -> bind(current);
    private final ChangeListener<Boolean> focusListener = (ignored, previous, focused) -> {
        if (focused) {
            refresh();
        }
    };
    private Window window;
    private ObservableBooleanValue focused;
    private boolean closed;

    ShellWindowFocus(Scene scene, Runnable refresh) {
        this(scene, refresh, Window::focusedProperty);
    }

    // 注入焦点可观测值用于确定性生命周期测试；生产构造器始终绑定窗口的真实焦点属性。
    ShellWindowFocus(Scene scene, Runnable refresh, Function<Window, ObservableBooleanValue> focusSource) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.focusSource = Objects.requireNonNull(focusSource, "focusSource");
        // 控制器先绑定 Scene，Application 随后才把 Scene 挂到 Stage；监听归属变化而不改变启动顺序。
        scene.windowProperty().addListener(windowListener);
        bind(scene.getWindow());
    }

    private void bind(Window current) {
        if (window == current) {
            return;
        }
        if (focused != null) {
            focused.removeListener(focusListener);
        }
        window = current;
        focused = window != null && !closed ? Objects.requireNonNull(focusSource.apply(window), "focused") : null;
        if (focused != null) {
            focused.addListener(focusListener);
            if (focused.get()) {
                refresh();
            }
        }
    }

    private void refresh() {
        if (!closed) {
            refresh.run();
        }
    }

    /** 释放 Scene 和 Window 监听；关闭后窗口切换或焦点变化都不再刷新。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        scene.windowProperty().removeListener(windowListener);
        bind(null);
    }
}
