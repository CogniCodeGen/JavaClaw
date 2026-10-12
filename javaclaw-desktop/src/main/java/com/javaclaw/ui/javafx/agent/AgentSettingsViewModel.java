package com.javaclaw.ui.javafx.agent;

import com.javaclaw.application.agent.AgentManagementApplicationService.Agent;
import com.javaclaw.application.agent.AgentManagementApplicationService.Catalog;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.Optional;

/** 智能体设置页的纯 JavaFX 状态；不持有应用服务或仓储。 */
public final class AgentSettingsViewModel {

    private final ObjectProperty<Catalog> catalog =
            new SimpleObjectProperty<>(this, "catalog", new Catalog(java.util.List.of()));
    private final StringProperty selectedId = new SimpleStringProperty(this, "selectedId");
    private final StringProperty status = new SimpleStringProperty(this, "status", "");
    private final BooleanProperty statusError =
            new SimpleBooleanProperty(this, "statusError", false);
    private final BooleanProperty loading = new SimpleBooleanProperty(this, "loading", false);
    private final BooleanProperty mutating = new SimpleBooleanProperty(this, "mutating", false);
    private final BooleanProperty optimizing = new SimpleBooleanProperty(this, "optimizing", false);

    public ObjectProperty<Catalog> catalogProperty() { return catalog; }
    public StringProperty selectedIdProperty() { return selectedId; }
    public StringProperty statusProperty() { return status; }
    public BooleanProperty statusErrorProperty() { return statusError; }
    public BooleanProperty loadingProperty() { return loading; }
    public BooleanProperty mutatingProperty() { return mutating; }
    public BooleanProperty optimizingProperty() { return optimizing; }

    public void apply(Catalog value) {
        catalog.set(java.util.Objects.requireNonNull(value, "value"));
        String selected = selectedId.get();
        if (selected != null && value.agents().stream().noneMatch(a -> a.id().equals(selected))) {
            selectedId.set(null);
        }
    }

    public Optional<Agent> selectedAgent() {
        String id = selectedId.get();
        if (id == null) return Optional.empty();
        return catalog.get().agents().stream().filter(agent -> agent.id().equals(id)).findFirst();
    }

    public void showStatus(String message, boolean error) {
        status.set(message == null ? "" : message);
        statusError.set(error);
    }
}
