package com.javaclaw.desktop;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import javax.imageio.ImageIO;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.AppearanceTheme;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;
import com.javaclaw.desktop.appearance.FontScale;
import com.javaclaw.desktop.appearance.InterfaceDensity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 在真实主聊天 Scene 验证布局边界；截图供人工检查，不以平台字形差异建立像素门禁。 */
class DesktopChatLayoutTest {
    private static final String MODEL_NAME = "团队专用长名称模型 · 高精度多语言推理与编程助手 · 2026 年九月预览版";
    private static final String LONG_DRAFT = "请根据上面的对话，逐项梳理界面改进与实现约束。\n".repeat(32);
    private static final List<WindowSize> SIZES = List.of(new WindowSize(940, 640), new WindowSize(1280, 820));

    @Test
    void everyAppearanceKeepsTranscriptAndComposerInsideRealChatScene() throws Exception {
        try (DesktopChatReplayFixture shell =
                new DesktopChatReplayFixture(3, "这里保留可阅读的聊天正文。\n\n- 输入区不遮盖消息\n- 待处理事项按需展开", false)) {
            shell.providers(List.of(provider()));
            shell.open();
            FxTestSupport.run(() ->
                    assertTrue(((Button) shell.control("chatModel")).getText().contains(MODEL_NAME)));
            for (WindowSize size : SIZES) {
                shell.resize(size.width(), size.height());
                for (AppearanceTheme theme : AppearanceTheme.values()) {
                    // 每批只遍历一个主题，让真实 FX pulse 和 WebView 在批次之间处理绘制。
                    FxTestSupport.run(() -> checkTheme(shell, size, theme));
                }
                capture(shell, size, AppearanceTheme.EMERALD);
                capture(shell, size, AppearanceTheme.MIDNIGHT);
            }
        }
    }

    private static void checkTheme(DesktopChatReplayFixture shell, WindowSize size, AppearanceTheme theme) {
        for (InterfaceDensity density : InterfaceDensity.values()) {
            for (FontScale scale : FontScale.values()) {
                AppearancePreferences preferences = new AppearancePreferences(theme, scale, density);
                // Stage 已显示，窗口登记已完成；仅 apply 当前 Scene，不预览或保存全局偏好。
                DesktopAppearanceManager.apply(shell.scene(), preferences);
                String context = size + " " + theme + " " + density + " " + scale;
                pending(shell, false);
                shell.composer().setText("继续完善这份计划。");
                layout(shell);
                assertTrue(shell.control("sidebar").isManaged(), context);
                assertTrue(
                        shell.composer().getHeight() >= 56 && shell.composer().getHeight() <= 64, context);
                assertGeometry(shell, context);

                pending(shell, true);
                shell.composer().setText(LONG_DRAFT);
                layout(shell);
                assertEquals(size.width() >= 1200, shell.control("sidebar").isManaged(), context);
                assertTrue(shell.control("progressPanel").isManaged(), context);
                assertEquals(240, shell.composer().getHeight(), 1, context);
                assertGeometry(shell, context);
                assertInScene(shell, shell.control("progressPanel"), context);
                pending(shell, false);
            }
        }
    }

    private static void assertGeometry(DesktopChatReplayFixture shell, String context) {
        Node card = shell.control("composerCard");
        Node transcript = shell.control("transcriptHost");
        assertInScene(shell, card, context);
        assertInScene(shell, transcript, context);
        assertTrue(bounds(transcript).getHeight() >= 160, context + " 正文高度不足");
        assertTrue(bounds(transcript).getMaxY() <= bounds(card).getMinY() + 1, context + " 输入区覆盖正文");
        for (String id : List.of("composer", "chatModel", "chatReasoning", "sendButton")) {
            Node control = shell.control(id);
            assertTrue(control.isVisible() && control.isManaged(), context + " " + id);
            assertInside(bounds(control), bounds(card), context + " " + id);
        }
        assertFalse(shell.control("chatConfigurationFeedback").isManaged(), context + " 空反馈行仍占位");
        assertTrue(((Button) shell.control("chatModel")).getWidth() <= 221, context + " 长模型名挤占工具栏");
        assertInScene(shell, shell.control("pendingButton"), context);
    }

    private static void assertInScene(DesktopChatReplayFixture shell, Node node, String context) {
        Bounds bounds = bounds(node);
        assertTrue(bounds.getMinX() >= -1 && bounds.getMinY() >= -1, context + " " + node.getId() + " 起点越界");
        assertTrue(bounds.getMaxX() <= shell.scene().getWidth() + 1, context + " " + node.getId() + " 水平越界");
        assertTrue(bounds.getMaxY() <= shell.scene().getHeight() + 1, context + " " + node.getId() + " 垂直越界");
    }

    private static void assertInside(Bounds child, Bounds parent, String context) {
        assertTrue(child.getMinX() >= parent.getMinX() - 1, context + " 左边越界");
        assertTrue(child.getMaxX() <= parent.getMaxX() + 1, context + " 右边越界");
        assertTrue(child.getMinY() >= parent.getMinY() - 1, context + " 顶部越界");
        assertTrue(child.getMaxY() <= parent.getMaxY() + 1, context + " 底部越界");
    }

    private static Bounds bounds(Node node) {
        return node.localToScene(node.getLayoutBounds());
    }

    private static void pending(DesktopChatReplayFixture shell, boolean visible) {
        if (shell.control("progressPanel").isVisible() != visible) {
            ((Button) shell.control("pendingButton")).fire();
        }
    }

    private static void layout(DesktopChatReplayFixture shell) {
        Parent root = shell.scene().getRoot();
        root.applyCss();
        root.layout();
        // 宽度变化会更新 composer 的自动高度，第二次布局消化这一局部请求。
        root.layout();
    }

