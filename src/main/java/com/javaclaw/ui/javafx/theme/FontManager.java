package com.javaclaw.ui.javafx.theme;

import com.javaclaw.config.AgentConfig;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 全局字体管理器 —— 字体的「单一来源」+ 运行时实时切换（与 {@link ThemeManager} 同构）。
 *
 * <p>三层设计：
 * <ol>
 *   <li><b>系统原生优先</b>：默认（未自定义）时不注入任何覆盖，界面直接继承
 *       {@code chat.css} 的 {@code .root} 基准栈，各平台命中本地最佳字体。</li>
 *   <li><b>打包回退保证一致</b>：{@link #loadBundledFonts()} 在启动时注册
 *       Inter / Noto Sans SC / Cascadia Code，named family 在缺字体的机器上也能解析。</li>
 *   <li><b>全局可配置</b>：用户在「设置 › 字体」选择字体族 / 等宽 / 密度后，本管理器监听
 *       {@link Window#getWindows()}，给每个窗口的 Scene 追加一张<b>动态生成的用户样式表</b>
 *       （排在 chat.css 之后，按 CSS 顺序覆盖），即可整窗实时换字、立即生效。
 *       对话气泡（WebView）的字号/行高由 {@link #chatFontPx()} / {@link #chatLineHeight()}
 *       供 MarkdownBubble 读取。</li>
 * </ol>
 *
 * <p>选择持久化到当前工作区配置（{@code ui.font.*}），切换工作区时 {@link #reload()} 重新读取。
 * 仅用 JavaFX 基础 API，不依赖任何特定版本的新特性。</p>
 */
public final class FontManager {

    private static final Logger log = LoggerFactory.getLogger(FontManager.class);

    /** 界面字体选项（id 持久化；stack 为该选项展开后的完整回退栈）。 */
    public record FontOption(String id, String name, String subtitle, String stack) {}

    /** 等宽字体选项。 */
    public record MonoOption(String id, String name, String stack) {}

    /** 密度选项：对话正文字号(px) + 行高。 */
    public record Density(String id, String name, double fontPx, double lineHeight) {}

    // —— 统一栈片段（与 chat.css .root 默认值一致） ——
    private static final String CJK_FALLBACK =
        "\"PingFang SC\", \"Microsoft YaHei\", \"Noto Sans SC\"";

    public static final List<FontOption> FONT_OPTIONS = List.of(
        new FontOption("native", "系统原生", "macOS SF Pro · Windows Segoe UI",
            "\"SF Pro Text\", \"Segoe UI\", \"Inter\", " + CJK_FALLBACK + ", sans-serif"),
        new FontOption("inter", "Inter", "打包 · 拉丁正文",
            "\"Inter\", " + CJK_FALLBACK + ", sans-serif"),
        new FontOption("noto", "Noto Sans SC", "打包 · 中文字形优美",
            "\"Noto Sans SC\", \"PingFang SC\", \"Inter\", sans-serif"),
        new FontOption("system", "跟随系统 UI", "System default",
            "\"System\", " + CJK_FALLBACK + ", sans-serif"));

    public static final List<MonoOption> MONO_OPTIONS = List.of(
        new MonoOption("cascadia", "Cascadia Code",
            "\"Cascadia Code\", \"SF Mono\", \"JetBrains Mono\", \"Consolas\", \"Menlo\", monospace"),
        new MonoOption("jetbrains", "JetBrains Mono",
            "\"JetBrains Mono\", \"SF Mono\", \"Cascadia Code\", \"Consolas\", \"Menlo\", monospace"),
        new MonoOption("sfmono", "SF Mono",
            "\"SF Mono\", \"Cascadia Code\", \"JetBrains Mono\", \"Consolas\", \"Menlo\", monospace"));

    public static final List<Density> DENSITIES = List.of(
        new Density("compact", "紧凑", 13.5, 1.55),
        new Density("cozy",    "适中", 14.5, 1.65),
        new Density("relaxed", "宽松", 15.5, 1.80));

    public static final String DEFAULT_FONT = "native";
    public static final String DEFAULT_MONO = "cascadia";
    public static final String DEFAULT_DENSITY = "cozy";

    /** 默认界面字体栈（与 chat.css .root 一致），供内联样式/WebView 复用。 */
    public static final String UI_FONT_STACK = FONT_OPTIONS.get(0).stack();
    /** 默认等宽字体栈，供内联样式/WebView 复用，避免字符串再次漂移。 */
    public static final String MONO_FONT_STACK = MONO_OPTIONS.get(0).stack();

    /** chat.css 中使用等宽字体的类（用户切换等宽时由生成样式表统一重指向）。 */
    private static final String[] MONO_SELECTORS = {
        ".msg-header-model", ".msg-header-time", ".msg-header-meta", ".msg-citation-chip",
        ".tp-elapsed", ".tp-metric-value", ".tp-tool-name", ".tp-tool-input", ".tp-tool-status",
        ".tp-pipeline-status", ".sidebar-nav-shortcut", ".kbd-chip",
        ".mc-log-time", ".mc-persona-json"
    };

    /** 打包字体资源路径（放在 src/main/resources/fonts/ 下）。 */
    private static final String[] BUNDLED_FONTS = {
        "/fonts/Inter-Regular.ttf", "/fonts/Inter-Medium.ttf",
        "/fonts/Inter-SemiBold.ttf", "/fonts/Inter-Bold.ttf",
        "/fonts/NotoSansSC-Regular.otf", "/fonts/NotoSansSC-Medium.otf", "/fonts/NotoSansSC-Bold.otf",
        "/fonts/CascadiaCode-Regular.ttf", "/fonts/CascadiaCode-SemiBold.ttf",
    };

    /** 变更计数：「设置 › 字体」面板监听它刷新选中态与预览。 */
    private static final SimpleIntegerProperty revision = new SimpleIntegerProperty(0);

    private static String fontId = DEFAULT_FONT;
    private static String monoId = DEFAULT_MONO;
    private static String densityId = DEFAULT_DENSITY;

    private static boolean loaded = false;
    private static boolean initialized = false;

    private FontManager() {}

    // ==================== 启动期 ====================

    /** 注册全部打包字体。幂等。须在构建任何 Scene 之前调用一次。 */
    public static synchronized void loadBundledFonts() {
        if (loaded) return;
        for (String path : BUNDLED_FONTS) {
            try (InputStream in = FontManager.class.getResourceAsStream(path)) {
                if (in == null) { log.warn("未找到打包字体: {}", path); continue; }
                if (Font.loadFont(in, -1) == null) log.warn("字体加载失败: {}", path);
            } catch (Exception e) {
                log.warn("读取字体异常 {}: {}", path, e.getMessage());
            }
        }
        loaded = true;
    }

    /**
     * 初始化：读取持久化选择并挂接全局窗口监听（含已存在与后续新建的窗口/弹窗）。
     * 须在 JavaFX Application Thread 调用一次（建议在 ThemeManager.init() 之后）。
     */
    public static synchronized void init() {
        if (initialized) return;
        AgentConfig cfg = AgentConfig.getInstance();
        fontId = normalize(cfg.getUiFontFamily(), FONT_OPTIONS.stream().map(FontOption::id).toList(), DEFAULT_FONT);
        monoId = normalize(cfg.getUiFontMono(), MONO_OPTIONS.stream().map(MonoOption::id).toList(), DEFAULT_MONO);
        densityId = normalize(cfg.getUiFontDensity(), DENSITIES.stream().map(Density::id).toList(), DEFAULT_DENSITY);

        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window w : change.getAddedSubList()) hookWindow(w);
            }
        });
        for (Window w : Window.getWindows()) hookWindow(w);
        initialized = true;
        log.info("字体管理器已初始化: font={} mono={} density={}", fontId, monoId, densityId);
    }

    // ==================== 配置入口（面板调用，立即全局生效） ====================

    public static void setFontFamily(String id) { apply(() -> fontId = normalizeFont(id)); AgentConfig.getInstance().setUiFontFamily(fontId); persist(); }
    public static void setMonoFamily(String id) { apply(() -> monoId = normalizeMono(id)); AgentConfig.getInstance().setUiFontMono(monoId); persist(); }
    public static void setDensity(String id)    { apply(() -> densityId = normalizeDensity(id)); AgentConfig.getInstance().setUiFontDensity(densityId); persist(); }

    public static String getFontFamily() { return fontId; }
    public static String getMonoFamily() { return monoId; }
    public static String getDensity()    { return densityId; }

    /** 对话气泡（WebView）正文字号；MarkdownBubble 构建 HTML 时读取。 */
    public static double chatFontPx()     { return density().fontPx(); }
    public static double chatLineHeight() { return density().lineHeight(); }

    /** 当前界面字体的完整回退栈（供 WebView body / 内联样式复用）。 */
    public static String uiStack()   { return fontOption().stack(); }
    public static String monoStack() { return monoOption().stack(); }

    /** 变更可观察属性（面板据此刷新）。 */
    public static ReadOnlyIntegerProperty revisionProperty() { return revision; }

    /** 工作区切换后重读该工作区记忆的字体。 */
    public static void reload() {
        AgentConfig cfg = AgentConfig.getInstance();
        fontId = normalizeFont(cfg.getUiFontFamily());
        monoId = normalizeMono(cfg.getUiFontMono());
        densityId = normalizeDensity(cfg.getUiFontDensity());
        applyToAllWindows();
        revision.set(revision.get() + 1);
    }

    // ==================== 内部实现 ====================

    private static void apply(Runnable mutate) {
        mutate.run();
        applyToAllWindows();
        revision.set(revision.get() + 1);
    }

    private static void persist() { AgentConfig.getInstance().save(); }

    private static void hookWindow(Window window) {
        Scene scene = window.getScene();
        if (scene != null) hookScene(scene);
        window.sceneProperty().addListener((obs, o, n) -> { if (n != null) hookScene(n); });
    }

    private static void hookScene(Scene scene) {
        applyToScene(scene);
        // 主题切换会替换 root 而非 scene，这里也跟随 root 变化补挂
        scene.rootProperty().addListener((obs, o, n) -> applyToScene(scene));
    }

    private static void applyToAllWindows() {
        Runnable r = () -> { for (Window w : Window.getWindows()) {
            if (w.getScene() != null) applyToScene(w.getScene());
        }};
        if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
    }

    /**
     * 默认（native 字体 + cascadia 等宽）时移除用户样式表，回到 chat.css 基线；
     * 否则生成一张覆盖样式表追加到 chat.css 之后。
     */
    private static void applyToScene(Scene scene) {
        if (scene == null) return;
        // 先移除旧的用户字体样式表（data: URI，以特征串识别）
        scene.getStylesheets().removeIf(s -> s.startsWith("data:text/css") && s.contains("JC_USER_FONT"));
        boolean isDefault = DEFAULT_FONT.equals(fontId) && DEFAULT_MONO.equals(monoId);
        if (isDefault) return;
        scene.getStylesheets().add(buildUserStylesheet());
    }

    /** 生成 data: URI 用户样式表：重指向 .root 字体族 + 全部等宽类。 */
    private static String buildUserStylesheet() {
        StringBuilder css = new StringBuilder("/* JC_USER_FONT */\n");
        css.append(".root { -fx-font-family: ").append(fontOption().stack()).append("; }\n");
        if (!DEFAULT_MONO.equals(monoId)) {
            String mono = monoOption().stack();
            css.append(String.join(",\n", MONO_SELECTORS))
               .append(" { -fx-font-family: ").append(mono).append("; }\n");
        }
        String enc = URLEncoder.encode(css.toString(), StandardCharsets.UTF_8).replace("+", "%20");
        return "data:text/css," + enc;
    }

    private static FontOption fontOption() {
        return FONT_OPTIONS.stream().filter(f -> f.id().equals(fontId)).findFirst().orElse(FONT_OPTIONS.get(0));
    }
    private static MonoOption monoOption() {
        return MONO_OPTIONS.stream().filter(m -> m.id().equals(monoId)).findFirst().orElse(MONO_OPTIONS.get(0));
    }
    private static Density density() {
        return DENSITIES.stream().filter(d -> d.id().equals(densityId)).findFirst().orElse(DENSITIES.get(1));
    }

    private static String normalizeFont(String id)    { return normalize(id, FONT_OPTIONS.stream().map(FontOption::id).toList(), DEFAULT_FONT); }
    private static String normalizeMono(String id)    { return normalize(id, MONO_OPTIONS.stream().map(MonoOption::id).toList(), DEFAULT_MONO); }
    private static String normalizeDensity(String id) { return normalize(id, DENSITIES.stream().map(Density::id).toList(), DEFAULT_DENSITY); }

    private static String normalize(String id, List<String> valid, String fallback) {
        return (id != null && valid.contains(id)) ? id : fallback;
    }
}
