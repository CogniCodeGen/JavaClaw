package com.javaclaw.ui.javafx.onboarding;

import com.javaclaw.application.onboarding.OnboardingApplicationService.Provider;
import com.javaclaw.application.onboarding.OnboardingApplicationService.ProviderSetup;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 首次向导的纯 JavaFX 页面状态，不持有应用服务。 */
public final class OnboardingViewModel {

    private final IntegerProperty currentStep = new SimpleIntegerProperty(1);
    private final ObjectProperty<Provider> selectedProvider = new SimpleObjectProperty<>();
    private final ObjectProperty<ProviderSetup> savedSetup = new SimpleObjectProperty<>();
    private final StringProperty status = new SimpleStringProperty("");
    private final ObjectProperty<StatusTone> statusTone =
            new SimpleObjectProperty<>(StatusTone.INFO);
    private final BooleanProperty saving = new SimpleBooleanProperty(false);
    private final BooleanProperty probing = new SimpleBooleanProperty(false);
    private final BooleanProperty completing = new SimpleBooleanProperty(false);

    public IntegerProperty currentStepProperty() { return currentStep; }

    public ObjectProperty<Provider> selectedProviderProperty() { return selectedProvider; }

    public ObjectProperty<ProviderSetup> savedSetupProperty() { return savedSetup; }

    public StringProperty statusProperty() { return status; }

    public ObjectProperty<StatusTone> statusToneProperty() { return statusTone; }

    public BooleanProperty savingProperty() { return saving; }

    public BooleanProperty probingProperty() { return probing; }

    public BooleanProperty completingProperty() { return completing; }

    public void showStatus(String message, StatusTone tone) {
        status.set(message == null ? "" : message);
        statusTone.set(tone == null ? StatusTone.INFO : tone);
    }

    public enum StatusTone { INFO, SUCCESS, ERROR }
}
