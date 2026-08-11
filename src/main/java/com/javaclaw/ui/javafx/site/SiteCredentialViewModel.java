package com.javaclaw.ui.javafx.site;

import com.javaclaw.application.site.SiteCredentialApplicationService.Snapshot;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 站点管理面板状态；不持有应用服务、仓储或窗口。 */
public final class SiteCredentialViewModel {

    private final ObjectProperty<Snapshot> snapshot = new SimpleObjectProperty<>(
            this, "snapshot", new Snapshot(java.util.List.of(), ""));
    private final BooleanProperty loading = new SimpleBooleanProperty(this, "loading");
    private final BooleanProperty mutating = new SimpleBooleanProperty(this, "mutating");
    private final StringProperty status = new SimpleStringProperty(this, "status", "");
    private final BooleanProperty statusError = new SimpleBooleanProperty(this, "statusError");

    public ObjectProperty<Snapshot> snapshotProperty() { return snapshot; }
    public BooleanProperty loadingProperty() { return loading; }
    public BooleanProperty mutatingProperty() { return mutating; }
    public StringProperty statusProperty() { return status; }
    public BooleanProperty statusErrorProperty() { return statusError; }

    public void apply(Snapshot value) {
        snapshot.set(java.util.Objects.requireNonNull(value, "value"));
    }

    public void showStatus(String message, boolean error) {
        status.set(message == null ? "" : message);
        statusError.set(error);
    }
}
