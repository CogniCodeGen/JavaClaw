package com.javaclaw.chat;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import netscape.javascript.JSObject;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Markdown 渲染气泡组件
 *
 * <p>封装 JavaFX WebView + CommonMark 解析器，提供与 InlineCssTextArea 兼容的 API，
 * 支持流式追加内容和高度自适应。用于替代助手消息中的纯文本显示。</p>
 */
public class MarkdownBubble {

    private static final Logger log = LoggerFactory.getLogger(MarkdownBubble.class);

    // ==================== CommonMark 解析器（线程安全，全局共享） ====================

    private static final List<Extension> EXTENSIONS = List.of(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            AutolinkExtension.create()
    );

    private static final Parser PARSER = Parser.builder()
            .extensions(EXTENSIONS)
            .build();

    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .build();

    // ==================== 实例字段 ====================

    private final WebView webView;
    private final WebEngine engine;
    private final StringBuilder content = new StringBuilder();

    /** WebView 是否已完成初始 HTML 模板加载 */
    private boolean templateLoaded = false;

    /** 模板加载前缓存的待渲染内容 */
    private boolean hasPendingRender = false;

    /** 渲染节流器（流式输入时限制渲染频率，避免 WebView 过载） */
    private final PauseTransition renderThrottle;

    /** 上次实际执行 renderToWebView 的时间戳（毫秒）。
     *  焦点重绘前用其判断"刚渲染过"，避免与流式渲染同帧并发。 */
    private volatile long lastRenderAtMs = 0;

    /** 焦点重绘"近期阈值"：上次渲染距今小于此值视为内容仍新鲜，跳过焦点兜底。 */
    private static final long FOCUS_REFRESH_RECENT_THRESHOLD_MS = 500;

    /** 焦点重绘错峰间隔：场景中多个气泡同时收到焦点事件时，按 slot 顺序每隔此值依次重绘。 */
    private static final long FOCUS_REFRESH_STAGGER_MS = 40;

    /** 全局焦点重绘 slot 计数，分散多个 MarkdownBubble 的并发 executeScript，
     *  避免一帧内打爆 WebKit 合成层导致 scene graph 复绘。 */
    private static final java.util.concurrent.atomic.AtomicInteger FOCUS_REFRESH_SLOT =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 高度回调桥接对象（必须保持强引用，防止被 GC 回收） */
    @SuppressWarnings("FieldCanBeLocal")
    private final HeightBridge heightBridge;

    /** 已释放标记，阻止 dispose 后的异步渲染回调重新使用 WebView */
    private volatile boolean disposed = false;

    /** 当前绑定的 Stage（用于 dispose 时解除 focus 监听） */
    private javafx.stage.Window boundWindow;

    /** 主题切换监听：实时更新页底填充并重新注入配色；保留引用以便 dispose 解除 */
    private final javafx.beans.value.ChangeListener<String> themeListener =
            (obs, oldTheme, newTheme) -> {
                if (disposed) return;
                applyPageFill();
                if (templateLoaded) {
                    applyThemePalette();
                }
            };

    /** 页底填充 = 当前主题的页面底色（ThemeManager.Theme.bg），不透明避免黑块伪影 */
    private void applyPageFill() {
        try {
            String bg = com.javaclaw.ui.javafx.theme.ThemeManager.getCurrentTheme().bg();
            webView.setPageFill(javafx.scene.paint.Color.web(bg));
        } catch (Exception e) {
            webView.setPageFill(javafx.scene.paint.Color.web("#FBFAF6"));
        }
    }

    /** 监听主窗口焦点变化以强制重绘；保留引用以便 dispose 时解除 */
    private final javafx.beans.value.ChangeListener<Boolean> focusListener =
            (obs, wasFocused, isFocused) -> {
                if (disposed) return;
                if (!isFocused || !templateLoaded || content.length() == 0) return;
                // 流式正在写入或刚刷过的气泡：内容已新鲜，跳过焦点兜底
                // 避免与 throttle 触发的 renderToWebView 同帧并发，保护 WebKit 合成
                if (System.currentTimeMillis() - lastRenderAtMs < FOCUS_REFRESH_RECENT_THRESHOLD_MS) return;
                // 多个气泡的焦点重绘按全局 slot 错峰，避免一帧内并发 executeScript
                int slot = FOCUS_REFRESH_SLOT.getAndIncrement();
                PauseTransition stagger = new PauseTransition(
                        Duration.millis(Math.max(1, FOCUS_REFRESH_STAGGER_MS * slot)));
                stagger.setOnFinished(e -> {
                    FOCUS_REFRESH_SLOT.updateAndGet(v -> Math.max(0, v - 1));
                    if (!disposed) renderToWebView();
                });
                stagger.play();
            };

