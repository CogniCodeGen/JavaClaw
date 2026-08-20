package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.application.plugin.PluginManagementApplicationService.Catalog;
import com.javaclaw.application.plugin.PluginManagementApplicationService.Plugin;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;
import java.util.Locale;

/** 插件中心页面状态，不持有插件服务、文件系统或窗口对象。 */
final class PluginCenterViewModel {

    enum Tab { INSTALLED, MARKET, AGENT_EXTENSIONS, SERVICE_PLUGINS }

    private final ObservableList<Plugin> plugins = FXCollections.observableArrayList();
    private final ObjectProperty<Tab> tab = new SimpleObjectProperty<>(Tab.INSTALLED);
    private final StringProperty query = new SimpleStringProperty("");
    private final StringProperty selectedId = new SimpleStringProperty();
    private final StringProperty status = new SimpleStringProperty("");
    private final StringProperty error = new SimpleStringProperty("");
    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final BooleanProperty mutating = new SimpleBooleanProperty(false);
    private final BooleanProperty detailLoading = new SimpleBooleanProperty(false);

    ObservableList<Plugin> plugins() { return plugins; }
    ObjectProperty<Tab> tabProperty() { return tab; }
    StringProperty queryProperty() { return query; }
    StringProperty selectedIdProperty() { return selectedId; }
    StringProperty statusProperty() { return status; }
    StringProperty errorProperty() { return error; }
    BooleanProperty loadingProperty() { return loading; }
    BooleanProperty mutatingProperty() { return mutating; }
    BooleanProperty detailLoadingProperty() { return detailLoading; }

    void apply(Catalog catalog) {
        plugins.setAll(catalog.plugins());
        String selected = selectedId.get();
        if (selected != null && plugins.stream().noneMatch(p -> p.id().equals(selected))) {
            selectedId.set(null);
        }
        error.set("");
    }

    List<Plugin> filteredPlugins() {
        String needle = query.get() == null ? "" : query.get().trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return List.copyOf(plugins);
        return plugins.stream()
                .filter(plugin -> plugin.name().toLowerCase(Locale.ROOT).contains(needle)
                        || plugin.description().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    void select(String pluginId) {
        selectedId.set(pluginId);
        tab.set(Tab.INSTALLED);
    }

    void showStatus(String message) {
        error.set("");
        status.set(message == null ? "" : message);
    }

    void showFailure(String prefix, Throwable failure) {
        String detail = PluginCenterCleanup.failureMessage(failure);
        error.set(detail);
        status.set(prefix + "：" + detail);
    }
}
