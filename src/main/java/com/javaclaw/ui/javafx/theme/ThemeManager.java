package com.javaclaw.ui.javafx.theme;

import com.javaclaw.config.AgentConfig;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ListChangeListener;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 界面风格主题管理器（设计稿「live style switching」能力的 JavaFX 实现）
 *
 * <p>原理：chat.css 的全部颜色收敛为挂在 {@code .root} 上的 looked-up colors 令牌；
 * 文件末尾的 {@code .theme-{id}} 覆盖块仅重新指向令牌。本管理器监听
 * {@link Window#getWindows()}，给每个窗口（含弹窗/Popup）的 Scene 根节点
 * 追加 {@code theme-{id}} style class，即可整体换肤、运行时立即生效。</p>
 *
 * <p>选择持久化到当前工作区的 javaclaw-agent.properties（{@code ui.theme}），
 * 切换工作区时由 {@link #reload()} 重新读取。</p>
 */
public final class ThemeManager {

    private static final Logger log = LoggerFactory.getLogger(ThemeManager.class);

    /** 主题描述（id 对应 CSS 覆盖块；brand/bg/surface 供切换 UI 渲染三联色块预览） */
    public record Theme(String id, String name, String subtitle, String brand, String bg, String surface) {}

    /** 全部内置主题，顺序即 UI 展示顺序（与设计稿一致：2 深 7 浅）；首个为默认 */
    public static final List<Theme> THEMES = List.of(
            new Theme("emerald",    "翡翠 Emerald",    "默认 · 随应用发布", "#2E9A6A", "#FBFAF6", "#FFFFFF"),
            new Theme("midnight",   "午夜 Midnight",   "暖色深色模式",      "#3DA574", "#15130E", "#1E1C16"),
            new Theme("carbon",     "碳黑 Carbon",     "冷色深色模式",      "#4F9DF0", "#0F141A", "#171D25"),
            new Theme("sapphire",   "蓝宝石 Sapphire", "冷蓝强调",          "#2A6FDB", "#F8FAFD", "#FFFFFF"),
            new Theme("ocean",      "海洋 Ocean",      "青绿强调",          "#0E8C8C", "#F6FAFA", "#FFFFFF"),
            new Theme("plum",       "梅紫 Plum",       "柔紫强调",          "#7E57C2", "#FAF8FC", "#FFFFFF"),
            new Theme("terracotta", "陶土 Terracotta", "暖陶强调",          "#C9613B", "#FCF8F4", "#FFFFFF"),
            new Theme("honey",      "蜂蜜 Honey",      "琥珀金强调",        "#C68A1E", "#FCFAF4", "#FFFFFF"),
            new Theme("graphite",   "石墨 Graphite",   "中性极简",          "#3F3B33", "#FAF9F5", "#FFFFFF"));

    public static final String DEFAULT_THEME = "emerald";

    private static final String CLASS_PREFIX = "theme-";

    private static final StringProperty current = new SimpleStringProperty(DEFAULT_THEME);

    private static boolean initialized = false;

    private ThemeManager() {}

    /**
     * 初始化：读取持久化主题并挂接全局窗口监听（含已存在与后续新建的窗口/弹出层）。
     * 须在 JavaFX Application Thread 上调用一次。
     */
    public static synchronized void init() {
        if (initialized) {
            return;
        }
        current.set(normalize(AgentConfig.getInstance().getUiTheme()));
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window w : change.getAddedSubList()) {
                    hookWindow(w);
                }
            }
        });
        for (Window w : Window.getWindows()) {
            hookWindow(w);
        }
        initialized = true;
        log.info("主题管理器已初始化，当前主题: {}", current.get());
    }

    /**
     * 切换主题：更新全部已打开窗口的根节点 style class 并持久化到工作区配置。
     */
    public static void setTheme(String id) {
        String normalized = normalize(id);
        if (normalized.equals(current.get())) {
            return;
        }
        current.set(normalized);
        Runnable apply = () -> {
            for (Window w : Window.getWindows()) {
                Scene scene = w.getScene();
                if (scene != null && scene.getRoot() != null) {
                    applyToRoot(scene.getRoot());
                }
            }
        };
        if (Platform.isFxApplicationThread()) {
            apply.run();
        } else {
            Platform.runLater(apply);
        }
        AgentConfig config = AgentConfig.getInstance();
        config.setUiTheme(normalized);
        config.save();
        log.info("界面风格已切换: {}", normalized);
    }

    /** 当前主题 ID */
    public static String getTheme() {
        return current.get();
    }

    /** 当前主题描述（用于切换 UI 展示色块/名称） */
    public static Theme getCurrentTheme() {
        return findTheme(current.get());
    }

    /** 主题变更可观察属性（顶栏「风格」菜单等据此刷新色块） */
    public static ReadOnlyStringProperty themeProperty() {
        return current;
    }

    /** 工作区切换后重新读取该工作区记忆的主题 */
    public static void reload() {
        setTheme(AgentConfig.getInstance().getUiTheme());
    }

    /** 按 id 查找主题，未知 id 回落默认主题 */
    public static Theme findTheme(String id) {
        return THEMES.stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElse(THEMES.get(0));
    }

    // ==================== 内部实现 ====================

    /** 给窗口当前及后续 Scene 应用主题（scene/root 可能延迟设置或被替换） */
    private static void hookWindow(Window window) {
        Scene scene = window.getScene();
        if (scene != null) {
            hookScene(scene);
        }
        window.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                hookScene(newScene);
            }
        });
    }

    private static void hookScene(Scene scene) {
        if (scene.getRoot() != null) {
            applyToRoot(scene.getRoot());
        }
        scene.rootProperty().addListener((obs, oldRoot, newRoot) -> {
            if (newRoot != null) {
                applyToRoot(newRoot);
            }
        });
    }

    /** 根节点仅保留当前主题 class；默认主题不挂 class（令牌基线即翡翠） */
    private static void applyToRoot(Parent root) {
        root.getStyleClass().removeIf(s -> s.startsWith(CLASS_PREFIX));
        String id = current.get();
        if (!DEFAULT_THEME.equals(id)) {
            root.getStyleClass().add(CLASS_PREFIX + id);
        }
    }

    private static String normalize(String id) {
        if (id == null || id.isBlank()) {
            return DEFAULT_THEME;
        }
        return THEMES.stream().anyMatch(t -> t.id().equals(id)) ? id : DEFAULT_THEME;
    }
}
