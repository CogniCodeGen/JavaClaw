package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.BehaviorSettingsApplicationService.GeneralSettings;
import com.javaclaw.ui.javafx.theme.ThemeOption;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;

/** 通用设置页的纯 JavaFX 状态。 */
public final class GeneralSettingsViewModel {

    private final ObservableList<ThemeOption> themes = FXCollections.observableArrayList();
    private final ObjectProperty<ThemeOption> selectedTheme = new SimpleObjectProperty<>();
    private final BooleanProperty minimizeToTrayOnClose = new SimpleBooleanProperty();
    private final BooleanProperty taskRiskAutoApproveEnabled = new SimpleBooleanProperty();
    private final StringProperty error = new SimpleStringProperty("");
    private final BooleanProperty busy = new SimpleBooleanProperty();

    public void load(GeneralSettings settings, List<ThemeOption> options, ThemeOption current) {
        themes.setAll(options);
        selectedTheme.set(current);
        minimizeToTrayOnClose.set(settings.minimizeToTrayOnClose());
        taskRiskAutoApproveEnabled.set(settings.taskRiskAutoApproveEnabled());
        error.set("");
    }

    public ObservableList<ThemeOption> themes() { return themes; }
    public ObjectProperty<ThemeOption> selectedThemeProperty() { return selectedTheme; }
    public BooleanProperty minimizeToTrayOnCloseProperty() { return minimizeToTrayOnClose; }
    public BooleanProperty taskRiskAutoApproveEnabledProperty() {
        return taskRiskAutoApproveEnabled;
    }
    public StringProperty errorProperty() { return error; }
    public BooleanProperty busyProperty() { return busy; }
}
