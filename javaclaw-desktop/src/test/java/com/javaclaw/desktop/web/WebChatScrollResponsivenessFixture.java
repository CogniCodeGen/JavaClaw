package com.javaclaw.desktop.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Worker;
import javafx.event.Event;
import javafx.geometry.Point3D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;

import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.protocol.CanonicalJson;

/** 两个同尺寸真实 WebView 接收同一事件流；基准页没有业务滚动回调，所有方法均由 JavaFX 线程调用。 */
final class WebChatScrollResponsivenessFixture implements AutoCloseable {
    static final int EVENT_COUNT = 24;
    static final double DELTA_PIXELS = 12;
    static final double PERIOD_MILLIS = 16;

    private final WebSurfaceHost host = new WebSurfaceHost("chat", new Label("简版"), (action, value) -> {});
    private final WebView baseline = new WebView();
    private final Stage stage = new Stage();
    private final CompletableFuture<Void> streamed = new CompletableFuture<>();
    private final int count;
    private Timeline stream;

    WebChatScrollResponsivenessFixture(int count) {
        this.count = count;
        try {
            baseline.getEngine()
                    .setUserDataDirectory(WebSurfaceRuntime.directory().toFile());
        } catch (IOException failure) {
            throw new AssertionError("不能创建基准 WebKit profile", failure);
        }
        host.setPrefWidth(600);
        baseline.setPrefWidth(600);
        HBox root = new HBox(host, baseline);
        HBox.setHgrow(host, Priority.ALWAYS);
        HBox.setHgrow(baseline, Priority.ALWAYS);
        Scene scene = new Scene(root, 1200, 500);
        DesktopStylesheets.apply(scene);
        stage.setScene(scene);
        stage.show();
        host.show("scroll-measurement", payload(count));
    }

    boolean ready() {
        return host.acknowledged();
    }

    void positionChat() {
        script(chat(), "window.scrollTo(0,(document.documentElement.scrollHeight-innerHeight)/2)");
    }

    void loadBaseline() {
        double height = number(chat(), "document.documentElement.scrollHeight");
        baseline.getEngine()
                .loadContent("<!doctype html><html><head><meta charset='UTF-8'>"
                        + "<style>html,body{margin:0;padding:0}body{background:#faf9f6}</style></head>"
                        + "<body><div style='height:" + height + "px'>原生 WebKit 滚动基准</div></body></html>");
    }

    boolean baselineReady() {
        return baseline.getEngine().getLoadWorker().getState() == Worker.State.SUCCEEDED;
    }

    void alignBaseline() {
        script(baseline, "window.scrollTo(0," + number(chat(), "scrollY") + ")");
    }

    Positions positions() {
        return new Positions(number(chat(), "scrollY"), baselineReady() ? number(baseline, "scrollY") : 0);
    }

    void observeAndStart() {
        observe(chat());
        observe(baseline);
        stream = new Timeline(new KeyFrame(Duration.millis(PERIOD_MILLIS), event -> {
            wheel(chat());
            wheel(baseline);
        }));
        stream.setCycleCount(EVENT_COUNT);
        stream.setOnFinished(event -> streamed.complete(null));
        stream.playFromStart();
    }

    void appendTail() {
        observe(chat());
        host.show("scroll-measurement", payload(count, "\n追加的回复内容"));
    }

    boolean streamFinished() {
        return streamed.isDone();
    }

    Metrics chatMetrics() {
        return metrics(chat());
    }

    Metrics baselineMetrics() {
        return metrics(baseline);
    }

    private WebView chat() {
        return host.getChildren().stream()
                .filter(WebView.class::isInstance)
                .map(WebView.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static void observe(WebView view) {
        script(view, """
                (() => {
                    const state = window.scrollProbe = {wheels:0,added:0,removed:0,remounted:0,scrollBy:0};
                    const removed = new WeakSet();
                    const observer = new MutationObserver(records => {
                        for (const record of records) {
                            for (const node of record.removedNodes) {
                                if (node.nodeType === 1 && node.matches('article')) {
                                    state.removed++; removed.add(node);
                                }
                            }
                            for (const node of record.addedNodes) {
                                if (node.nodeType === 1 && node.matches('article')) {
                                    state.added++; if (removed.has(node)) { state.remounted++; }
                                }
                            }
                        }
                    });
                    observer.observe(document.getElementById('surface') || document.body, {childList:true});
                    window.addEventListener('wheel', () => state.wheels++, {passive:true});
                    const scrollBy = window.scrollBy;
                    window.scrollBy = (...args) => { state.scrollBy++; return scrollBy.apply(window,args); };
                })()
                """);
    }

    private static Metrics metrics(WebView view) {
        return new Metrics(
                number(view, "scrollY"),
                (int) number(view, "scrollProbe.wheels"),
                (int) number(view, "scrollProbe.added"),
                (int) number(view, "scrollProbe.removed"),
                (int) number(view, "scrollProbe.remounted"),
                (int) number(view, "scrollProbe.scrollBy"),
                (int) number(view, "document.querySelectorAll('article').length"));
    }

    private static void wheel(WebView view) {
        // 相同像素 delta 经 JavaFX 的公开事件路径进入 WebKit；不调用 scrollTo 代替测量输入。
        Event.fireEvent(
                view,
                new ScrollEvent(
                        ScrollEvent.SCROLL,
                        200,
                        200,
                        200,
                        200,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        0,
                        DELTA_PIXELS,
                        0,
                        DELTA_PIXELS,
                        ScrollEvent.HorizontalTextScrollUnits.NONE,
                        0,
                        ScrollEvent.VerticalTextScrollUnits.NONE,
                        0,
                        0,
                        new PickResult(view, new Point3D(200, 200, 0), 0)));
    }

    private static Object script(WebView view, String source) {
        return view.getEngine().executeScript(source);
    }

    private static double number(WebView view, String expression) {
        return ((Number) script(view, expression)).doubleValue();
    }

    private static String payload(int count) {
        return payload(count, "");
    }

    private static String payload(int count, String tail) {
        List<Map<String, Object>> rows = IntStream.range(0, count)
                .mapToObj(index -> Map.<String, Object>of(
                        "id",
                        "message:" + index,
                        "version",
                        index == count - 1 && !tail.isEmpty() ? "2" : "1",
                        "title",
                        "USER",
                        "style",
                        "message-user",
                        "text",
                        "第 " + index + " 条正文\n第二行正文\n第三行正文" + (index == count - 1 ? tail : ""),
                        "references",
                        List.of()))
                .toList();
        return new CanonicalJson().encode(Map.of("items", rows)).json();
    }

    @Override
    public void close() {
        if (stream != null) {
            stream.stop();
        }
        host.close();
        baseline.getEngine().load(null);
        stage.close();
    }

    record Positions(double chat, double baseline) {}

    record Metrics(double scrollY, int wheels, int added, int removed, int remounted, int scrollBy, int articles) {}
}
