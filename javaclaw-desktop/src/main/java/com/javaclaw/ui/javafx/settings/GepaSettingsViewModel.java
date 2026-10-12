package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.BehaviorSettingsApplicationService.GepaSettings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** GEPA 设置页的纯 JavaFX 状态。 */
public final class GepaSettingsViewModel {

    private final BooleanProperty goalDecompositionEnabled = new SimpleBooleanProperty();
    private final StringProperty evaluationInterval = new SimpleStringProperty("");
    private final StringProperty evaluationThreshold = new SimpleStringProperty("");
    private final BooleanProperty adaptivePlanningEnabled = new SimpleBooleanProperty();
    private final StringProperty feedbackMaxRounds = new SimpleStringProperty("");
    private final StringProperty error = new SimpleStringProperty("");
    private final BooleanProperty busy = new SimpleBooleanProperty();

    public void load(GepaSettings settings) {
        goalDecompositionEnabled.set(settings.goalDecompositionEnabled());
        evaluationInterval.set(Integer.toString(settings.evaluationInterval()));
        evaluationThreshold.set(Double.toString(settings.evaluationThreshold()));
        adaptivePlanningEnabled.set(settings.adaptivePlanningEnabled());
        feedbackMaxRounds.set(Integer.toString(settings.feedbackMaxRounds()));
        error.set("");
    }

    public BooleanProperty goalDecompositionEnabledProperty() {
        return goalDecompositionEnabled;
    }

    public StringProperty evaluationIntervalProperty() {
        return evaluationInterval;
    }

    public StringProperty evaluationThresholdProperty() {
        return evaluationThreshold;
    }

    public BooleanProperty adaptivePlanningEnabledProperty() {
        return adaptivePlanningEnabled;
    }

    public StringProperty feedbackMaxRoundsProperty() {
        return feedbackMaxRounds;
    }

    public StringProperty errorProperty() {
        return error;
    }

    public BooleanProperty busyProperty() {
        return busy;
    }
}
