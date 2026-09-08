package com.javaclaw.desktop.web;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.appearance.AppearancePreferenceStore;
import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.AppearanceTheme;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;
import com.javaclaw.desktop.appearance.FontScale;
import com.javaclaw.desktop.appearance.InterfaceDensity;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphAppearanceAcceptanceTest {
    @Test
    void 图谱Canvas节点随原生字号预览变化并在取消后恢复() {
        AtomicReference<WebSurfaceHost> host = new AtomicReference<>();
        AtomicReference<Stage> stage = new AtomicReference<>();
        AtomicReference<DesktopAppearanceManager> appearance = new AtomicReference<>();
        try {
            FxTestSupport.run(() -> {
                var surface = new WebSurfaceHost("graph", new Label("简版图谱"), (action, value) -> {});
                var manager = new DesktopAppearanceManager(new MemoryStore());
                Scene scene = new Scene(surface, 700, 520);
                DesktopStylesheets.apply(scene);
                manager.register(scene);
                Stage window = new Stage();
                window.setScene(scene);
                window.show();
                surface.show(
                        "workspace-graph",
                        new CanonicalJson()
                                .encode(Map.of(
                                        "elements", List.of(Map.of("data", Map.of("id", "a", "label", "中文记忆节点")))))
                                .json());
                host.set(surface);
                stage.set(window);
                appearance.set(manager);
            });
            FxTestSupport.await(() -> FxTestSupport.call(() -> host.get().acknowledged()));
            assertNodePainted(host.get());
            assertFont(host.get(), 13);
            preview(appearance.get(), FontScale.SMALL);
            assertFont(host.get(), 11.7);
            preview(appearance.get(), FontScale.EXTRA_LARGE);
            assertFont(host.get(), 15.6);
            FxTestSupport.run(() -> appearance.get().cancelPreview());
            assertFont(host.get(), 13);
        } finally {
            FxTestSupport.run(() -> {
                if (host.get() != null) {
                    host.get().close();
                    stage.get().close();
                }
            });
        }
    }

    private static void assertNodePainted(WebSurfaceHost host) {
        FxTestSupport.await(() -> FxTestSupport.call(() -> {
            var web = (WebView) host.getChildren().getFirst();
            int x = (int)
                    number(host, "document.getElementById('graph')._cyreg.cy.nodes().first().renderedPosition().x");
            int y = (int)
                    number(host, "document.getElementById('graph')._cyreg.cy.nodes().first().renderedPosition().y");
            var pixel = web.snapshot(null, null).getPixelReader().getColor(x, y);
            return pixel.getGreen() - pixel.getRed() > .2;
        }));
    }

    private static void preview(DesktopAppearanceManager manager, FontScale scale) {
        FxTestSupport.run(() ->
                manager.preview(new AppearancePreferences(AppearanceTheme.EMERALD, scale, InterfaceDensity.STANDARD)));
    }

    private static void assertFont(WebSurfaceHost host, double expected) {
        // 验证 Cytoscape 实际节点计算样式，而非只验证传入的 CSS 变量。
        FxTestSupport.await(() -> FxTestSupport.call(() -> Math.abs(number(
                                host,
                                "parseFloat(document.getElementById('graph')._cyreg.cy.nodes().first().style('font-size'))")
                        - expected)
                < .01));
        FxTestSupport.run(() -> {
            var web = (WebView) host.getChildren().getFirst();
            Object matches = web.getEngine().executeScript("""
                    document.getElementById('graph')._cyreg.cy.nodes().first().style('font-family')
                      === getComputedStyle(document.body).fontFamily
                    """);
            assertEquals(Boolean.TRUE, matches);
            assertTrue(web.snapshot(null, null).getWidth() > 300);
        });
    }

    private static double number(WebSurfaceHost host, String expression) {
        var web = (WebView) host.getChildren().getFirst();
        return ((Number) web.getEngine().executeScript(expression)).doubleValue();
    }

    private static final class MemoryStore implements AppearancePreferenceStore {
        @Override
        public AppearancePreferences load() {
            return AppearancePreferences.defaults();
        }

        @Override
        public void save(AppearancePreferences preferences) {
            throw new AssertionError("预览与取消不得写入偏好");
        }
    }
}
