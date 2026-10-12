package com.javaclaw.ui.javafx.settings;

import com.javaclaw.ui.javafx.theme.ThemeOption;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;

/** 界面风格页的纯 JavaFX 状态。 */
public final class AppearanceSettingsViewModel {

    private final ObservableList<ThemeOption> themes = FXCollections.observableArrayList();
    private final StringProperty currentThemeId = new SimpleStringProperty("");

    public void load(List<ThemeOption> values, String current) {
        themes.setAll(values);
        currentThemeId.set(current == null ? "" : current);
    }

    public ObservableList<ThemeOption> themes() { return themes; }
    public StringProperty currentThemeIdProperty() { return currentThemeId; }
}
