package com.javaclaw.ui.javafx.settings;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.EnumMap;
import java.util.Map;

/** 左侧设置导航的搜索、选中项与折叠状态。 */
public final class SettingsNavigationViewModel {

    private final StringProperty query = new SimpleStringProperty("");
    private final ObjectProperty<SettingsCategory> selected =
            new SimpleObjectProperty<>(SettingsCategory.MODEL);
    private final Map<SettingsCategory.Group, Boolean> expanded =
            new EnumMap<>(SettingsCategory.Group.class);

    public SettingsNavigationViewModel() {
        for (SettingsCategory.Group group : SettingsCategory.Group.values()) {
            expanded.put(group, group == SettingsCategory.Group.CORE);
        }
    }

    public StringProperty queryProperty() {
        return query;
    }

    public String query() {
        String value = query.get();
        return value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
    }

    public ObjectProperty<SettingsCategory> selectedProperty() {
        return selected;
    }

    public SettingsCategory selected() {
        return selected.get();
    }

    public void select(SettingsCategory category) {
        selected.set(category);
        expanded.put(category.group(), true);
    }

    public boolean expanded(SettingsCategory.Group group) {
        return Boolean.TRUE.equals(expanded.get(group));
    }

    public void toggle(SettingsCategory.Group group) {
        expanded.put(group, !expanded(group));
    }
}
