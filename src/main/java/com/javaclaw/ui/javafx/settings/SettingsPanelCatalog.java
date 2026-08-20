package com.javaclaw.ui.javafx.settings;

import javafx.scene.Node;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** 设置分区按首次访问创建；一个窗口内复用，并按创建反序释放。 */
final class SettingsPanelCatalog implements AutoCloseable {

    record Definition(SettingsCategory category, Supplier<Panel> factory) {
        Definition {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(factory, "factory");
        }
    }

    record Panel(
            SettingsCategory category,
            Node root,
            SettingsPanelActions actions,
            Runnable reload,
            Runnable deactivate,
            AutoCloseable resource) {
        Panel {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(actions, "actions");
            Objects.requireNonNull(resource, "resource");
            reload = reload == null ? () -> { } : reload;
            deactivate = deactivate == null ? () -> { } : deactivate;
        }

        Panel(SettingsCategory category, Node root, SettingsPanelActions actions,
              Runnable reload, AutoCloseable resource) {
            this(category, root, actions, reload, null, resource);
        }
    }

    private final Map<SettingsCategory, Definition> definitions;
    private final Map<SettingsCategory, Panel> loaded = new EnumMap<>(SettingsCategory.class);
    private final List<Panel> loadOrder = new ArrayList<>();
    private boolean closed;

    SettingsPanelCatalog(List<Definition> definitions) {
        EnumMap<SettingsCategory, Definition> indexed = new EnumMap<>(SettingsCategory.class);
        for (Definition definition : definitions) {
            if (indexed.put(definition.category(), definition) != null) {
                throw new IllegalArgumentException("重复的设置分区: " + definition.category());
            }
        }
        if (indexed.size() != SettingsCategory.values().length) {
            throw new IllegalArgumentException("设置分区不完整: " + indexed.keySet());
        }
        this.definitions = Map.copyOf(indexed);
    }

    synchronized Panel load(SettingsCategory category) {
        if (closed) throw new IllegalStateException("设置分区目录已关闭");
        Panel existing = loaded.get(category);
        if (existing != null) return existing;
        Definition definition = Objects.requireNonNull(definitions.get(category),
                "缺少设置分区: " + category);
        Panel created = Objects.requireNonNull(definition.factory().get(),
                "设置分区工厂返回空值: " + category);
        if (created.category() != category) {
            IllegalStateException failure = new IllegalStateException(
                    "设置分区工厂返回错误分类: " + created.category());
            try { created.resource().close(); }
            catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
        loaded.put(category, created);
        loadOrder.add(created);
        return created;
    }

    synchronized Panel loaded(SettingsCategory category) { return loaded.get(category); }

    synchronized List<Panel> loadedPanels() { return List.copyOf(loadOrder); }

    synchronized int loadedCount() { return loaded.size(); }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        for (int i = loadOrder.size() - 1; i >= 0; i--) {
            try { loadOrder.get(i).resource().close(); }
            catch (Throwable closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        loaded.clear();
        loadOrder.clear();
        if (failure != null) throw new IllegalStateException("释放设置分区失败", failure);
    }
}
