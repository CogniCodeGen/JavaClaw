package com.javaclaw.desktop.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebChatScrollingTest {
    @Test
    void 小步真实上滚退出跟随且快照刷新保留阅读位置直到主动回到最新() {
        Fixture fixture = open(32);
        try {
            FxTestSupport.run(() -> {
                assertTrue(fixture.number("scrollY") > 500);
                fixture.wheelUp();
            });
            FxTestSupport.await(() -> FxTestSupport.call(fixture::readingHistory));
            awaitScrollStopped(fixture);
            FxTestSupport.run(() -> {
                assertTrue(fixture.distanceFromBottom() > 0);
                assertTrue(fixture.distanceFromBottom() < 70, "必须覆盖被原先底部宽容区吞掉的小步滚动");
                fixture.captureAnchor();
                fixture.show(0, 32, "2", "追加正文\n".repeat(12));
            });
            awaitSnapshot(fixture);
            FxTestSupport.run(() -> {
                fixture.assertAnchor();
                assertTrue(fixture.readingHistory());
                fixture.script("document.querySelector('.new-messages').click()");
                assertTrue(fixture.distanceFromBottom() <= 2);
                fixture.show(0, 33, "3", "新消息");
            });
            awaitSnapshot(fixture);
            FxTestSupport.run(() -> {
                assertTrue(fixture.distanceFromBottom() <= 2);
                assertEquals("following:true", fixture.actions.getLast());
                fixture.wheelUp();
            });
            FxTestSupport.await(() -> FxTestSupport.call(fixture::readingHistory));
            scrollDownToLatest(fixture);
        } finally {
            FxTestSupport.run(fixture::close);
        }
    }

    @Test
    void 上滚后立即更新快照不会在下一次滚动事件前夺回底部() {
        Fixture fixture = open(32);
        try {
            FxTestSupport.run(() -> {
                fixture.wheelUp();
                fixture.show(0, 32, "2", "立即流式追加\n".repeat(20));
            });
            awaitSnapshot(fixture);
            FxTestSupport.run(() -> {
                assertTrue(fixture.readingHistory());
                assertTrue(fixture.distanceFromBottom() > 70);
            });
        } finally {
            FxTestSupport.run(fixture::close);
        }
    }

    @Test
    void 上翻大型虚拟历史并插入更早消息后锚点及有界DOM均保留() {
        Fixture fixture = open(500);
        try {
            FxTestSupport.run(() -> fixture.script("window.scrollTo(0,0)"));
            FxTestSupport.await(() -> FxTestSupport.call(() -> fixture.readingHistory()
                    && Boolean.TRUE.equals(
                            fixture.script("document.querySelector('article').dataset.id==='message:0'"))));
            FxTestSupport.run(() -> {
                fixture.captureAnchor();
                fixture.show(-20, 500, "2", "");
            });
            awaitSnapshot(fixture);
            FxTestSupport.run(() -> {
                fixture.assertAnchor();
                assertTrue(fixture.number("document.querySelectorAll('article').length") <= 128);
                assertTrue(fixture.readingHistory());
                fixture.host.retry();
            });
            awaitSnapshot(fixture);
            FxTestSupport.run(() -> {
                assertTrue(fixture.readingHistory());
                assertTrue(Boolean.TRUE.equals(fixture.script("Array.from(document.querySelectorAll('article'))"
                        + ".some(node=>node.dataset.id==='message:0' && node.getBoundingClientRect().bottom>0)")));
            });
        } finally {
            FxTestSupport.run(fixture::close);
        }
    }

    @Test
    void 附件显示为链接且点击只发送受控引用而不跳转页面() {
        Fixture fixture = open(32);
        try {
            FxTestSupport.run(() -> {
                assertEquals("A", fixture.script("document.querySelector('.attachment').tagName"));
                fixture.script("document.querySelector('.attachment').click()");
                assertEquals("preview:reference:0", fixture.actions.getLast());
                assertEquals("", fixture.script("location.hash"));
            });
        } finally {
            FxTestSupport.run(fixture::close);
        }
    }

    @Test
    void 窗口和字体变化夹紧滚动位置时保持跟随并让新消息继续到底部() {
        Fixture fixture = open(32);
        try {
            double height = FxTestSupport.call(() -> fixture.number("innerHeight"));
            FxTestSupport.run(() -> {
                fixture.stage.setHeight(fixture.stage.getHeight() + 140);
                fixture.stage.setWidth(fixture.stage.getWidth() + 180);
                fixture.script("window.JavaClawSurface.theme({fontSize:'10px'})");
            });
            FxTestSupport.await(() -> FxTestSupport.call(
                    () -> fixture.number("innerHeight") > height && fixture.distanceFromBottom() <= 2));
            FxTestSupport.run(() -> {
                assertTrue(fixture.actions.stream().noneMatch("following:false"::equals));
                fixture.show(0, 33, "2", "追加正文\n".repeat(12));
            });
            awaitSnapshot(fixture);
            FxTestSupport.run(() -> {
                assertTrue(fixture.distanceFromBottom() <= 2);
                assertTrue(fixture.actions.stream().noneMatch("following:false"::equals));
            });
        } finally {
            FxTestSupport.run(fixture::close);
        }
    }

    @Test
    void 阅读历史时窗口增高即使到达新底部也不能恢复跟随() {
        Fixture fixture = open(32);
        try {
            FxTestSupport.run(fixture::wheelUp);
            FxTestSupport.await(() -> FxTestSupport.call(fixture::readingHistory));
            double height = FxTestSupport.call(() -> fixture.number("innerHeight"));
            FxTestSupport.run(() -> fixture.stage.setHeight(fixture.stage.getHeight() + 140));
            FxTestSupport.await(() -> FxTestSupport.call(() -> fixture.number("innerHeight") > height));
            FxTestSupport.run(() -> fixture.show(0, 33, "2", "追加正文\n".repeat(12)));
            awaitSnapshot(fixture);
            FxTestSupport.run(() -> {
                assertTrue(fixture.readingHistory());
                assertTrue(fixture.actions.stream().noneMatch("following:true"::equals));
            });
        } finally {
            FxTestSupport.run(fixture::close);
        }
    }

    private static Fixture open(int count) {
        Fixture fixture = FxTestSupport.call(() -> new Fixture(count));
        awaitSnapshot(fixture);
        return fixture;
    }

    private static void awaitSnapshot(Fixture fixture) {
        FxTestSupport.await(() -> FxTestSupport.call(fixture.host::acknowledged));
    }

    private static void awaitScrollStopped(Fixture fixture) {
        double[] previous = {Double.NaN};
        long[] changedAt = {System.nanoTime()};
        // 原生惯性继续移动属于用户输入；停止后再校验新快照是否保持阅读位置。
        FxTestSupport.await(() -> {
            double position = FxTestSupport.call(() -> fixture.number("scrollY"));
            if (Double.compare(previous[0], position) != 0) {
                previous[0] = position;
                changedAt[0] = System.nanoTime();
            }
            return System.nanoTime() - changedAt[0] >= 120_000_000L;
        });
    }

    private static void scrollDownToLatest(Fixture fixture) {
        // WebKit 的原生滚轮动画不保证反向等量输入恰好抵消；验证每一步真实下移，最终到底后恢复跟随。
        for (int step = 0; step < 8; step++) {
            double distance = FxTestSupport.call(fixture::distanceFromBottom);
            if (distance <= 2) {
                break;
            }
            FxTestSupport.run(() -> fixture.wheel(-10));
            FxTestSupport.await(() -> FxTestSupport.call(() -> fixture.distanceFromBottom() < distance));
        }
        FxTestSupport.await(() -> FxTestSupport.call(() ->
                fixture.distanceFromBottom() <= 2 && fixture.actions.getLast().equals("following:true")));
    }

    private static final class Fixture implements AutoCloseable {
        private final List<String> actions = new ArrayList<>();
        private final WebSurfaceHost host =
                new WebSurfaceHost("chat", new Label("简版"), (action, value) -> actions.add(action + ":" + value));
        private final Stage stage = new Stage();

        private Fixture(int count) {
            Scene scene = new Scene(host, 760, 500);
            DesktopStylesheets.apply(scene);
            stage.setScene(scene);
            stage.show();
            show(0, count, "1", "");
        }

        private void show(int first, int end, String version, String appended) {
            List<Map<String, Object>> rows = IntStream.range(first, end)
                    .mapToObj(index -> Map.<String, Object>of(
                            "id",
                            "message:" + index,
                            "version",
                            version,
                            "title",
                            "USER",
                            "style",
                            "message-user",
                            "text",
                            "第 " + index + " 条正文\n第二行正文\n第三行正文" + (index == end - 1 ? appended : ""),
                            "references",
                            index == 0 ? List.of(Map.of("id", "reference:0", "label", "查看附件")) : List.of()))
                    .toList();
            host.show(
                    "history", new CanonicalJson().encode(Map.of("items", rows)).json());
        }

        private WebView web() {
            return host.getChildren().stream()
                    .filter(WebView.class::isInstance)
                    .map(WebView.class::cast)
                    .findFirst()
                    .orElseThrow();
        }

        private void wheelUp() {
            wheel(10);
        }

        private void wheel(double deltaY) {
            WebView view = web();
            // 使用 JavaFX 公开事件进入真正 WebKit，不依赖系统焦点或仅用 scrollTo 模拟滚轮。
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
                            deltaY,
                            0,
                            deltaY,
                            ScrollEvent.HorizontalTextScrollUnits.NONE,
                            0,
                            ScrollEvent.VerticalTextScrollUnits.NONE,
                            0,
                            0,
                            new PickResult(view, 200, 200)));
        }

        private Object script(String source) {
            return web().getEngine().executeScript(source);
        }

        private double number(String expression) {
            return ((Number) script(expression)).doubleValue();
        }

        private double distanceFromBottom() {
            return number("document.documentElement.scrollHeight-scrollY-innerHeight");
        }

        private boolean readingHistory() {
            return actions.contains("following:false")
                    && Boolean.TRUE.equals(script("document.querySelector('.new-messages')!==null"));
        }

        private void captureAnchor() {
            script(
                    "window.testAnchorNode=Array.from(document.querySelectorAll('article'))"
                            + ".find(node=>node.getBoundingClientRect().bottom>0 && node.getBoundingClientRect().top<innerHeight);"
                            + "window.testAnchorId=testAnchorNode.dataset.id;window.testAnchorOffset=testAnchorNode.getBoundingClientRect().top");
        }

        private void assertAnchor() {
            assertTrue(
                    Boolean.TRUE.equals(
                            script(
                                    "Array.from(document.querySelectorAll('article')).some(node=>"
                                            + "node.dataset.id===testAnchorId && Math.abs(node.getBoundingClientRect().top-testAnchorOffset)<2)")));
        }

        @Override
        public void close() {
            host.close();
            stage.close();
        }
    }
}
