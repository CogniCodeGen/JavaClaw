package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.application.knowledge.KnowledgeApplicationService.Health;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Settings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/** Immutable knowledge settings and health state for the settings page. */
public final class KnowledgeSettingsViewModel {
    private final ObjectProperty<Settings> settings = new SimpleObjectProperty<>();
    private final ObjectProperty<Health> health = new SimpleObjectProperty<>();

    public ObjectProperty<Settings> settingsProperty() { return settings; }
    public ObjectProperty<Health> healthProperty() { return health; }
    public Settings settings() { return settings.get(); }
    public Health health() { return health.get(); }
    public void apply(Settings value, Health healthValue) {
        settings.set(value);
        health.set(healthValue);
    }
    public void updateSettings(Settings value) { settings.set(value); }
    public void updateHealth(Health value) { health.set(value); }
}
