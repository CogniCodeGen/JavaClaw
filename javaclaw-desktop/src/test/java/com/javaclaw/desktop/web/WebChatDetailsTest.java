package com.javaclaw.desktop.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 在真实 WebKit 中验证折叠状态、虚拟化高度和跨会话恢复，不通过静态 HTML 推测可见结果。 */
class WebChatDetailsTest {
    @Test
    void 空加载后旧未确认卡回补恢复阅读锚点而真正新发送仍跟随尾部() {
        List<Map<String, Object>> messages = rows(80);
        messages.add(outgoing("old-send", "UNCONFIRMED", 7));
        try (Fixture fixture = new Fixture(messages)) {
            fixture.awaitReady();
            FxTestSupport.run(() -> fixture.script("""
                    const target = document.querySelector('[data-id=row-6]');
                    window.scrollTo(0, target.offsetTop + 12);
                    """));
            fixture.await("!!document.querySelector('.new-messages')"
                    + " && Math.abs(document.querySelector('[data-id=row-6]').getBoundingClientRect().top + 12) < 2");
            double savedPosition = FxTestSupport.call(() -> fixture.number("scrollY"));
            fixture.show("other", List.of(plain("other", "另一会话")));
            fixture.awaitReady();
            fixture.show("details", List.of());
            fixture.awaitReady();
            fixture.show("details", messages);
            fixture.awaitReady();
            FxTestSupport.run(() -> {
                assertEquals(savedPosition, fixture.number("scrollY"), 1);
                assertTrue(fixture.truth("!!document.querySelector('.new-messages')"));
            });
            Map<String, Object> old = messages.removeLast();
            fixture.show("details", messages);
            fixture.awaitReady();
            messages.add(old);
            fixture.show("details", messages);
            fixture.awaitReady();
            FxTestSupport.run(() -> assertEquals(savedPosition, fixture.number("scrollY"), 1));
            messages.add(outgoing("new-send", "SENDING", 8));
            fixture.show("details", messages);
            fixture.awaitReady();
            fixture.await("document.documentElement.scrollHeight - innerHeight - scrollY <= 2");
            FxTestSupport.run(() -> assertTrue(fixture.truth("!document.querySelector('.new-messages')")));
        }
    }

    private static Map<String, Object> outgoing(String id, String status, long attempt) {
        Map<String, Object> row = new LinkedHashMap<>(plain(id, "发送原文"));
        row.put("outgoing", status);
        row.put("sendAttempt", attempt);
        return row;
    }

    @Test
    void 默认折叠工具详情并在展开折叠时保持阅读锚点() {
        try (Fixture fixture = new Fixture(rows(30))) {
            fixture.awaitReady();
            FxTestSupport.run(() -> fixture.script("window.scrollTo(0, 0)"));
            fixture.await("window.scrollY < 1");
            FxTestSupport.run(() -> {
                assertTrue(fixture.truth("document.querySelector('.tool-details-body').hidden"));
                fixture.script("""
                        window.tool = document.querySelector('[data-id=tool]');
                        window.toolTop = tool.getBoundingClientRect().top;
                        window.collapsedHeight = tool.getBoundingClientRect().height;
                        tool.querySelector('.tool-details-toggle').click();
                        """);
                assertTrue(fixture.truth("!tool.querySelector('.tool-details-body').hidden"));
                assertEquals(fixture.number("toolTop"), fixture.number("tool.getBoundingClientRect().top"), 1);
                assertTrue(fixture.number("tool.getBoundingClientRect().height") > fixture.number("collapsedHeight"));
                fixture.script("tool.querySelector('.tool-details-toggle').click()");
                assertEquals(fixture.number("toolTop"), fixture.number("tool.getBoundingClientRect().top"), 1);
                assertEquals(
                        fixture.number("collapsedHeight"), fixture.number("tool.getBoundingClientRect().height"), 1);
            });
        }
    }

