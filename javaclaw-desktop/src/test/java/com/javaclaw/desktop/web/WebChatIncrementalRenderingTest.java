package com.javaclaw.desktop.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 直接向真实 WebKit 提交协议补丁，验证输入期间的节点、选区和滚动状态不被重建破坏。 */
class WebChatIncrementalRenderingTest {
    @Test
    void 失败中间帧被合并后同状态重试仍回到尾部而确认刷新保留阅读位置() {
        List<Map<String, Object>> rows = new ArrayList<>();
        IntStream.range(0, 32).forEach(index -> rows.add(plain("old-" + index, "旧消息\n".repeat(8))));
        Map<String, Object> sending = new LinkedHashMap<>(plain("outgoing", "刚刚发送的消息"));
        sending.put("outgoing", "SENDING");
        sending.put("sendAttempt", 1);
        rows.add(sending);
        try (Fixture fixture = open(rows)) {
            FxTestSupport.run(() -> fixture.script("""
                    window.dispatchEvent(new WheelEvent('wheel', {deltaY:-240}));
                    window.scrollTo(0, 0);
                    """));
            fixture.await("window.scrollY < 1 && !!document.querySelector('.new-messages')");
            FxTestSupport.run(() -> {
                // 跳过失败帧；同一幂等消息、相同正文和状态，仅新尝试号代表再次主动发送。
                sending.put("sendAttempt", 2);
                sending.put("version", "retry-2");
                fixture.patch(List.of(sending), List.of(), null);
            });
            fixture.await("document.documentElement.scrollHeight - innerHeight - scrollY <= 2");
            FxTestSupport.run(() -> {
                assertTrue(fixture.truth(
                        "document.querySelector('[data-id=outgoing]').getBoundingClientRect().bottom <= innerHeight"));
                fixture.script("""
                        window.dispatchEvent(new WheelEvent('wheel', {deltaY:-240}));
                        window.scrollTo(0, 0);
                        """);
            });
            fixture.await("window.scrollY < 1 && !!document.querySelector('.new-messages')");
            FxTestSupport.run(() -> {
                sending.put("outgoing", "ACCEPTED");
                sending.put("version", "accepted-2");
                fixture.patch(List.of(sending), List.of(), null);
                assertTrue(fixture.number("scrollY") < 1);
            });
        }
    }

    @Test
    void 跨两条消息的反向选区在任一正文重解析后保留端点和方向() {
        try (Fixture fixture = open(List.of(history(), streaming("1", "已经解析", "新")))) {
            FxTestSupport.run(() -> {
                fixture.script("""
                        window.firstParagraph = document.querySelector('[data-id="history"] p');
                        window.lastParagraph = document.querySelector('[data-id="stream"] p');
                        getSelection().setBaseAndExtent(lastParagraph.firstChild, 2, firstParagraph.firstChild, 1);
                        window.selectedAcross = getSelection().toString();
                        window.firstEndpoint = getSelection().focusNode;
                        """);
                fixture.patch(List.of(streaming("2", "已经解析新内容", "尾部")), List.of(), null);
                assertEquals(fixture.script("selectedAcross"), fixture.script("getSelection().toString()"));
                assertTrue(fixture.truth("getSelection().focusNode === firstEndpoint"));
                assertTrue(fixture.truth(
                        "getSelection().anchorNode === document.querySelector('[data-id=stream] p').firstChild"
                                + " && getSelection().anchorOffset === 2"));
                fixture.script("window.lastEndpoint = getSelection().anchorNode");
                Map<String, Object> updated = new LinkedHashMap<>(history());
                updated.put("version", "2");
                updated.put("html", "<div>" + updated.get("html") + "</div>");
                fixture.patch(List.of(updated), List.of(), null);
                assertEquals(fixture.script("selectedAcross"), fixture.script("getSelection().toString()"));
                assertTrue(fixture.truth("getSelection().anchorNode === lastEndpoint"));
                assertTrue(fixture.truth(
                        "getSelection().focusNode === document.querySelector('[data-id=history] p').firstChild"
                                + " && getSelection().focusOffset === 1"));
            });
        }
    }

