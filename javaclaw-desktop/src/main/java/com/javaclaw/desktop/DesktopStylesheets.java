package com.javaclaw.desktop;

import java.util.List;

import javafx.scene.Parent;
import javafx.scene.Scene;

import com.javaclaw.desktop.component.PlatformStylesheets;

/** 按固定级联顺序加载 509f197 视觉基线与 v6 Desktop 壳样式。 */
public final class DesktopStylesheets {
    static final List<String> BASELINE_RESOURCES = PlatformStylesheets.baselineResources();

    private DesktopStylesheets() {}

    /**
     * 将全部样式添加到 Scene；任一资源缺失时立即失败，禁止使用不完整视觉基线启动。
     *
     * @param scene 接收样式的 Scene
     */
    public static void apply(Scene scene) {
        PlatformStylesheets.apply(scene);
    }

    /**
     * 将完整 Desktop 样式应用到独立节点树；主要供 JavaFX DialogPane 在展示前使用。
     *
     * @param root 接收样式和已保存外观的根节点
     */
    public static void applyTo(Parent root) {
        PlatformStylesheets.applyTo(root);
    }
}
