package com.javaclaw.ui.javafx.settings;

import javafx.scene.Node;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 一次设置窗口打开期间创建的全部分区及其统一释放边界。 */
final class SettingsPanelCatalog implements AutoCloseable {

    record Panel(
            SettingsCategory category,
            Node root,
            SettingsPanelActions actions,
            Runnable reload) {

        Panel {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(actions, "actions");
            reload = reload == null ? () -> { } : reload;
        }
    }

    private final Map<SettingsCategory, Panel> panels;
    private final List<? extends AutoCloseable> resources;

    SettingsPanelCatalog(List<Panel> panels, List<? extends AutoCloseable> resources) {
        EnumMap<SettingsCategory, Panel> indexed = new EnumMap<>(SettingsCategory.class);
        for (Panel panel : panels) {
            if (indexed.put(panel.category(), panel) != null) {
                throw new IllegalArgumentException("重复的设置分区: " + panel.category());
            }
        }
        if (indexed.size() != SettingsCategory.values().length) {
            throw new IllegalArgumentException("设置分区不完整: " + indexed.keySet());
        }
        this.panels = Map.copyOf(indexed);
        this.resources = List.copyOf(resources);
    }

    Panel panel(SettingsCategory category) {
        return Objects.requireNonNull(panels.get(category), "缺少设置分区: " + category);
    }

    List<Panel> orderedPanels() {
        return java.util.Arrays.stream(SettingsCategory.values()).map(this::panel).toList();
    }

    void reloadAll() {
        orderedPanels().forEach(panel -> panel.reload().run());
    }

    @Override
    public void close() {
        Throwable failure = null;
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (Throwable closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            throw new IllegalStateException("释放设置分区失败", failure);
        }
    }
}
