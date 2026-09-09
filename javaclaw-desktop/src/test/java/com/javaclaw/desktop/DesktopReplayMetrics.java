package com.javaclaw.desktop;

import java.util.ArrayList;
import java.util.List;

import javafx.animation.AnimationTimer;
import javafx.event.Event;
import javafx.geometry.Point3D;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.scene.web.WebView;

/** 输入到布局完成和活动期间的 Pulse 间隔；不声称等同操作系统合成帧或光学可见时刻。 */
final class DesktopReplayMetrics implements AutoCloseable {
    private final Scene scene;
    private final List<Double> input = new ArrayList<>();
    private final List<Double> pulses = new ArrayList<>();
    private final Runnable afterLayout = this::layoutCompleted;
    private long queued;
    private long previous;
    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (previous != 0) {
                pulses.add((now - previous) / 1_000_000.0);
            }
            previous = now;
        }
    };

    DesktopReplayMetrics(Scene scene) {
        this.scene = scene;
        scene.addPostLayoutPulseListener(afterLayout);
        timer.start();
    }

    void type(TextArea editor, long started) {
        if (queued != 0) {
            throw new IllegalStateException("前一次输入未完成布局");
        }
        queued = started;
        editor.requestFocus();
        Event.fireEvent(
                editor, new KeyEvent(KeyEvent.KEY_TYPED, "字", "", KeyCode.UNDEFINED, false, false, false, false));
    }

    boolean inputCompleted() {
        return queued == 0;
    }

    private void layoutCompleted() {
        if (queued != 0) {
            input.add((System.nanoTime() - queued) / 1_000_000.0);
            queued = 0;
        }
    }

    static void wheel(WebView web, double delta) {
        Event.fireEvent(
                web,
                new ScrollEvent(
                        ScrollEvent.SCROLL,
                        220,
                        180,
                        220,
                        180,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        0,
                        delta,
                        0,
                        delta,
                        ScrollEvent.HorizontalTextScrollUnits.NONE,
                        0,
                        ScrollEvent.VerticalTextScrollUnits.NONE,
                        0,
                        0,
                        new PickResult(web, new Point3D(220, 180, 0), 1)));
    }

    List<Double> inputs() {
        return List.copyOf(input);
    }

    List<Double> pulses() {
        return List.copyOf(pulses);
    }

    static double percentile(List<Double> values, double quantile) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * quantile) - 1));
    }

    @Override
    public void close() {
        timer.stop();
        scene.removePostLayoutPulseListener(afterLayout);
    }
}
