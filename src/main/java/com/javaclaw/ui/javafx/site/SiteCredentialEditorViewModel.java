package com.javaclaw.ui.javafx.site;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 站点凭据编辑表单状态，不持有应用服务。 */
public final class SiteCredentialEditorViewModel {

    private final StringProperty id = new SimpleStringProperty("");
    private final StringProperty name = new SimpleStringProperty("");
    private final StringProperty host = new SimpleStringProperty("");
    private final StringProperty loginUrl = new SimpleStringProperty("");
    private final StringProperty username = new SimpleStringProperty("");
    private final StringProperty password = new SimpleStringProperty("");
    private final StringProperty notes = new SimpleStringProperty("");
    private final BooleanBinding valid = Bindings.createBooleanBinding(
            () -> !name.get().isBlank() && !host.get().isBlank(), name, host);

    public StringProperty idProperty() { return id; }
    public StringProperty nameProperty() { return name; }
    public StringProperty hostProperty() { return host; }
    public StringProperty loginUrlProperty() { return loginUrl; }
    public StringProperty usernameProperty() { return username; }
    public StringProperty passwordProperty() { return password; }
    public StringProperty notesProperty() { return notes; }
    public BooleanBinding validBinding() { return valid; }
}
