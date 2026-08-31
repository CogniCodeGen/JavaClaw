package com.javaclaw.desktop;

import java.util.List;
import java.util.Objects;

import javafx.scene.Parent;

/** 原桌面的主题与样式入口；只管理 JavaFX 表现，偏好持久化必须交给 SDK。 */
public final class DesktopTheme {
    public static final String DEFAULT_ID = "emerald";
    private static final List<Choice> CHOICES = List.of(
            new Choice("emerald", "翡翠"),
            new Choice("midnight", "午夜"),
            new Choice("carbon", "碳黑"),
            new Choice("sapphire", "蓝宝石"),
            new Choice("ocean", "海洋"),
            new Choice("plum", "梅紫"),
            new Choice("terracotta", "陶土"),
            new Choice("honey", "蜂蜜"),
            new Choice("graphite", "石墨"));
    private static final List<String> BASE_STYLESHEETS = List.of("chat", "controls", "desktop");

    private DesktopTheme() {}

    /** 返回与原版顺序一致的九个主题选项；集合不可修改。 */
    public static List<Choice> choices() {
        return CHOICES;
    }

    static List<String> baseStylesheets() {
        return BASE_STYLESHEETS;
    }

    /** 在 FX 线程给窗口或对话框根节点装配原始样式；未知主题回到翡翠，不加载客户端指定的任意文件。 */
    public static void apply(Parent root, String themeId) {
        Objects.requireNonNull(root, "root");
        for (String sheet : BASE_STYLESHEETS) {
            String resource = resource(sheet);
            if (!root.getStylesheets().contains(resource)) {
                root.getStylesheets().add(resource);
            }
        }
        root.getStyleClass().removeIf(value -> value.startsWith("theme-"));
        String selected = CHOICES.stream().anyMatch(value -> value.id().equals(themeId)) ? themeId : DEFAULT_ID;
        root.getStyleClass().add("theme-" + selected);
    }

    /** 仅在当前管理页子树装配领域样式，防止知识、技能、MCP 等选择器污染其他页面。 */
    static void applyPage(Parent page, ManagementPageSpec spec) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(spec, "spec");
        for (String sheet : spec.stylesheets()) {
            String resource = resource(sheet);
            if (!page.getStylesheets().contains(resource)) {
                page.getStylesheets().add(resource);
            }
        }
    }

    private static String resource(String sheet) {
        return Objects.requireNonNull(DesktopTheme.class.getResource("/css/" + sheet + ".css"))
                .toExternalForm();
    }

    /**
     * 原版主题选项。
     *
     * @param id 稳定的非空 CSS 主题标识
     * @param name 非空中文展示名
     */
    public record Choice(String id, String name) {}
}
