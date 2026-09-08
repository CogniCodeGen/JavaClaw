package com.javaclaw.desktop.acceptance.chatdocument;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.image.WritableImage;
import javafx.scene.web.WebView;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.DocumentPreview;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentVisualAcceptanceTest {
    @Test
    void Markdown表格代码相对图片和文档链接通过只读网关显示() throws Exception {
        try (DocumentAcceptanceFixture fixture = new DocumentAcceptanceFixture()) {
            fixture.gateway.add("grid.png", "image/png", DocumentAcceptanceFixture.image("png"), Optional.empty());
            fixture.gateway.add(
                    "notes.txt", "text/plain", DocumentAcceptanceFixture.text("相对引用文档正文"), Optional.empty());
            String markdown = "# 引用说明\n\n| 名称 | 状态 |\n| --- | --- |\n| 文档 | 已验证 |\n\n"
                    + "```java\nString version = \"只读版本\";\n```\n\n![本地图片](grid.png)\n\n"
                    + "[打开相对文档](notes.txt) · [外部资料](https://example.com/docs)\n\n<script>window.attack=true</script>";
            fixture.open("README.md", "text/markdown", DocumentAcceptanceFixture.text(markdown), Optional.empty());
            fixture.await("已验证");
            assertEquals("1", fixture.script("String(document.querySelectorAll('table').length)"));
            assertEquals("undefined", fixture.script("typeof window.attack"));
            FxTestSupport.await(() -> fixture.script(
                            "!!document.querySelector('img')&&document.querySelector('img').naturalWidth>0")
                    .equals("true"));
            assertEquals("360", fixture.script("String(document.querySelector('img').naturalWidth)"));
            AcceptanceCapture.save(
                    fixture.stage.getScene(),
                    "document-markdown",
                    Map.of("table", true, "relativeImage", true, "scriptExecuted", false));
            fixture.script("[...document.querySelectorAll('a')].find(n=>n.textContent==='外部资料').click()");
            assertEquals(
                    "https://example.com/docs",
                    fixture.gateway.external.getFirst().toString());
            fixture.script("[...document.querySelectorAll('a')].find(n=>n.textContent==='打开相对文档').click()");
            fixture.await("相对引用文档正文");
            assertTrue(fixture.gateway.resources.contains("notes.txt"));
            AcceptanceCapture.save(
                    fixture.stage.getScene(), "document-relative-text", Map.of("resolvedByGateway", true));
        }
    }

    @Test
    void 代码目标行分页前后切换及健康页面简版重试均保留正文() throws Exception {
        try (DocumentAcceptanceFixture fixture = new DocumentAcceptanceFixture()) {
            String code = IntStream.rangeClosed(1, 620)
                    .mapToObj(line -> "int line" + line + " = " + line + ";")
                    .collect(Collectors.joining("\n"));
            fixture.open("Example.java", "text/plain", DocumentAcceptanceFixture.text(code), Optional.of(550));
            fixture.await("line550");
            assertEquals("550", fixture.script("document.querySelector('.target-line .line-number').textContent"));
            AcceptanceCapture.save(fixture.stage.getScene(), "document-code-target", Map.of("targetLine", 550));
            fixture.click("上一页");
            fixture.await("line1 = 1;");
            assertEquals("1", fixture.script("document.querySelector('.line-number').textContent"));
            fixture.click("下一页");
            fixture.await("line550");
            fixture.click("简版");
            FxTestSupport.run(() -> assertTrue(fixture.pane.lookupAll(".text-area").stream()
                    .map(TextArea.class::cast)
                    .anyMatch(area -> area.isVisible() && area.getText().contains("line550"))));
            AcceptanceCapture.save(fixture.stage.getScene(), "document-code-native", Map.of("samePage", true));
            FxTestSupport.run(() -> fixture.surface().retry());
            fixture.await("line550");
            AcceptanceCapture.save(fixture.stage.getScene(), "document-code-recovered", Map.of("sameTargetLine", 550));
        }
    }

    @Test
    void 图片格式校验与撤权清除及不支持格式元信息均在真实面板可见() throws Exception {
        try (DocumentAcceptanceFixture fixture = new DocumentAcceptanceFixture()) {
            for (String format : new String[] {"png", "gif"}) {
                DocumentPreview version = fixture.open(
                        "grid." + format, "image/" + format, DocumentAcceptanceFixture.image(format), Optional.empty());
                FxTestSupport.await(
                        () -> FxTestSupport.call(() -> fixture.surface().acknowledged())
                                && fixture.script(
                                                "!!document.querySelector('img')&&document.querySelector('img').naturalWidth>0")
                                        .equals("true"));
                AcceptanceCapture.save(
                        fixture.stage.getScene(),
                        "document-image-" + format,
                        Map.of("decodedWidth", 360, "decodedHeight", 180));
                WebView previous = FxTestSupport.call(() -> fixture.surface().getChildren().stream()
                        .filter(WebView.class::isInstance)
                        .map(WebView.class::cast)
                        .findFirst()
                        .orElseThrow());
                FxTestSupport.run(() -> {
                    fixture.pane.invalidate(version.handleId(), "REVOKED");
                    assertNull(previous.getParent(), "撤权必须拆除旧纹理，不能等空 DOM 的 ACK 后复显旧 WebView");
                });
                fixture.awaitMetadata("读取权限已变化");
                fixture.awaitBlank();
                assertNoGrid(fixture.stage.getScene());
                FxTestSupport.run(() -> assertTrue(fixture.pane.lookupAll(".text-area").stream()
                        .map(TextArea.class::cast)
                        .allMatch(area -> area.getText().isEmpty())));
                assertTrue(fixture.gateway.closed.contains(version.handleId()));
            }
            AcceptanceCapture.save(
                    fixture.stage.getScene(), "document-revoked", Map.of("contentCleared", true, "handleClosed", true));
            fixture.open(
                    "report.pdf",
                    "application/pdf",
                    DocumentAcceptanceFixture.text("%PDF-unsupported"),
                    Optional.empty());
            fixture.awaitMetadata("此格式暂不支持预览");
            fixture.awaitBlank();
            FxTestSupport.run(() -> {
                assertTrue(fixture.pane.getChildren().stream()
                        .filter(Label.class::isInstance)
                        .map(Label.class::cast)
                        .anyMatch(label -> label.getText().equals("report.pdf")));
                assertFalse(fixture.pane.lookupAll(".text-area").stream()
                        .map(TextArea.class::cast)
                        .anyMatch(area -> area.getText().contains("%PDF")));
            });
            AcceptanceCapture.save(
                    fixture.stage.getScene(),
                    "document-unsupported",
                    Map.of("metadataRetained", true, "contentExecuted", false));
        }
    }

    @Test
    void 窄文档侧栏完整展示所有按钮名称且自动换行() throws Exception {
        try (DocumentAcceptanceFixture fixture = new DocumentAcceptanceFixture(340)) {
            fixture.open("narrow.txt", "text/plain", DocumentAcceptanceFixture.text("窄侧栏仍可完整阅读与操作"), Optional.empty());
            fixture.await("窄侧栏");
            FxTestSupport.run(() -> {
                var buttons = fixture.pane.lookupAll(".button").stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .filter(button -> java.util.Set.of("上一页", "下一页", "重新读取", "简版", "关闭")
                                .contains(button.getText()))
                        .toList();
                assertEquals(5, buttons.size());
                buttons.forEach(button -> assertTrue(button.getWidth() >= button.prefWidth(-1) - 1));
                assertTrue(buttons.stream()
                                .map(button -> button.getBoundsInParent().getMinY())
                                .distinct()
                                .count()
                        > 1);
            });
            AcceptanceCapture.save(
                    fixture.stage.getScene(),
                    "document-narrow-toolbar",
                    Map.of("width", 340, "allLabelsUntruncated", true));
        }
    }

    private static void assertNoGrid(Scene scene) {
        WritableImage frame = FxTestSupport.call(() -> scene.snapshot(null));
        boolean found = false;
        for (int y = 0; y < (int) frame.getHeight(); y++) {
            for (int x = 0; x < (int) frame.getWidth(); x++) {
                found |= (frame.getPixelReader().getArgb(x, y) & 0xFFFFFF) == 0x276C58;
            }
        }
        assertFalse(found, "同一张 Scene 像素中不得保留已撤权图片的格子");
    }
}
