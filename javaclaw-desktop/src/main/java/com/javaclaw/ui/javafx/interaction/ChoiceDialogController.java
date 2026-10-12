package com.javaclaw.ui.javafx.interaction;

import com.javaclaw.api.interaction.ChoiceOption;
import com.javaclaw.api.interaction.ChoiceRequest;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;

/** 互斥选择弹窗 Controller。 */
public final class ChoiceDialogController implements AutoCloseable {

    @FXML private Label messageLabel;
    @FXML private ComboBox<ChoiceOption> optionBox;

    private final ChoiceDialogViewModel viewModel = new ChoiceDialogViewModel();

    @FXML
    private void initialize() {
        messageLabel.textProperty().bind(viewModel.messageProperty());
        optionBox.setItems(viewModel.options());
        optionBox.valueProperty().bindBidirectional(viewModel.selectedProperty());
    }

    void configure(ChoiceRequest request) {
        viewModel.apply(java.util.Objects.requireNonNull(request, "request"));
    }

    ReadOnlyObjectProperty<ChoiceOption> selectedProperty() {
        return viewModel.selectedProperty();
    }

    String selectedId() {
        ChoiceOption selected = viewModel.selectedProperty().get();
        return selected == null ? null : selected.id();
    }

    @Override
    public void close() {
        messageLabel.textProperty().unbind();
        optionBox.valueProperty().unbindBidirectional(viewModel.selectedProperty());
        optionBox.setItems(null);
        viewModel.options().clear();
        viewModel.selectedProperty().set(null);
    }
}
