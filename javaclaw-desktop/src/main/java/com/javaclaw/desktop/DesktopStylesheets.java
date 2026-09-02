package com.javaclaw.desktop;

import java.net.URL;
import java.util.List;
import java.util.Objects;

import javafx.scene.Scene;

import com.javaclaw.desktop.appearance.DesktopAppearanceManager;
import com.javaclaw.desktop.appearance.JavaPreferencesAppearanceStore;

/** 按固定级联顺序加载 509f197 视觉基线与 v5 Desktop 壳样式。 */
public final class DesktopStylesheets {
    static final List<String> BASELINE_RESOURCES = List.of(
            "/css/design-tokens-controls.css",
            "/css/navigation.css",
            "/css/chat-surface.css",
            "/css/settings-extensions.css",
            "/css/interaction-overlays.css",
            "/css/design-system-components.css",
            "/css/themes-shell.css");
    private static final List<String> RESOURCES = appendShellStylesheet(BASELINE_RESOURCES);

    private DesktopStylesheets() {}

    /**
     * 将全部样式添加到 Scene；任一资源缺失时立即失败，禁止使用不完整视觉基线启动。
     *
     * @param scene 接收样式的 Scene
     */
    public static void apply(Scene scene) {
        Objects.requireNonNull(scene, "scene");
        for (String resource : RESOURCES) {
            scene.getStylesheets().add(requireResource(resource).toExternalForm());
        }
        DesktopAppearanceManager.apply(scene, new JavaPreferencesAppearanceStore().load());
    }

    private static List<String> appendShellStylesheet(List<String> baseline) {
        java.util.ArrayList<String> resources = new java.util.ArrayList<>(baseline);
        resources.add("/css/management-center.css");
        resources.add("/css/desktop.css");
        return List.copyOf(resources);
    }

    private static URL requireResource(String path) {
        URL resource = DesktopStylesheets.class.getResource(path);
        if (resource == null) {
            throw new IllegalStateException("Desktop 样式资源不存在：" + path);
        }
        return resource;
    }
}