    @Test
    void 详情展开在虚拟化重挂与切换会话后按消息身份恢复() {
        List<Map<String, Object>> messages = rows(140);
        try (Fixture fixture = new Fixture(messages)) {
            fixture.awaitReady();
            FxTestSupport.run(() -> fixture.script("window.scrollTo(0, 0)"));
            fixture.await("!!document.querySelector('[data-id=tool]')");
            FxTestSupport.run(() -> {
                fixture.script("document.querySelector('.tool-details-toggle').click()");
                fixture.script("window.dispatchEvent(new WheelEvent('wheel',{deltaY:-1}))");
                fixture.script("document.querySelector('.new-messages').click()");
            });
            fixture.await("!document.querySelector('[data-id=tool]')");
            FxTestSupport.run(() -> fixture.script("window.scrollTo(0, 0)"));
            fixture.await("!!document.querySelector('[data-id=tool]')");
            FxTestSupport.run(() -> assertTrue(fixture.truth("!document.querySelector('.tool-details-body').hidden")));
            Map<String, Object> updatedTool = new LinkedHashMap<>(messages.getFirst());
            updatedTool.put("version", "2");
            updatedTool.put("details", updatedTool.get("details") + "新增技术详情");
            messages.set(0, updatedTool);
            fixture.show("details", messages);
            fixture.awaitReady();
            FxTestSupport.run(() -> {
                assertTrue(fixture.truth("!document.querySelector('.tool-details-body').hidden"));
                assertTrue(
                        fixture.truth("document.querySelector('.tool-details-body').textContent.includes('新增技术详情')"));
            });
            fixture.show("other", List.of(plain("other", "另一会话")));
            fixture.awaitReady();
            // 真实切换会话先发布空快照，再异步读取历史；空窗口不能作为删除 Item 展开状态的证据。
            fixture.show("details", List.of());
            fixture.awaitReady();
            fixture.show("details", messages);
            fixture.awaitReady();
            fixture.await("!!document.querySelector('[data-id=tool]')");
            FxTestSupport.run(() -> {
                assertTrue(fixture.truth("!document.querySelector('.tool-details-body').hidden"));
                assertTrue(fixture.number("document.querySelectorAll('article').length") <= 128);
                assertTrue(fixture.number("Number(document.getElementById('surface').dataset.heightCacheSize)") <= 140);
            });
        }
    }

    @Test
    void 工具详情只以文本显示且恢复按钮随宿主草稿状态原位变化() {
        Map<String, Object> outgoing = new LinkedHashMap<>(plain("send-opaque", "发送原文"));
        outgoing.put("outgoing", "UNCONFIRMED");
        List<Map<String, Object>> messages = new ArrayList<>(rows(1));
        messages.add(outgoing);
        try (Fixture fixture = new Fixture(messages)) {
            fixture.awaitReady();
            FxTestSupport.run(() -> {
                fixture.script("window.originalArticle = document.querySelector('[data-id=send-opaque]')");
                assertEquals(0, fixture.number("document.querySelectorAll('.tool-details-body img').length"));
                fixture.host.setOutgoingAvailability(true, false);
                assertTrue(fixture.truth("document.querySelector('[data-send-action=restoreSend]').disabled"));
                assertTrue(fixture.truth("!document.querySelector('[data-send-action=retrySend]').disabled"));
                fixture.host.setOutgoingAvailability(false, true);
                assertTrue(fixture.truth("!document.querySelector('[data-send-action=restoreSend]').disabled"));
                assertTrue(fixture.truth("document.querySelector('[data-send-action=retrySend]').disabled"));
                assertTrue(fixture.truth("!document.querySelector('[data-send-action=copySend]').disabled"));
                assertTrue(fixture.truth("originalArticle === document.querySelector('[data-id=send-opaque]')"));
            });
        }
    }

    private static List<Map<String, Object>> rows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> tool = new LinkedHashMap<>(plain("tool", ""));
        tool.put("title", "read_file · 成功");
        tool.put("style", "transcript-execution-block");
        tool.put("details", "<img src=x onerror=alert(1)>\n" + "结构化技术详情\n".repeat(15));
        tool.put("collapsible", true);
        rows.add(tool);
        for (int index = 1; index < count; index++) {
            rows.add(plain("row-" + index, "正文\n第二行\n第三行"));
        }
        return rows;
    }

    private static Map<String, Object> plain(String id, String text) {
        return Map.of("id", id, "version", "1", "title", "助手", "text", text, "style", "message-assistant");
    }

    private static final class Fixture implements AutoCloseable {
        private final WebSurfaceHost host;
        private final Stage stage;

        private Fixture(List<Map<String, Object>> rows) {
            host = FxTestSupport.call(() -> new WebSurfaceHost("chat", new Label("简版"), (action, value) -> {}));
            stage = FxTestSupport.call(() -> {
                Stage result = new Stage();
                Scene scene = new Scene(host, 760, 500);
                DesktopStylesheets.apply(scene);
                result.setScene(scene);
                result.show();
                return result;
            });
            show("details", rows);
        }

        private void show(String context, List<Map<String, Object>> rows) {
            FxTestSupport.run(() -> host.show(
                    context, new CanonicalJson().encode(Map.of("items", rows)).json()));
        }

        private void awaitReady() {
            FxTestSupport.await(() -> FxTestSupport.call(host::acknowledged));
        }

        private void await(String condition) {
            FxTestSupport.await(() -> FxTestSupport.call(() -> truth(condition)));
        }

        private Object script(String source) {
            return engine().executeScript(source);
        }

        private boolean truth(String source) {
            return Boolean.TRUE.equals(script(source));
        }

        private double number(String source) {
            return ((Number) script(source)).doubleValue();
        }

        private WebEngine engine() {
            return host.getChildren().stream()
                    .filter(WebView.class::isInstance)
                    .map(WebView.class::cast)
                    .findFirst()
                    .orElseThrow()
                    .getEngine();
        }

        @Override
        public void close() {
            FxTestSupport.run(() -> {
                host.close();
                stage.close();
            });
        }
    }
}
