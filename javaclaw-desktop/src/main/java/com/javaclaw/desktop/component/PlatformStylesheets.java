package com.javaclaw.desktop.component;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javafx.scene.Parent;
import javafx.scene.Scene;

import com.javaclaw.desktop.appearance.DesktopAppearanceManager;
import com.javaclaw.desktop.appearance.JavaPreferencesAppearanceStore;

/** 在平台组件层加载固定视觉基线，避免 Dialog 反向依赖 Desktop 编排层。 */
public final class PlatformStylesheets {
    private static final List<String> BASELINE_RESOURCES = List.of(
            "/css/design-tokens-controls.css",
            "/css/navigation.css",
            "/css/chat-surface.css",
            "/css/settings-extensions.css",
            "/css/interaction-overlays.css",
            "/css/design-system-components.css",
            "/css/themes-shell.css");
    private static final List<String> RESOURCES = appendShellStylesheet(BASELINE_RESOURCES);

    private PlatformStylesheets() {}

    /**
     * 返回不可变的视觉基线资源及其固定级联顺序。
     *
     * @return 视觉基线资源
     */
    public static List<String> baselineResources() {
        return BASELINE_RESOURCES;
    }

    /**
     * 将全部样式添加到 Scene；任一资源缺失时立即失败，禁止使用不完整视觉基线启动。
     *
     * @param scene 接收样式的 Scene
     */
    public static void apply(Scene scene) {
        Objects.requireNonNull(scene, "scene");
        addStylesheets(scene.getStylesheets());
        DesktopAppearanceManager.apply(scene, new JavaPreferencesAppearanceStore().load());
    }

    /**
     * 将完整 Desktop 样式应用到独立节点树；主要供 JavaFX DialogPane 在展示前使用。
     *
     * @param root 接收样式和已保存外观的根节点
     */
    public static void applyTo(Parent root) {
        Objects.requireNonNull(root, "root");
        addStylesheets(root.getStylesheets());
        DesktopAppearanceManager.applyTo(root, new JavaPreferencesAppearanceStore().load());
    }

    private static void addStylesheets(List<String> stylesheets) {
        for (String resource : RESOURCES) {
            String stylesheet = requireResource(resource).toExternalForm();
            if (!stylesheets.contains(stylesheet)) {
                stylesheets.add(stylesheet);
            }
        }
    }

    private static List<String> appendShellStylesheet(List<String> baseline) {
        ArrayList<String> resources = new ArrayList<>(baseline);
        resources.add("/css/management-center.css");
        resources.add("/css/desktop.css");
        return List.copyOf(resources);
    }

    private static URL requireResource(String path) {
        URL resource = PlatformStylesheets.class.getResource(path);
        if (resource == null) {
            throw new IllegalStateException("Desktop 样式资源不存在：" + path);
        }
        return resource;
    }
}
