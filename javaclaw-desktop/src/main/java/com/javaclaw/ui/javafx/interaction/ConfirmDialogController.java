package com.javaclaw.ui.javafx.interaction;

import com.javaclaw.api.interaction.ConfirmRequest;
import javafx.beans.binding.BooleanBinding;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

/** 确认弹窗 Controller；只负责 FXML 字段与页面状态绑定。 */
public final class ConfirmDialogController implements AutoCloseable {

    @FXML private Label descriptionLabel;
    @FXML private Label hintLabel;
    @FXML private TextField keywordField;
    @FXML private TextArea detailsArea;

    private final ConfirmDialogViewModel viewModel = new ConfirmDialogViewModel();

    @FXML
    private void initialize() {
        descriptionLabel.textProperty().bind(viewModel.descriptionProperty());
        hintLabel.textProperty().bind(viewModel.hintProperty());
        hintLabel.visibleProperty().bind(viewModel.hintProperty().isNotEmpty());
        hintLabel.managedProperty().bind(hintLabel.visibleProperty());
        keywordField.promptTextProperty().bind(viewModel.keywordPromptProperty());
        keywordField.textProperty().bindBidirectional(viewModel.keywordInputProperty());
        keywordField.visibleProperty().bind(viewModel.keywordRequiredProperty());
        keywordField.managedProperty().bind(viewModel.keywordRequiredProperty());
        detailsArea.textProperty().bind(viewModel.detailsProperty());
    }

    void configure(ConfirmRequest request) {
        viewModel.apply(java.util.Objects.requireNonNull(request, "request"));
    }

    BooleanBinding validBinding() {
        return viewModel.validBinding();
    }

    @Override
    public void close() {
        descriptionLabel.textProperty().unbind();
        hintLabel.textProperty().unbind();
        hintLabel.visibleProperty().unbind();
        hintLabel.managedProperty().unbind();
        keywordField.promptTextProperty().unbind();
        keywordField.textProperty().unbindBidirectional(viewModel.keywordInputProperty());
        keywordField.visibleProperty().unbind();
        keywordField.managedProperty().unbind();
        detailsArea.textProperty().unbind();
        keywordField.clear();
        viewModel.close();
    }
}
