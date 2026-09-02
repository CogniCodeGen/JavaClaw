package com.javaclaw.desktop;

import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.appearance.AppearanceTheme;
import com.javaclaw.desktop.appearance.FontScale;
import com.javaclaw.desktop.appearance.InterfaceDensity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopStylesheetsTest {
    private static final String BASELINE_SHA256 = "1e95cbe4f963f1f6b12229f3a4cbbfb43861af0379491303c9bd1424f787d51f";

    @Test
    void 可达视觉基线保持固定字节和级联顺序() throws Exception {
        assertEquals(7, DesktopStylesheets.BASELINE_RESOURCES.size());
        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        for (String resource : DesktopStylesheets.BASELINE_RESOURCES) {
            URL url = DesktopStylesheets.class.getResource(resource);
            assertTrue(url != null, () -> "缺少样式资源：" + resource);
            byte[] content = Files.readAllBytes(Path.of(url.toURI()));
            combined.write(content);
            assertTrue(lineCount(content) <= 600, () -> "样式文件超过 600 行：" + resource);
        }

        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(combined.toByteArray()));
        assertEquals(BASELINE_SHA256, digest);
    }

    @Test
    void 不可达旧领域样式不再进入发行资源() {
        Path cssDirectory = Path.of(System.getProperty("basedir", "."), "src/main/resources/css");
        for (String removed : List.of(
                "/css/conversation-components.css",
                "/css/planning-attachments.css",
                "/css/knowledge-settings.css",
                "/css/mcp-tasks.css",
                "/css/task-editor.css",
                "/css/media-markdown-loop.css")) {
            assertFalse(DesktopStylesheets.BASELINE_RESOURCES.contains(removed));
            assertFalse(
                    Files.exists(cssDirectory.resolve(Path.of(removed).getFileName())),
                    () -> "旧领域样式仍在源码资源中：" + removed);
        }
    }

    @Test
    void 新增界面只使用平台组件语义和设计令牌() throws Exception {
        String stylesheet = resourceText("/css/desktop.css");
        for (String selector : List.of(
                ".platform-window",
                ".platform-page",
                ".platform-section-card",
                ".platform-navigation-list",
                ".platform-detail-cell",
                ".platform-feedback")) {
            assertTrue(stylesheet.contains(selector), () -> "缺少平台组件样式：" + selector);
        }
        assertFalse(Pattern.compile("#[0-9a-fA-F]{3,8}|rgba?\\(")
                .matcher(stylesheet)
                .find());

        String managementStylesheet = resourceText("/css/management-center.css");
        for (String selector : List.of(
                ".management-center",
                ".platform-form-section",
                ".platform-revision-conflict",
                ".platform-secret-field",
                ".platform-danger-zone",
                ".font-scale-120",
                ".density-spacious")) {
            assertTrue(managementStylesheet.contains(selector), () -> "缺少管理中心样式：" + selector);
        }
        assertFalse(Pattern.compile("#[0-9a-fA-F]{3,8}|rgba?\\(")
                .matcher(managementStylesheet)
                .find());

        String themeStylesheet = resourceText("/css/themes-shell.css");
        for (AppearanceTheme theme : AppearanceTheme.values()) {
            if (theme != AppearanceTheme.EMERALD) {
                assertTrue(themeStylesheet.contains("." + theme.cssClass()), () -> "缺少主题：" + theme.cssClass());
            }
        }
        for (FontScale scale : FontScale.values()) {
            assertTrue(managementStylesheet.contains("." + scale.cssClass()), () -> "缺少字号：" + scale.cssClass());
        }
        for (InterfaceDensity density : InterfaceDensity.values()) {
            if (density != InterfaceDensity.STANDARD) {
                assertTrue(managementStylesheet.contains("." + density.cssClass()), () -> "缺少密度：" + density.cssClass());
            }
        }

        String mainFxml = resourceText("/fxml/main.fxml");
        assertFalse(mainFxml.contains(" style=\""), "FXML 不得通过 inline style 绕过设计系统");
        assertTrue(mainFxml.contains("fx:id=\"connectionErrorCard\""));
        assertTrue(mainFxml.contains("onAction=\"#retryConnection\""));
        assertTrue(mainFxml.contains("onAction=\"#openDiagnostics\""));
        assertFalse(mainFxml.contains("Connection refused"), "连接异常不得作为原始字符串直接写入界面");
    }

    private static String resourceText(String path) throws Exception {
        URL resource = DesktopStylesheets.class.getResource(path);
        assertTrue(resource != null, () -> "缺少资源：" + path);
        return Files.readString(Path.of(resource.toURI()));
    }

    private static long lineCount(byte[] content) {
        long newlines = 0;
        for (byte value : content) {
            if (value == '\n') {
                newlines++;
            }
        }
        return newlines;
    }
}
