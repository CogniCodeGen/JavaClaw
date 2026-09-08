package com.javaclaw.desktop.web;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSurfaceRecoveryTest {
    @Test
    void 绘制无确认五秒后只恢复一次并在再次失败时降级且不重执行业务() {
        AtomicReference<WebSurfaceHost> owner = new AtomicReference<>();
        AtomicReference<Stage> window = new AtomicReference<>();
        AtomicInteger business = new AtomicInteger();
        try {
            FxTestSupport.run(() -> {
                WebSurfaceHost host =
                        new WebSurfaceHost("chat", new Label("原生正文"), (action, value) -> business.incrementAndGet());
                Scene scene = new Scene(host, 700, 500);
                DesktopStylesheets.apply(scene);
                Stage stage = new Stage();
                stage.setScene(scene);
                stage.show();
                owner.set(host);
                window.set(stage);
                host.show("thread", "{}");
            });
            FxTestSupport.await(() -> FxTestSupport.call(() -> owner.get().acknowledged()));
            long generation = FxTestSupport.call(() -> owner.get().generation());
            FxTestSupport.run(() -> {
                web(owner.get()).getEngine().executeScript("window.JavaClawSurface.apply=function(){}");
                owner.get().show("thread", "{\"items\":[]}");
            });
            FxTestSupport.await(() -> FxTestSupport.call(
                    () -> owner.get().generation() > generation && owner.get().acknowledged()));
            FxTestSupport.run(() -> {
                web(owner.get())
                        .getEngine()
                        .executeScript("window.JavaClawSurface.apply=function(){throw Error('fault')}");
                owner.get().show("thread", "{\"items\":[],\"hasEarlier\":true}");
            });
            FxTestSupport.await(() ->
                    FxTestSupport.call(() -> owner.get().getChildren().stream().noneMatch(WebView.class::isInstance)));
            FxTestSupport.run(() -> {
                assertFalse(owner.get().acknowledged());
                assertTrue(owner.get().getChildren().getFirst().isVisible());
                assertEquals(0, business.get());
            });
        } finally {
            FxTestSupport.run(() -> {
                if (owner.get() != null) {
                    owner.get().close();
                    window.get().close();
                }
            });
        }
    }

    private static WebView web(WebSurfaceHost host) {
        return host.getChildren().stream()
                .filter(WebView.class::isInstance)
                .map(WebView.class::cast)
                .findFirst()
                .orElseThrow();
    }
}
