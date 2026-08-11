package com.javaclaw.ui.javafx.interaction;

import com.javaclaw.api.interaction.ChoiceOption;
import com.javaclaw.api.interaction.ChoiceRequest;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/** 互斥选择弹窗的页面状态。 */
final class ChoiceDialogViewModel {

    private final StringProperty message = new SimpleStringProperty("");
    private final ObservableList<ChoiceOption> options = FXCollections.observableArrayList();
    private final ObjectProperty<ChoiceOption> selected = new SimpleObjectProperty<>();

    void apply(ChoiceRequest request) {
        message.set(request.message());
        options.setAll(request.options());
        selected.set(options.isEmpty() ? null : options.getFirst());
    }

    StringProperty messageProperty() { return message; }
    ObservableList<ChoiceOption> options() { return options; }
    ObjectProperty<ChoiceOption> selectedProperty() { return selected; }
}
