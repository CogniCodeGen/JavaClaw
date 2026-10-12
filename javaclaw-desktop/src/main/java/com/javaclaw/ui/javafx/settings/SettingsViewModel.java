package com.javaclaw.ui.javafx.settings;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** 设置窗口的页面状态；不持有 Service、Repository 或 JavaFX Node。 */
public final class SettingsViewModel {

    private final ObjectProperty<SettingsCategory> selectedCategory =
            new SimpleObjectProperty<>(SettingsCategory.MODEL);
    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final BooleanProperty testing = new SimpleBooleanProperty(false);
    private final ReadOnlyIntegerWrapper dirtyCount = new ReadOnlyIntegerWrapper(0);
    private final EnumSet<SettingsCategory> dirtyCategories =
            EnumSet.noneOf(SettingsCategory.class);

    public ObjectProperty<SettingsCategory> selectedCategoryProperty() {
        return selectedCategory;
    }

    public SettingsCategory selectedCategory() {
        return selectedCategory.get();
    }

    public void select(SettingsCategory category) {
        selectedCategory.set(category);
    }

    public BooleanProperty loadingProperty() {
        return loading;
    }

    public boolean loading() {
        return loading.get();
    }

    public void loading(boolean value) {
        loading.set(value);
    }

    public BooleanProperty testingProperty() {
        return testing;
    }

    public boolean testing() {
        return testing.get();
    }

    public void testing(boolean value) {
        testing.set(value);
    }

    public ReadOnlyIntegerProperty dirtyCountProperty() {
        return dirtyCount.getReadOnlyProperty();
    }

    public int dirtyCount() {
        return dirtyCount.get();
    }

    public boolean isDirty(SettingsCategory category) {
        return dirtyCategories.contains(category);
    }

    public Set<SettingsCategory> dirtyCategories() {
        return Collections.unmodifiableSet(EnumSet.copyOf(dirtyCategories));
    }

    public void markDirty(SettingsCategory category) {
        if (dirtyCategories.add(category)) dirtyCount.set(dirtyCategories.size());
    }

    public void clearDirty(SettingsCategory category) {
        if (dirtyCategories.remove(category)) dirtyCount.set(dirtyCategories.size());
    }

    public void clearDirty() {
        dirtyCategories.clear();
        dirtyCount.set(0);
    }
}