    @Test
    void 流式尾部原位追加且历史选区代码横滚和消息节点保持原样() {
        try (Fixture fixture = open(List.of(history(), streaming("1", "已经解析", "新")))) {
            FxTestSupport.run(() -> {
                fixture.script("""
                        window.historyArticle = document.querySelector('[data-id="history"]');
                        window.streamArticle = document.querySelector('[data-id="stream"]');
                        window.historyBody = historyArticle.querySelector('.message-body');
                        window.suffixNode = streamArticle.querySelector('span.plain').firstChild;
                        window.codeBlock = historyArticle.querySelector('pre');
                        codeBlock.scrollLeft = 90;
                        const range = document.createRange();
                        range.selectNodeContents(historyArticle.querySelector('p'));
                        getSelection().removeAllRanges(); getSelection().addRange(range);
                        window.selectedBefore = getSelection().toString();
                        window.codeOffset = codeBlock.scrollLeft;
                        """);
                assertTrue(fixture.number("codeOffset") > 0);
                fixture.patch(List.of(streaming("2", "已经解析", "新增正文😀")), List.of(), null);
                assertTrue(fixture.truth("historyArticle === document.querySelector('[data-id=history]')"));
                assertTrue(fixture.truth("streamArticle === document.querySelector('[data-id=stream]')"));
                assertTrue(fixture.truth("suffixNode === streamArticle.querySelector('span.plain').firstChild"));
                assertEquals("新增正文😀", fixture.script("streamArticle.querySelector('span.plain').textContent"));
                assertEquals(fixture.script("selectedBefore"), fixture.script("getSelection().toString()"));
                assertEquals(fixture.number("codeOffset"), fixture.number("codeBlock.scrollLeft"));
                assertEquals("你", fixture.script("historyArticle.querySelector('.role').textContent"));
            });
        }
    }

    @Test
    void Markdown重解析仅更新正文并保留同消息选区和代码横滚() {
        try (Fixture fixture = open(List.of(history()))) {
            FxTestSupport.run(() -> {
                fixture.script("""
                        window.originalArticle = document.querySelector('article');
                        window.originalBody = originalArticle.querySelector('.message-body');
                        originalArticle.querySelector('pre').scrollLeft = 80;
                        window.originalCodeOffset = originalArticle.querySelector('pre').scrollLeft;
                        const range = document.createRange();
                        range.selectNodeContents(originalArticle.querySelector('p'));
                        getSelection().removeAllRanges(); getSelection().addRange(range);
                        window.originalSelection = getSelection().toString();
                        """);
                Map<String, Object> updated = new LinkedHashMap<>(history());
                updated.put("version", "2");
                updated.put("html", updated.get("html") + "<p>补充说明</p>");
                fixture.patch(List.of(updated), List.of(), null);
                assertTrue(fixture.truth("originalArticle === document.querySelector('article')"));
                assertTrue(fixture.truth("originalBody === document.querySelector('.message-body')"));
                assertEquals(fixture.script("originalSelection"), fixture.script("getSelection().toString()"));
                assertEquals(
                        fixture.number("originalCodeOffset"),
                        fixture.number("document.querySelector('pre').scrollLeft"));
            });
        }
    }

