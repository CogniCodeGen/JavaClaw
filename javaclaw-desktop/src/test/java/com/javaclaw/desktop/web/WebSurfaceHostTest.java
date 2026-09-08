package com.javaclaw.desktop.web;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSurfaceHostTest {
    @Test
    void 真实WebKit确认显示并在数据更新和重建后保持有界窗口() {
        AtomicReference<WebSurfaceHost> owner = new AtomicReference<>();
        AtomicReference<Stage> window = new AtomicReference<>();
        try {
            FxTestSupport.run(() -> {
                WebSurfaceHost host = new WebSurfaceHost("chat", new Label("简版"), (action, value) -> {});
                Scene scene = new Scene(host, 760, 500);
                DesktopStylesheets.apply(scene);
                Stage stage = new Stage();
                stage.setScene(scene);
                stage.show();
                owner.set(host);
                window.set(stage);
                List<Map<String, Object>> items = history();
                host.show(
                        "thread:'安全",
                        new CanonicalJson().encode(Map.of("items", items)).json());
            });
            FxTestSupport.await(() -> FxTestSupport.call(() -> owner.get().acknowledged()));
            FxTestSupport.run(() -> {
                WebView web = (WebView) owner.get().getChildren().stream()
                        .filter(WebView.class::isInstance)
                        .findFirst()
                        .orElseThrow();
                Number count = (Number) web.getEngine().executeScript("document.querySelectorAll('article').length");
                assertTrue(count.intValue() > 0);
                assertTrue(count.intValue() <= 128);
                assertTrue(web.isVisible());
                owner.get().retry();
            });
            FxTestSupport.await(() -> FxTestSupport.call(() -> owner.get().acknowledged()));
            FxTestSupport.run(() -> {
                assertEquals(
                        1,
                        owner.get().getChildren().stream()
                                .filter(WebView.class::isInstance)
                                .count());
                owner.get().useFallback();
                assertFalse(owner.get().acknowledged());
                assertEquals(
                        0,
                        owner.get().getChildren().stream()
                                .filter(WebView.class::isInstance)
                                .count());
            });
        } finally {
            FxTestSupport.run(() -> {
                if (owner.get() != null) {
                    owner.get().close();
                }
                if (window.get() != null) {
                    window.get().close();
                }
            });
        }
    }

    @Test
    void 连续替换历史窗口时高度缓存同时淘汰已离开窗口的未挂载消息() {
        AtomicReference<WebSurfaceHost> owner = new AtomicReference<>();
        AtomicReference<Stage> window = new AtomicReference<>();
        try {
            FxTestSupport.run(() -> {
                WebSurfaceHost host = new WebSurfaceHost("chat", new Label("简版"), (action, value) -> {});
                Stage stage = new Stage();
                Scene scene = new Scene(host, 760, 500);
                DesktopStylesheets.apply(scene);
                stage.setScene(scene);
                stage.show();
                owner.set(host);
                window.set(stage);
            });
            for (int page = 0; page < 6; page++) {
                int current = page;
                FxTestSupport.run(() -> {
                    var rows = history().stream()
                            .map(row -> {
                                Map<String, Object> result = new java.util.LinkedHashMap<>(row);
                                result.put("id", current + ":" + row.get("id"));
                                return result;
                            })
                            .toList();
                    owner.get()
                            .show(
                                    "history",
                                    new CanonicalJson()
                                            .encode(Map.of("items", rows))
                                            .json());
                });
                FxTestSupport.await(() -> FxTestSupport.call(() -> owner.get().acknowledged()));
                FxTestSupport.run(() -> engine(owner.get()).executeScript("window.scrollTo(0,0)"));
                FxTestSupport.await(() -> FxTestSupport.call(() -> Boolean.TRUE.equals(engine(owner.get())
                        .executeScript("document.querySelector('article').dataset.id==='" + current + ":message:0'"))));
                FxTestSupport.run(() ->
                        engine(owner.get()).executeScript("window.scrollTo(0,document.documentElement.scrollHeight)"));
                FxTestSupport.run(() -> {
                    Number heights = (Number) engine(owner.get())
                            .executeScript("Number(document.getElementById('surface').dataset.heightCacheSize)");
                    assertTrue(heights.intValue() <= 500);
                });
            }
        } finally {
            FxTestSupport.run(() -> {
                if (owner.get() != null) {
                    owner.get().close();
                    window.get().close();
                }
            });
        }
    }

    private static javafx.scene.web.WebEngine engine(WebSurfaceHost owner) {
        return owner.getChildren().stream()
                .filter(WebView.class::isInstance)
                .map(WebView.class::cast)
                .findFirst()
                .orElseThrow()
                .getEngine();
    }

    private static List<Map<String, Object>> history() {
        return IntStream.range(0, 500)
                .mapToObj(index -> Map.<String, Object>of(
                        "id",
                        "message:" + index,
                        "version",
                        "1",
                        "title",
                        "USER",
                        "style",
                        "message-user",
                        "text",
                        "第 " + index + " 条正文",
                        "references",
                        List.of()))
                .toList();
    }

    @Test
    void 所有页面资源离线自包含并拒绝网络和子框架() {
        for (String kind : List.of("chat", "document", "graph")) {
            String html = WebSurfaceResources.page(kind);
            assertTrue(html.contains("default-src 'none'"));
            assertTrue(html.contains("connect-src 'none'"));
            assertTrue(html.contains("frame-src 'none'"));
            assertFalse(html.contains("<script src="));
            assertFalse(html.contains("<link rel="));
        }
    }
}
