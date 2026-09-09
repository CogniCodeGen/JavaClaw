package com.javaclaw.desktop.web;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 像素、字素和选区同时验收，防止 DOM 字符正确却仍显示成灰色条纹。 */
@EnabledOnOs(OS.MAC)
class WebEmojiRenderingTest {
    private static final List<String> EMOJI =
            List.of("😊", "😀", "📚", "🎉", "✅", "⚠️", "👨‍👩‍👧‍👦", "👍🏽", "🇨🇳", "1️⃣");

    @Test
    void 复制彩色字形仍得到原始Unicode并恢复测试前剪贴板() {
        String text = "复制原文 😊 👨‍👩‍👧‍👦 👍🏽 🇨🇳 1️⃣";
        try (Fixture fixture = new Fixture("chat")) {
            fixture.chat(text, false, false);
            fixture.awaitGlyphs(5);
            FxTestSupport.run(() -> {
                Clipboard clipboard = Clipboard.getSystemClipboard();
                Map<DataFormat, Object> previous = new LinkedHashMap<>();
                clipboard.getContentTypes().forEach(format -> previous.put(format, clipboard.getContent(format)));
                try {
                    fixture.script("""
                            const range = document.createRange();
                            range.selectNodeContents(document.querySelector('.message-body'));
                            getSelection().removeAllRanges(); getSelection().addRange(range);
                            """);
                    fixture.web().requestFocus();
                    fixture.web()
                            .fireEvent(
                                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "c", KeyCode.C, false, false, false, true));
                    assertEquals(text, clipboard.getString());
                } finally {
                    clipboard.setContent(previous);
                }
            });
        }
    }

    @Test
    void 中文混排和组合表情保留原文且每个字形都有真实彩色像素() throws Exception {
        String text = "你好！是的，我会讲故事！ 😊\n\n**我的故事能力：**\n\n"
                + "- 创意写作：根据主题、风格或角色创作故事。\n"
                + "- 表情检查：" + String.join(" ", EMOJI.subList(1, EMOJI.size()))
                + "\n\n或者你有其他任务需要帮助？ 📚";
        try (Fixture fixture = new Fixture("chat")) {
            fixture.chat(text, true, false);
            fixture.awaitGlyphs(11);
            FxTestSupport.run(() -> {
                String body = fixture.string("document.querySelector('.message-body').textContent");
                EMOJI.forEach(emoji -> assertTrue(body.contains(emoji)));
                fixture.script("""
                        const range = document.createRange();
                        range.selectNodeContents(document.querySelector('.message-body'));
                        getSelection().removeAllRanges(); getSelection().addRange(range);
                        """);
                assertEquals(
                        body.replace("\n", ""),
                        fixture.string("getSelection().toString()").replace("\n", ""));
                fixture.script("getSelection().removeAllRanges()");
            });
            fixture.assertColorPixels();
            fixture.save("chat-light.png");
            FxTestSupport.run(() -> fixture.script("""
                    window.JavaClawSurface.theme({page:'#191d21',card:'#22272b',body:'#e1e3e1',
                        title:'#eef0ee',assistantBorder:'#3d4543',muted:'#a5ada7',fontSize:'22px'});
                    """));
            fixture.awaitGlyphs(11);
            fixture.assertColorPixels();
            fixture.save("chat-dark.png");
        }
    }

    @Test
    void 流式追加只更新末簇并保留稳定前缀节点与选区() {
        try (Fixture fixture = new Fixture("chat")) {
            fixture.chat("稳定前缀 😊 尾部👍", false, true);
            fixture.awaitGlyphs(2);
            FxTestSupport.run(() -> fixture.script("""
                    window.stablePrefix = document.querySelector('.message-body').firstChild;
                    const range = document.createRange(); range.setStart(stablePrefix,0); range.setEnd(stablePrefix,4);
                    getSelection().removeAllRanges(); getSelection().addRange(range);
                    """));
            fixture.chat("稳定前缀 😊 尾部👍🏽", false, true);
            fixture.awaitGlyphs(2);
            FxTestSupport.run(() -> {
                assertTrue(fixture.truth("stablePrefix === document.querySelector('.message-body').firstChild"));
                assertEquals("稳定前缀", fixture.string("getSelection().toString()"));
                assertEquals("稳定前缀 😊 尾部👍🏽", fixture.string("document.querySelector('.message-body').textContent"));
                assertEquals(
                        "👍🏽",
                        fixture.string("Array.from(document.querySelectorAll('.emoji-glyph')).pop().textContent"));
            });
        }
    }

    @Test
    void 连续分块的国旗键帽连接符与代理对最终只有一个完整字素() {
        try (Fixture fixture = new Fixture("chat")) {
            for (String emoji : List.of("😊", "👍🏽", "🇨🇳", "1️⃣", "👨‍👩‍👧‍👦")) {
                fixture.chat("前缀 ", false, true);
                fixture.awaitText("前缀 ");
                for (int end = 1; end <= emoji.length(); end++) {
                    fixture.chat("前缀 " + emoji.substring(0, end), false, true);
                    fixture.awaitText("前缀 " + emoji.substring(0, end));
                }
                fixture.awaitGlyphs(1);
                FxTestSupport.run(() ->
                        assertEquals(emoji, fixture.string("document.querySelector('.emoji-glyph').textContent")));
            }
        }
    }

    @Test
    void 文档Markdown和代码高亮后仍显示完整原始表情() throws Exception {
        try (Fixture fixture = new Fixture("document")) {
            String source = "中文说明 😊\n\n```java\nString value = \"👍🏽 🇨🇳\";\n```";
            String html = new SafeMarkdown().render(source, "test:", Map.of()).html();
            fixture.show(Map.of("html", html));
            fixture.awaitGlyphs(3);
            fixture.save("document-markdown.png");
            fixture.assertColorPixels();
            fixture.show(Map.of(
                    "language",
                    "java",
                    "lines",
                    List.of(Map.of("number", 1, "text", "String value = \"👨‍👩‍👧‍👦 1️⃣\";"))));
            fixture.awaitGlyphs(2);
            fixture.save("document-code.png");
            FxTestSupport.run(() -> assertEquals(
                    "String value = \"👨‍👩‍👧‍👦 1️⃣\";",
                    fixture.string("document.querySelector('.source-code').textContent")));
            fixture.assertColorPixels();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final CanonicalJson json = new CanonicalJson();
        private final WebSurfaceHost host;
        private final Stage stage;
        private int revision;

        private Fixture(String kind) {
            host = FxTestSupport.call(() -> new WebSurfaceHost(kind, new Label("简版"), (action, value) -> {}));
            stage = FxTestSupport.call(() -> {
                Stage window = new Stage();
                Scene scene = new Scene(host, 980, 580);
                DesktopStylesheets.apply(scene);
                window.setScene(scene);
                window.show();
                return window;
            });
        }

        private void chat(String text, boolean markdown, boolean streaming) {
            String html = markdown
                    ? new SafeMarkdown().render(text, "emoji:", Map.of()).html()
                    : "";
            show(Map.of(
                    "items",
                    List.of(Map.of(
                            "id",
                            "message",
                            "version",
                            Integer.toString(++revision),
                            "title",
                            "助手",
                            "style",
                            "message-assistant",
                            "text",
                            text,
                            "html",
                            html,
                            "streaming",
                            streaming))));
        }

        private void show(Map<String, Object> data) {
            FxTestSupport.run(() -> host.show("emoji-test", json.encode(data).json()));
        }

        private void awaitText(String text) {
            FxTestSupport.await(() -> FxTestSupport.call(() ->
                    host.acknowledged() && text.equals(string("document.querySelector('.message-body').textContent"))));
        }

        private void awaitGlyphs(int count) {
            FxTestSupport.await(() -> FxTestSupport.call(() ->
                    host.acknowledged() && truth("document.querySelectorAll('.emoji-native').length === " + count)));
            FxTestSupport.run(() -> script("""
                    window.emojiPixelsReady = false;
                    Promise.all(Array.from(document.querySelectorAll('.emoji-native')).map(span => new Promise(done => {
                        const image = new Image(); image.onload = done; image.onerror = done;
                        image.src = getComputedStyle(span).backgroundImage.slice(5,-2);
                    }))).then(() => requestAnimationFrame(() => requestAnimationFrame(() => { window.emojiPixelsReady = true; })));
                    """));
            FxTestSupport.await(() -> FxTestSupport.call(() -> truth("window.emojiPixelsReady === true")));
        }

        private void assertColorPixels() {
            FxTestSupport.run(() -> {
                Map<?, ?> geometry = json.decode(new CanonicalPayload(string("""
                        JSON.stringify({rectangles:Array.from(document.querySelectorAll('.emoji-native')).map(span => {
                            const rect = span.getBoundingClientRect();
                            return {text:span.textContent,x:rect.x,y:rect.y,width:rect.width,height:rect.height};
                        })})
                        """)), Map.class);
                List<?> rectangles = (List<?>) geometry.get("rectangles");
                WritableImage frame = web().snapshot(null, null);
                for (Object value : rectangles) {
                    Map<?, ?> rectangle = (Map<?, ?>) value;
                    int x = ((Number) rectangle.get("x")).intValue();
                    int y = ((Number) rectangle.get("y")).intValue();
                    int width = ((Number) rectangle.get("width")).intValue();
                    int height = ((Number) rectangle.get("height")).intValue();
                    int colored = 0;
                    for (int row = Math.max(0, y); row < Math.min(frame.getHeight(), y + height); row++) {
                        for (int column = Math.max(0, x); column < Math.min(frame.getWidth(), x + width); column++) {
                            var pixel = frame.getPixelReader().getColor(column, row);
                            if (pixel.getSaturation() > 0.35 && pixel.getBrightness() > 0.5) {
                                colored++;
                            }
                        }
                    }
                    // 小字号的浅蓝色家庭图形只有少量高饱和像素；按面积验收，灰色条纹仍无法通过。
                    assertTrue(
                            colored > Math.max(3, width * height * 0.025),
                            "表情必须实际绘制彩色像素：" + rectangle + "，彩色像素=" + colored);
                }
            });
        }

        private void save(String name) throws Exception {
            WritableImage frame = FxTestSupport.call(() -> web().snapshot(null, null));
            BufferedImage image =
                    new BufferedImage((int) frame.getWidth(), (int) frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    image.setRGB(x, y, frame.getPixelReader().getArgb(x, y));
                }
            }
            Path directory = Path.of("target", "emoji-rendering");
            Files.createDirectories(directory);
            ImageIO.write(image, "png", directory.resolve(name).toFile());
        }

        private WebView web() {
            return host.getChildren().stream()
                    .filter(WebView.class::isInstance)
                    .map(WebView.class::cast)
                    .findFirst()
                    .orElseThrow();
        }

        private Object script(String source) {
            return web().getEngine().executeScript(source);
        }

        private String string(String source) {
            return String.valueOf(script(source));
        }

        private boolean truth(String source) {
            return Boolean.TRUE.equals(script(source));
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