    /**
     * 创建 Markdown 渲染气泡
     *
     * @param prefWidth 首选宽度（像素）
     */
    public MarkdownBubble(double prefWidth) {
        webView = new WebView();
        webView.setPrefWidth(prefWidth);
        webView.setMinHeight(28);
        webView.setPrefHeight(28);
        webView.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        webView.setContextMenuEnabled(false);
        // 页面底色跟随主题页面底色（不透明）：
        // - 不能用 WebKit 默认白底，深色主题下会成大白块；
        // - 也不能用 TRANSPARENT，macOS 渲染管线对透明 WebView 有已知缺陷（未合成区域画成黑块）。
        applyPageFill();
        // 禁用 WebView 自身的滚动条，由外层 ScrollPane 处理
        webView.getStyleClass().add("markdown-webview");

        engine = webView.getEngine();
        engine.setJavaScriptEnabled(true);

        heightBridge = new HeightBridge(webView);

        // 渲染节流：流式输入时最多每 100ms 渲染一次，减少 WebView 负载
        renderThrottle = new PauseTransition(Duration.millis(100));
        renderThrottle.setOnFinished(e -> {
            if (templateLoaded) {
                renderToWebView();
            }
        });

        // 监听模板加载完成
        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                templateLoaded = true;
                // 注册 Java 回调到 JS
                try {
                    JSObject window = (JSObject) engine.executeScript("window");
                    window.setMember("javaApp", heightBridge);
                } catch (Exception e) {
                    log.warn("注册高度回调失败", e);
                }
                // 注入当前主题配色（CSS 变量）
                applyThemePalette();
                // 如果有待渲染的内容，立即渲染
                if (hasPendingRender) {
                    hasPendingRender = false;
                    renderToWebView();
                }
            }
        });

        // 主题切换时实时刷新已渲染气泡的配色（dispose 时解除，避免泄漏）
        com.javaclaw.ui.javafx.theme.ThemeManager.themeProperty().addListener(themeListener);

        // 加载 HTML 模板（对话密度由 FontManager 注入：正文字号 / 行高随「设置 › 字体」生效）
        String shell = HTML_TEMPLATE
                .replace("%CHAT_FS%", String.valueOf(com.javaclaw.ui.javafx.theme.FontManager.chatFontPx()))
                .replace("%CHAT_LH%", String.valueOf(com.javaclaw.ui.javafx.theme.FontManager.chatLineHeight()));
        engine.loadContent(shell);

        // 将 MarkdownBubble 实例附加到 WebView 上，便于外部定位并在清空场景图时 dispose
        webView.getProperties().put("markdownBubble", this);

        // 窗口焦点恢复时强制重绘 WebView（修复切换窗口后内容空白的问题）。
        // 关键：沿用同一个 focusListener 实例，且只跟随「场景主窗口」一次，
        // dispose 时能通过 boundWindow 反向解除，避免泄漏。
        webView.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (disposed) return;
            rebindWindowFocus(newScene == null ? null : newScene.getWindow());
            if (newScene != null) {
                newScene.windowProperty().addListener((wObs, oldWindow, newWindow) -> {
                    if (disposed) return;
                    rebindWindowFocus(newWindow);
                });
            }
        });
    }

    /** 把 focusListener 绑定到指定 Window，先解除旧绑定。 */
    private void rebindWindowFocus(javafx.stage.Window newWindow) {
        if (boundWindow == newWindow) return;
        if (boundWindow != null) {
            boundWindow.focusedProperty().removeListener(focusListener);
        }
        boundWindow = newWindow;
        if (newWindow != null) {
            newWindow.focusedProperty().addListener(focusListener);
        }
    }

    /**
     * 释放此气泡持有的全部 JavaFX/WebView 资源。
     *
     * <p>调用后 WebView 不再可用。会停止渲染节流器、解除窗口焦点 listener、
     * 清空 HTML 引擎与本地 content 缓存，斩断 MarkdownBubble 与主 Stage
     * 之间的引用链，让 WebKit 本地资源可回收。</p>
     */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        try {
            renderThrottle.stop();
        } catch (Exception ignore) {}
        com.javaclaw.ui.javafx.theme.ThemeManager.themeProperty().removeListener(themeListener);
        rebindWindowFocus(null);
        try {
            engine.load(null);
        } catch (Exception ignore) {}
        content.setLength(0);
        webView.getProperties().remove("markdownBubble");
    }

    /**
     * 获取 WebView 节点（用于添加到场景图）
     */
    public WebView getView() {
        return webView;
    }

    /**
     * 追加文本内容（流式调用）
     */
    public void appendText(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        content.append(chunk);
        requestRender();
    }

    /**
     * 替换全部文本内容
     */
    public void replaceText(String text) {
        content.setLength(0);
        if (text != null) {
            content.append(text);
        }
        requestImmediateRender();
    }

    /**
     * 强制重渲染当前内容（会话切换重建场景图后调用）。
     *
     * <p>macOS 上批量新建 WebView 时 WebKit 偶发「已加载但未绘制」（空白气泡），
     * 与窗口焦点恢复后的空白同病灶；重新写入 innerHTML 可强制合成层重绘。</p>
     */
    public void refresh() {
        if (disposed) return;
        if (templateLoaded) {
            renderToWebView();
        } else {
            hasPendingRender = true;
        }
    }

    /**
     * 获取原始文本内容（非 HTML）
     */
    public String getText() {
        return content.toString();
    }

    /**
     * 获取文本长度
     */
    public int getLength() {
        return content.length();
    }

    // ==================== 内部渲染 ====================

    private void requestRender() {
        if (disposed) return;
        if (templateLoaded) {
            // 节流：快速连续调用时只在最后一次 100ms 后实际渲染
            renderThrottle.playFromStart();
        } else {
            hasPendingRender = true;
        }
    }

    /**
     * 立即渲染（用于 replaceText 等需要即时生效的场景）
     */
    private void requestImmediateRender() {
        if (disposed) return;
        if (templateLoaded) {
            renderThrottle.stop();
            renderToWebView();
        } else {
            hasPendingRender = true;
        }
    }

    private void renderToWebView() {
        if (disposed) return;
        String markdown = content.toString();
        String html = toHtml(markdown);
        // 彩色表情图片化（纯 Java：JEmoji 检测 + Java2D 渲染）：规避 WebKit 无法在正文字号栅格化彩色表情字形的限制
        html = EmojiImageRenderer.imageifyHtml(html);
        log.debug("Markdown 原文: {}", markdown.length() > 500 ? markdown.substring(0, 500) + "..." : markdown);
        log.debug("渲染 HTML: {}", html.length() > 800 ? html.substring(0, 800) + "..." : html);
        String escaped = escapeForJs(html);
        try {
            engine.executeScript(
                    "document.getElementById('content').innerHTML = '" + escaped + "';" +
                    "notifyHeight();");
            lastRenderAtMs = System.currentTimeMillis();
        } catch (Exception e) {
            log.warn("WebView 渲染失败", e);
        }
    }

    // ==================== 主题配色注入 ====================

    /**
     * Markdown 气泡配色（WebView 内部 HTML 无法解析 JavaFX looked-up colors，
     * 需按主题注入 CSS 变量）。与 chat.css 各主题覆盖块的令牌取值保持一致。
     *
     * @param fg 正文 / @param muted 次要 / @param border 边框&分隔
     * @param codeBg 行内代码底 / @param codeFg 行内代码字
     * @param preBg 代码块底 / @param preFg 代码块字
     * @param quoteBg 引用底 / @param quoteBorder 引用左线（品牌色）
     * @param link 链接 / @param thBg 表头底 / @param stripe 偶数行底
     */
    private record MdPalette(String fg, String muted, String border,
                             String codeBg, String codeFg, String preBg, String preFg,
                             String quoteBg, String quoteBorder, String link,
                             String thBg, String stripe) {}

    private static final java.util.Map<String, MdPalette> MD_PALETTES = java.util.Map.of(
            "emerald", new MdPalette("#27251F", "#706B5F", "#E8E4DB",
                    "#F3F1EB", "#1F7E54", "#27251F", "#F3F1EB",
                    "#FAF9F4", "#2E9A6A", "#1F7E54", "#F3F1EB", "#FAF9F4"),
            "midnight", new MdPalette("#F3F1EB", "#A19A8A", "#34312A",
                    "#27251F", "#6ABD92", "#211F19", "#D3CDBF",
                    "#211F19", "#3DA574", "#6ABD92", "#27251F", "#1E1C16"),
            "sapphire", new MdPalette("#16202E", "#677183", "#E1E7F0",
                    "#EDF1F8", "#1F57B0", "#16202E", "#E1E7F0",
                    "#F4F7FB", "#2A6FDB", "#1F57B0", "#EDF1F8", "#F8FAFD"),
            "graphite", new MdPalette("#1F1D18", "#6E695D", "#E6E2D8",
                    "#F1EFE9", "#3F3B33", "#1F1D18", "#E6E2D8",
                    "#FAF9F5", "#3F3B33", "#46423A", "#F1EFE9", "#FAF9F5"),
            "terracotta", new MdPalette("#2A1D14", "#7A6857", "#EDE2D6",
                    "#F3EBE2", "#A84B2A", "#2A1D14", "#EDE2D6",
                    "#FAF6F1", "#C9613B", "#A84B2A", "#F3EBE2", "#FCF8F4"),
            "carbon", new MdPalette("#EAEFF5", "#8B98A8", "#2A323D",
                    "#1E252F", "#7FB6F4", "#1A212A", "#C2CCD8",
                    "#1A212A", "#4F9DF0", "#7FB6F4", "#1E252F", "#171D25"),
            "ocean", new MdPalette("#11272A", "#5C7176", "#DEEAEA",
                    "#E9F2F2", "#0A6E70", "#11272A", "#DEEAEA",
                    "#F2F7F7", "#0E8C8C", "#0A6E70", "#E9F2F2", "#F6FAFA"),
            "plum", new MdPalette("#221A2E", "#6C617A", "#E9E2F0",
                    "#EFEAF6", "#5E3FA0", "#221A2E", "#E9E2F0",
                    "#F7F4FA", "#7E57C2", "#5E3FA0", "#EFEAF6", "#FAF8FC"),
            "honey", new MdPalette("#2A2208", "#7A6E48", "#ECE4D2",
                    "#F3EEE1", "#A06E12", "#2A2208", "#ECE4D2",
                    "#F9F5EC", "#C68A1E", "#A06E12", "#F3EEE1", "#FCFAF4"));

    /** 把当前主题配色以 CSS 变量形式注入 WebView（模板内所有颜色均走 var(--md-*)） */
    private void applyThemePalette() {
        if (disposed || !templateLoaded) return;
        String themeId = com.javaclaw.ui.javafx.theme.ThemeManager.getTheme();
        MdPalette p = MD_PALETTES.getOrDefault(themeId, MD_PALETTES.get("emerald"));
        String js = "var s=document.documentElement.style;"
                + "s.setProperty('--md-fg','" + p.fg() + "');"
                + "s.setProperty('--md-muted','" + p.muted() + "');"
                + "s.setProperty('--md-border','" + p.border() + "');"
                + "s.setProperty('--md-code-bg','" + p.codeBg() + "');"
                + "s.setProperty('--md-code-fg','" + p.codeFg() + "');"
                + "s.setProperty('--md-pre-bg','" + p.preBg() + "');"
                + "s.setProperty('--md-pre-fg','" + p.preFg() + "');"
                + "s.setProperty('--md-quote-bg','" + p.quoteBg() + "');"
                + "s.setProperty('--md-quote-border','" + p.quoteBorder() + "');"
                + "s.setProperty('--md-link','" + p.link() + "');"
                + "s.setProperty('--md-th-bg','" + p.thBg() + "');"
                + "s.setProperty('--md-stripe','" + p.stripe() + "');";
        try {
            engine.executeScript(js);
        } catch (Exception e) {
            log.warn("注入主题配色失败", e);
        }
    }

    // ==================== 静态工具方法 ====================

    /**
     * 将 Markdown 文本转换为 HTML
     */
    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        Node document = PARSER.parse(markdown);
        return RENDERER.render(document);
    }

    /**
     * 转义字符串以安全嵌入 JavaScript 单引号字符串
     */
    private static String escapeForJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("</" + "script>", "<\\/script>");
    }

    // ==================== 高度回调桥接 ====================

    /**
     * JavaScript → Java 高度回调桥接类
     *
     * <p>WebView 中的 ResizeObserver 检测到内容高度变化时，
     * 通过此桥接对象回调 Java 端，动态调整 WebView 高度。</p>
     */
    public static class HeightBridge {
        private final WebView webView;
        /**
         * 单纹理高度上限：JavaFX/Prism (ES2) 最大单纹理边长为 16384，
         * 超过会抛 "Requested texture dimensions exceed maximum texture size"。
         * 这里留 1384px 安全垫，确保叠加 effect/clip 等情况也不会突破。
         */
        private static final int MAX_BUBBLE_HEIGHT = 15000;
        private boolean internalScrollEnabled;

        HeightBridge(WebView webView) {
            this.webView = webView;
        }

        /**
         * 由 JavaScript 调用，报告内容高度
         */
        public void reportHeight(int height) {
            Platform.runLater(() -> {
                boolean exceeds = height > MAX_BUBBLE_HEIGHT;
                double h = Math.max(28, Math.min(MAX_BUBBLE_HEIGHT, height + 2));
                webView.setPrefHeight(h);
                webView.setMinHeight(h);
                webView.setMaxHeight(h);
                // 内容超过 GPU 单纹理上限时，切换到 WebView 内部滚动避免裁断
                if (exceeds && !internalScrollEnabled) {
                    try {
                        webView.getEngine().executeScript(
                                "document.body.style.overflow='auto';" +
                                "document.documentElement.style.overflow='auto';");
                        internalScrollEnabled = true;
                    } catch (Exception ignored) {}
                }
            });
        }
    }

    // ==================== HTML 模板 ====================

    private static final String HTML_TEMPLATE = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="UTF-8">
            <style>
            * {
                margin: 0;
                padding: 0;
                box-sizing: border-box;
            }
            body {
                /* 系统字体统一走 -apple-system / system-ui 关键字解析（macOS→SF Pro，Windows→Segoe UI），
                   再 Inter（打包回退），彩色表情字体排在拉丁之后、CJK 之前，最后 CJK 与 sans 兜底。
                   表情字体须排在 CJK 之前：PingFang SC 等中文字体自带单色表情字形，排前面会被逐字形
                   回退命中、把 😊 渲染成单色字形。
                   已知限制（JavaFX WebView/WebKit）：内联表情在正文字号下（实测 14.5–40px）无论字体栈如何
                   排序、即使换装 COLR 矢量表情字体，仍渲染为梳齿乱码（仅独立大字号可出彩色）—— 这是
                   WebKit 对 sbix/COLR 字形在文本流内的栅格化限制，非本字体栈所能修复；彩色表情改走
                   <img> 替换（renderToWebView 内调 EmojiImageRenderer：纯 Java JEmoji 检测 + Java2D 渲染）。 */
                font-family: -apple-system, system-ui, "Inter", "Segoe UI", "Apple Color Emoji", "Segoe UI Emoji", "Noto Color Emoji", "PingFang SC", "Noto Sans CJK SC", "Hiragino Sans GB", "Microsoft YaHei", "Noto Sans SC", sans-serif;
                font-size: %CHAT_FS%px;
                font-weight: 400;
                line-height: %CHAT_LH%;
                color: var(--md-fg, #27251F);
                background: transparent;
                padding: 0;
                overflow: hidden;
                word-wrap: break-word;
                overflow-wrap: break-word;
                -webkit-font-smoothing: antialiased;
                -moz-osx-font-smoothing: grayscale;
            }
            /* Twemoji 图片化的彩色表情：随正文字号缩放、基线对齐，不撑高行 */
            img.emoji {
                height: 1.15em;
                width: 1.15em;
                margin: 0 0.05em 0 0.1em;
                vertical-align: -0.2em;
                display: inline-block;
            }
            /* 强制所有内联格式化元素不改变字重和样式 */
            strong, b, em, i {
                font-weight: inherit;
                font-style: inherit;
                color: inherit;
            }
            /* 段落 */
            p {
                margin: 0 0 8px 0;
            }
            p:last-child {
                margin-bottom: 0;
            }
            /* 标题 */
            h1, h2, h3, h4, h5, h6 {
                margin: 12px 0 6px 0;
                font-weight: 600;
                color: inherit;
                line-height: 1.3;
            }
            h1:first-child, h2:first-child, h3:first-child {
                margin-top: 0;
            }
            h1 { font-size: 18px; letter-spacing: -0.01em; }
            h2 { font-size: 15px; }
            h3 { font-size: 13px; color: var(--md-muted, #706B5F); }
            h4 { font-size: 13px; color: var(--md-muted, #706B5F); }
            /* 行内代码 */
            code {
                background: var(--md-code-bg, #F3F1EB);
                color: var(--md-code-fg, #1F7E54);
                padding: 1px 5px;
                border-radius: 4px;
                font-family: "Cascadia Code", "SF Mono", "JetBrains Mono", Consolas, Menlo, monospace;
                font-size: 13px;
            }
            /* 代码块 */
            pre {
                background: var(--md-pre-bg, #27251F);
                color: var(--md-pre-fg, #F3F1EB);
                padding: 12px 14px;
                border-radius: 10px;
                overflow-x: auto;
                margin: 8px 0;
                line-height: 1.5;
            }
            pre code {
                background: none;
                color: inherit;
                padding: 0;
                border-radius: 0;
                font-size: 12.5px;
            }
            /* 引用 */
            blockquote {
                border-left: 3px solid var(--md-quote-border, #2E9A6A);
                padding: 4px 12px;
                margin: 8px 0;
                color: var(--md-muted, #706B5F);
                background: var(--md-quote-bg, #FAF9F4);
                border-radius: 0 6px 6px 0;
            }
            blockquote p {
                margin: 0;
            }
            /* 列表 */
            ul, ol {
                padding-left: 20px;
                margin: 6px 0;
            }
            li {
                margin: 2px 0;
            }
            /* 分隔线 */
            hr {
                border: none;
                border-top: 1px solid var(--md-border, #E8E4DB);
                margin: 10px 0;
            }
            /* 链接 */
            a {
                color: var(--md-link, #1F7E54);
                text-decoration: none;
            }
            a:hover {
                text-decoration: underline;
            }
            /* 表格 */
            table {
                border-collapse: collapse;
                width: 100%;
                margin: 8px 0;
                font-size: 13px;
            }
            th, td {
                border: 1px solid var(--md-border, #E8E4DB);
                padding: 6px 10px;
                text-align: left;
            }
            th {
                background: var(--md-th-bg, #F3F1EB);
                font-weight: 600;
                color: var(--md-fg, #27251F);
            }
            tr:nth-child(even) {
                background: var(--md-stripe, #FAF9F4);
            }
            /* 删除线 */
            del {
                color: var(--md-muted, #A19A8A);
            }
            /* 图片 */
            img {
                max-width: 100%;
                border-radius: 8px;
                margin: 4px 0;
            }
            </style>
            </head>
            <body>
            <div id="content"></div>
            <script>
            // 高度通知：通过 ResizeObserver 监听内容变化，回调 Java 端
            function notifyHeight() {
                var h = document.documentElement.scrollHeight;
                if (window.javaApp) {
                    window.javaApp.reportHeight(h);
                }
            }
            // 监听 DOM 变化自动通知高度
            var observer = new ResizeObserver(function() { notifyHeight(); });
            observer.observe(document.body);
            // 图片加载后重新计算高度
            document.addEventListener('load', function(e) {
                if (e.target.tagName === 'IMG') notifyHeight();
            }, true);
            </script>
            </body>
            </html>
            """;
}