    @Test
    void 纯文本截断仍保留存在的选区和原文本节点() {
        try (Fixture fixture = open(List.of(plain("text", "保留的正文😀（稍后截断）")))) {
            FxTestSupport.run(() -> {
                fixture.script("""
                        window.originalText = document.querySelector('.message-body').firstChild;
                        const range = document.createRange();
                        range.setStart(originalText, 0);
                        const walker = document.createTreeWalker(document.querySelector('.message-body'), NodeFilter.SHOW_TEXT);
                        let remaining = '保留的正文😀'.length;
                        let end;
                        while ((end = walker.nextNode())) {
                            if (remaining <= end.length) { range.setEnd(end, remaining); break; }
                            remaining -= end.length;
                        }
                        getSelection().removeAllRanges(); getSelection().addRange(range);
                        """);
                Map<String, Object> updated = new LinkedHashMap<>(plain("text", "保留的正文😀"));
                updated.put("version", "2");
                fixture.patch(List.of(updated), List.of(), null);
                assertTrue(fixture.truth("originalText === document.querySelector('.message-body').firstChild"));
                assertEquals("保留的正文😀", fixture.script("getSelection().toString()"));
                assertEquals("保留的正文😀", fixture.script("document.querySelector('.message-body').textContent"));
            });
        }
    }

    @Test
    void 补丁基线或上下文错误时拒绝整次提交且完整快照可以恢复() {
        try (Fixture fixture = open(List.of(history()))) {
            FxTestSupport.run(() -> {
                String before = fixture.script("document.getElementById('surface').textContent");
                Map<String, Object> replacement = streaming("2", "新解析", "尾部");
                Map<String, Object> patch = Map.of(
                        "mode",
                        "patch",
                        "baseRevision",
                        99,
                        "upserts",
                        List.of(replacement),
                        "removedIds",
                        List.of("history"),
                        "order",
                        List.of("stream"));
                assertFalse(fixture.apply("incremental", 2, patch));
                assertEquals(before, fixture.script("document.getElementById('surface').textContent"));
                Map<String, Object> valid = new LinkedHashMap<>(patch);
                valid.put("baseRevision", 1);
                assertFalse(fixture.apply("other-context", 2, valid));
                assertTrue(fixture.apply("incremental", 2, valid));
                assertEquals("stream", fixture.script("document.querySelector('article').dataset.id"));
                assertTrue(fixture.apply("incremental", 4, Map.of("mode", "replace", "items", List.of(history()))));
                assertEquals("history", fixture.script("document.querySelector('article').dataset.id"));
            });
        }
    }

    @Test
    void 旧空快照可以清空页面但显式替换仍要求消息列表() {
        try (Fixture fixture = open(List.of(history()))) {
            FxTestSupport.run(() -> {
                assertFalse(fixture.apply("incremental", 2, Map.of("mode", "replace")));
                assertEquals(1, fixture.number("document.querySelectorAll('article').length"));
                assertTrue(fixture.apply("incremental", 2, Map.of()));
                assertEquals(0, fixture.number("document.querySelectorAll('article').length"));
            });
        }
    }

    @Test
    void 虚拟占位使用当前实际外边距且主题变化后重新测量窗口() {
        List<Map<String, Object>> rows = IntStream.range(0, 140)
                .mapToObj(index -> plain("row-" + index, "正文\n第二行\n第三行"))
                .toList();
        try (Fixture fixture = open(rows)) {
            FxTestSupport.run(() -> fixture.script("window.scrollTo(0,0)"));
            fixture.await("document.querySelector('article').dataset.id === 'row-0'");
            FxTestSupport.run(() -> {
                fixture.script("""
                        window.firstHeight = document.querySelector('article').getBoundingClientRect().height;
                        window.realMargins = parseFloat(getComputedStyle(document.querySelector('article')).marginTop)
                                + parseFloat(getComputedStyle(document.querySelector('article')).marginBottom);
                        document.querySelector('.new-messages').click();
                        """);
                assertEquals(
                        (fixture.number("firstHeight") + fixture.number("realMargins")) * 12,
                        fixture.number("document.querySelector('.chat-spacer').getBoundingClientRect().height"),
                        1);
                fixture.script("window.JavaClawSurface.theme({fontSize:'21px'})");
            });
            fixture.await("document.querySelector('article').getBoundingClientRect().height > firstHeight"
                    + " && document.documentElement.scrollHeight - scrollY - innerHeight <= 2");
            FxTestSupport.run(() -> fixture.script("window.scrollTo(0,0)"));
            fixture.await("document.querySelector('article').dataset.id === 'row-0'");
            FxTestSupport.run(() -> {
                fixture.script("""
                        window.resizedHeight = document.querySelector('article').getBoundingClientRect().height;
                        window.resizedMargins = parseFloat(getComputedStyle(document.querySelector('article')).marginTop)
                                + parseFloat(getComputedStyle(document.querySelector('article')).marginBottom);
                        document.querySelector('.new-messages').click();
                        """);
                assertEquals(
                        (fixture.number("resizedHeight") + fixture.number("resizedMargins")) * 12,
                        fixture.number("document.querySelector('.chat-spacer').getBoundingClientRect().height"),
                        1);
                assertTrue(fixture.number("document.querySelectorAll('article').length") <= 128);
                assertTrue(fixture.number("Number(document.getElementById('surface').dataset.heightCacheSize)") <= 140);
            });
        }
    }

