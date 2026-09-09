package com.javaclaw.desktop.shell;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ShellWindowFocusTest {
    @Test
    void 场景尚未挂载窗口时允许订阅且后续获得焦点触发刷新() {
        try (Windows windows = FxTestSupport.call(Windows::new)) {
            FxTestSupport.run(() -> {
                assertNull(windows.scene.getWindow());
                windows.subscribe();
                assertEquals(0, windows.refreshed.get());
                windows.mount(windows.first);
            });
            windows.focus(windows.first);
            assertEquals(1, windows.refreshed.get());
            windows.focus(windows.second);
            assertEquals(1, windows.refreshed.get());

            windows.focus(windows.first);

            assertEquals(2, windows.refreshed.get());
        }
    }

    @Test
    void 绑定已聚焦窗口立即刷新且未挂载窗口前关闭不会留下监听() {
        try (Windows windows = FxTestSupport.call(Windows::new)) {
            FxTestSupport.run(() -> windows.mount(windows.first));
            windows.focus(windows.first);
            FxTestSupport.run(() -> {
                windows.subscribe();
                assertEquals(1, windows.refreshed.get());
                windows.subscription.close();
                windows.first.setScene(new Scene(new VBox(), 320, 200));
                assertNull(windows.scene.getWindow());
                windows.subscribe();
                windows.subscription.close();
                windows.mount(windows.second);
            });

            windows.focus(windows.second);

            assertEquals(1, windows.refreshed.get());
        }
    }

    @Test
    void 场景更换窗口后解绑旧窗口且关闭后不再绑定新窗口() {
        try (Windows windows = FxTestSupport.call(Windows::new)) {
            FxTestSupport.run(() -> {
                windows.mount(windows.first);
                windows.subscribe();
            });
            windows.focus(windows.first);
            FxTestSupport.run(() -> windows.mount(windows.second));
            windows.focus(windows.second);
            assertEquals(2, windows.refreshed.get());

            windows.focus(windows.first);

            assertEquals(2, windows.refreshed.get());
            windows.focus(windows.second);
            assertEquals(3, windows.refreshed.get());
            FxTestSupport.run(() -> {
                windows.subscription.close();
                windows.subscription.close();
            });
            windows.focus(windows.first);
            windows.focus(windows.second);
            assertEquals(3, windows.refreshed.get());
            FxTestSupport.run(() -> windows.mount(windows.third));
            windows.focus(windows.third);
            assertEquals(3, windows.refreshed.get());
        }
    }

    private static final class Windows implements AutoCloseable {
        private final Scene scene = new Scene(new VBox(), 320, 200);
        private final Stage first = window();
        private final Stage second = window();
        private final Stage third = window();
        private final AtomicInteger refreshed = new AtomicInteger();
        private final Map<Window, SimpleBooleanProperty> focusStates = new IdentityHashMap<>();
        private ShellWindowFocus subscription;

        private void subscribe() {
            subscription = new ShellWindowFocus(scene, refreshed::incrementAndGet, this::focusState);
        }

        private void mount(Stage target) {
            if (scene.getWindow() instanceof Stage previous) {
                previous.setScene(new Scene(new VBox(), 320, 200));
            }
            target.setScene(scene);
        }

        private SimpleBooleanProperty focusState(Window window) {
            return focusStates.computeIfAbsent(window, ignored -> new SimpleBooleanProperty());
        }

        private void focus(Stage target) {
            FxTestSupport.run(() -> {
                // 只替换焦点的可观测输入；Scene 归属仍由真实 Stage.setScene 驱动。
                for (Stage window : List.of(first, second, third)) {
                    if (window != target) {
                        focusState(window).set(false);
                    }
                }
                focusState(target).set(true);
            });
        }

        private static Stage window() {
            Stage stage = new Stage();
            stage.setScene(new Scene(new VBox(), 320, 200));
            return stage;
        }

        @Override
        public void close() {
            FxTestSupport.run(() -> {
                if (subscription != null) {
                    subscription.close();
                }
                List.of(first, second, third).forEach(Stage::hide);
            });
        }
    }
}
