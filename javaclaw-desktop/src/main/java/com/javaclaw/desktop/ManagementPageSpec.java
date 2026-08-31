package com.javaclaw.desktop;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Region;

/**
 * 管理页的窗口、导航栏与领域样式规格。
 *
 * <p>规格只影响 Desktop 展示，不改变 SDK DTO 或服务端状态。推荐尺寸用于页面首次打开，最小尺寸用于保证固定操作栏和表单仍可达。
 *
 * @param section 页面入口使用的稳定英文标识
 * @param title 页面窗口标题
 * @param preferredWidth 首次打开的推荐宽度，单位为逻辑像素
 * @param preferredHeight 首次打开的推荐高度，单位为逻辑像素
 * @param minimumWidth 允许用户缩放到的最小宽度，单位为逻辑像素
 * @param minimumHeight 允许用户缩放到的最小高度，单位为逻辑像素
 * @param railWidth 对象导航栏宽度，单位为逻辑像素
 * @param stylesheets 仅在当前页面子树加载的历史领域样式名
 */
record ManagementPageSpec(
        String section,
        String title,
        double preferredWidth,
        double preferredHeight,
        double minimumWidth,
        double minimumHeight,
        double railWidth,
        List<String> stylesheets) {
    private static final Map<String, ManagementPageSpec> SPECS = java.util.stream.Stream.of(
                    spec("Settings", "设置", 1100, 760, 900, 680, 210),
                    spec("Profiles", "Agent Studio", 1100, 760, 900, 680, 232, "workflow-center"),
                    spec("Memory", "记忆中心", 1000, 680, 820, 600, 212, "memory-center"),
                    spec("Knowledge", "知识库中心", 1340, 864, 1040, 680, 252, "knowledge-center"),
                    spec("Skills", "技能中心", 920, 650, 780, 560, 232, "skill-center"),
                    spec(
                            "Automation",
                            "自动化 · Loop / Workflow / SDD",
                            1240,
                            800,
                            960,
                            680,
                            258,
                            "workflow-center",
                            "sdd-task"),
                    spec("Schedules", "定时任务", 960, 680, 780, 560, 264, "schedule"),
                    spec("Plugins", "插件中心", 960, 680, 780, 560, 228, "plugins"),
                    spec("MCP", "MCP 连接", 960, 680, 780, 560, 224, "mcp-settings"),
                    spec("Sites", "站点管理", 1080, 740, 860, 620, 232),
                    spec("Instructions", "项目约定", 1000, 700, 820, 600, 232),
                    spec("Worktrees", "协作与工作树恢复", 1100, 740, 900, 620, 252, "workflow-center"))
            .collect(java.util.stream.Collectors.toUnmodifiableMap(ManagementPageSpec::section, value -> value));

    ManagementPageSpec {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(title, "title");
        stylesheets = List.copyOf(stylesheets);
    }

    static ManagementPageSpec forSection(String section) {
        return SPECS.getOrDefault(
                section,
                new ManagementPageSpec(
                        section == null ? "Providers" : section, "云模型设置", 1100, 760, 900, 680, 224, List.of()));
    }

    static List<ManagementPageSpec> all() {
        return SPECS.values().stream()
                .sorted(java.util.Comparator.comparing(ManagementPageSpec::section))
                .toList();
    }

    void configure(Parent page) {
        Objects.requireNonNull(page, "page");
        page.getStyleClass().addAll("management-page", "management-page-" + cssName(section));
        configureRail(page);
    }

    private void configureRail(Node node) {
        if (node instanceof Region region && node.getStyleClass().contains("management-rail")) {
            region.setMinWidth(railWidth);
            region.setPrefWidth(railWidth);
            region.setMaxWidth(railWidth);
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(this::configureRail);
        }
    }

    private static ManagementPageSpec spec(
            String section,
            String title,
            double preferredWidth,
            double preferredHeight,
            double minimumWidth,
            double minimumHeight,
            double railWidth,
            String... stylesheets) {
        return new ManagementPageSpec(
                section,
                title,
                preferredWidth,
                preferredHeight,
                minimumWidth,
                minimumHeight,
                railWidth,
                List.of(stylesheets));
    }

    private static String cssName(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }
}