    private static Map<String, Object> history() {
        Map<String, Object> row = new LinkedHashMap<>(plain("history", "稳定的中文历史😀"));
        row.put(
                "html",
                "<p>稳定的中文历史😀</p><pre><code class='language-plaintext'>" + "long_expression = ".repeat(35)
                        + "</code></pre>");
        return row;
    }

    private static Map<String, Object> plain(String id, String text) {
        return Map.of(
                "id",
                id,
                "version",
                "1",
                "title",
                "USER",
                "style",
                "message-user",
                "text",
                text,
                "references",
                List.of());
    }

    private static Map<String, Object> streaming(String version, String parsed, String suffix) {
        Map<String, Object> row = new LinkedHashMap<>(plain("stream", parsed + suffix));
        row.put("version", version);
        row.put("title", "ASSISTANT");
        row.put("style", "message-assistant");
        row.put("streaming", true);
        row.put("streamHtml", "<p>" + parsed + "</p>");
        row.put("streamSuffix", suffix);
        return row;
    }

    private static Fixture open(List<Map<String, Object>> rows) {
        Fixture fixture = FxTestSupport.call(() -> new Fixture(rows));
        try {
            FxTestSupport.await(() -> FxTestSupport.call(fixture.host::acknowledged));
            return fixture;
        } catch (RuntimeException | Error failure) {
            fixture.close();
            throw failure;
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final WebSurfaceHost host = new WebSurfaceHost("chat", new Label("简版"), (action, value) -> {});
        private final Stage stage = new Stage();
        private int revision = 1;

        private Fixture(List<Map<String, Object>> rows) {
            Scene scene = new Scene(host, 640, 500);
            DesktopStylesheets.apply(scene);
            stage.setScene(scene);
            stage.show();
            host.show(
                    "incremental",
                    new CanonicalJson().encode(Map.of("items", rows)).json());
        }

        private void patch(List<Map<String, Object>> rows, List<String> removed, List<String> order) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("mode", "patch");
            data.put("baseRevision", revision);
            data.put("upserts", rows);
            data.put("removedIds", removed);
            if (order != null) {
                data.put("order", order);
            }
            assertTrue(apply("incremental", ++revision, data));
        }

        private boolean apply(String context, int version, Map<String, Object> data) {
            CanonicalJson json = new CanonicalJson();
            return truth("window.JavaClawSurface.apply(("
                    + json.encode(Map.of("value", context)).json() + ").value," + version + ","
                    + json.encode(data).json() + ")");
        }

        private String script(String source) {
            return String.valueOf(engine().executeScript(source));
        }

        private boolean truth(String source) {
            return Boolean.TRUE.equals(engine().executeScript(source));
        }

        private double number(String source) {
            return ((Number) engine().executeScript(source)).doubleValue();
        }

        private void await(String condition) {
            FxTestSupport.await(() -> FxTestSupport.call(() -> truth(condition)));
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