    private static void capture(DesktopChatReplayFixture shell, WindowSize size, AppearanceTheme theme)
            throws IOException {
        FxTestSupport.run(() -> {
            DesktopAppearanceManager.apply(
                    shell.scene(), new AppearancePreferences(theme, FontScale.STANDARD, InterfaceDensity.STANDARD));
            pending(shell, size.width() < 1200);
            shell.composer().setText(size.width() < 1200 ? LONG_DRAFT : "继续完善这份计划。");
            layout(shell);
        });
        FxTestSupport.await(() -> FxTestSupport.call(() -> webAppearanceMatches(shell)));
        awaitWebLayout(shell);
        WritableImage image = FxTestSupport.call(() -> {
            assertTrue(shell.scene().getRoot().getStyleClass().contains(theme.cssClass()));
            assertGeometry(shell, "截图 " + theme + " " + size);
            assertShortTranscriptVisible(shell);
            return shell.scene().snapshot(null);
        });
        Path directory = Path.of("target", "chat-layout");
        Files.createDirectories(directory);
        save(image, directory.resolve(theme.id() + "-" + size.width() + "x" + size.height() + ".png"));
    }

    private static boolean webAppearanceMatches(DesktopChatReplayFixture shell) {
        Label page = (Label) shell.scene().lookup(".web-theme-page");
        Color color = (Color) page.getTextFill();
        String expected = "rgba(" + Math.round(color.getRed() * 255) + ',' + Math.round(color.getGreen() * 255) + ','
                + Math.round(color.getBlue() * 255) + ',' + color.getOpacity() + ')';
        return expected.equals(
                shell.web().getEngine().executeScript("document.documentElement.style.getPropertyValue('--page')"));
    }

    private static void awaitWebLayout(DesktopChatReplayFixture shell) {
        FxTestSupport.await(() -> FxTestSupport.call(() -> webViewportMatches(shell)));
        FxTestSupport.run(() -> shell.web().getEngine().executeScript("""
                (() => {
                    const probe = {ready: false};
                    window.chatLayoutCapture = probe;
                    let previous = "";
                    let unchanged = 0;
                    const check = () => {
                        const cards = [...document.querySelectorAll("#surface article")];
                        const current = JSON.stringify([innerWidth, innerHeight, scrollY,
                            document.documentElement.scrollHeight,
                            cards.map(card => {
                                const rect = card.getBoundingClientRect();
                                return [rect.x, rect.y, rect.width, rect.height];
                            })]);
                        unchanged = current === previous ? unchanged + 1 : 0;
                        previous = current;
                        if (unchanged >= 2) { probe.ready = true; }
                        else { requestAnimationFrame(check); }
                    };
                    requestAnimationFrame(check);
                })()
                """));
        // 颜色注入与 FX layout 均不代表 WebKit 已完成 resize；跨 RAF 等几何稳定后再取像。
        FxTestSupport.await(() -> FxTestSupport.call(() -> webViewportMatches(shell)
                && Boolean.TRUE.equals(shell.web().getEngine().executeScript("window.chatLayoutCapture.ready"))));
    }

    private static boolean webViewportMatches(DesktopChatReplayFixture shell) {
        double width = ((Number) shell.web().getEngine().executeScript("window.innerWidth")).doubleValue();
        double height = ((Number) shell.web().getEngine().executeScript("window.innerHeight")).doubleValue();
        return Math.abs(width - shell.web().getWidth()) <= 1
                && Math.abs(height - shell.web().getHeight()) <= 1
                && Math.abs(shell.web().getWidth() - shell.host().getWidth()) <= 1
                && Math.abs(shell.web().getHeight() - shell.host().getHeight()) <= 1;
    }

    private static void assertShortTranscriptVisible(DesktopChatReplayFixture shell) {
        Object valid = shell.web().getEngine().executeScript("""
                (() => {
                    const cards = [...document.querySelectorAll("#surface article")];
                    if (!cards.length) { return false; }
                    const contentHeight = cards.reduce((height, card) => {
                        const style = getComputedStyle(card);
                        return height + card.getBoundingClientRect().height
                            + parseFloat(style.marginTop) + parseFloat(style.marginBottom);
                    }, 0);
                    return contentHeight > innerHeight + 1
                        || (Math.abs(scrollY) <= 1 && cards[0].getBoundingClientRect().top >= -1);
                })()
                """);
        assertEquals(
                Boolean.TRUE,
                valid,
                () -> "短正文应从首条完整显示：" + shell.web().getEngine().executeScript("""
                JSON.stringify({height: innerHeight, scrollY,
                    documentHeight: document.documentElement.scrollHeight,
                    first: document.querySelector("#surface article").getBoundingClientRect().top})
                """));
    }

    private static void save(WritableImage image, Path file) throws IOException {
        BufferedImage output =
                new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < output.getHeight(); y++) {
            for (int x = 0; x < output.getWidth(); x++) {
                output.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            }
        }
        ImageIO.write(output, "png", file.toFile());
    }

    private static ProviderEndpoint provider() {
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                "布局测试模型服务",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.empty(),
                ProviderAuthentication.API_KEY,
                List.of(new ProviderModelSpec(
                        "model-test", MODEL_NAME, Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())),
                Optional.empty(),
                Duration.ofSeconds(30),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
        return new ProviderEndpoint(
                "openai", 1, ProviderLifecycle.ACTIVE, spec, DesktopTestFixtures.NOW, DesktopTestFixtures.NOW);
    }

    private record WindowSize(int width, int height) {}
}
