package com.javaclaw.ui.javafx.site;

import com.javaclaw.application.site.SiteCredentialApplicationService.Credential;
import com.javaclaw.application.site.SiteCredentialApplicationService.SaveCommand;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;

/** 站点凭据编辑器 Controller；只协调字段绑定与密码可见性。 */
public final class SiteCredentialEditorController implements AutoCloseable {

    @FXML private Label titleLabel;
    @FXML private TextField nameField;
    @FXML private TextField hostField;
    @FXML private TextField loginUrlField;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private TextField passwordPlainField;
    @FXML private ToggleButton revealButton;
    @FXML private TextArea notesArea;

    private final SiteCredentialEditorViewModel viewModel = new SiteCredentialEditorViewModel();

    @FXML
    private void initialize() {
        nameField.textProperty().bindBidirectional(viewModel.nameProperty());
        hostField.textProperty().bindBidirectional(viewModel.hostProperty());
        loginUrlField.textProperty().bindBidirectional(viewModel.loginUrlProperty());
        usernameField.textProperty().bindBidirectional(viewModel.usernameProperty());
        passwordField.textProperty().bindBidirectional(viewModel.passwordProperty());
        passwordPlainField.textProperty().bindBidirectional(viewModel.passwordProperty());
        notesArea.textProperty().bindBidirectional(viewModel.notesProperty());
    }

    void configure(Credential existing) {
        boolean editing = existing != null;
        titleLabel.setText(editing ? "编辑站点" : "添加站点");
        viewModel.idProperty().set(editing ? existing.id() : "");
        viewModel.nameProperty().set(editing ? existing.name() : "");
        viewModel.hostProperty().set(editing ? existing.hostPattern() : "");
        viewModel.loginUrlProperty().set(editing ? existing.loginUrl() : "");
        viewModel.usernameProperty().set(editing ? existing.username() : "");
        viewModel.passwordProperty().set(editing ? existing.password() : "");
        viewModel.notesProperty().set(editing ? existing.notes() : "");
    }

    @FXML
    private void revealRequested() {
        boolean reveal = revealButton.isSelected();
        passwordPlainField.setVisible(reveal);
        passwordPlainField.setManaged(reveal);
        passwordField.setVisible(!reveal);
        passwordField.setManaged(!reveal);
        revealButton.setText(reveal ? "🙈" : "👁");
    }

    SaveCommand command() {
        return new SaveCommand(viewModel.idProperty().get(), viewModel.nameProperty().get(),
                viewModel.hostProperty().get(), viewModel.loginUrlProperty().get(),
                viewModel.usernameProperty().get(), viewModel.passwordProperty().get(),
                viewModel.notesProperty().get());
    }

    javafx.beans.binding.BooleanBinding validBinding() {
        return viewModel.validBinding();
    }

    @Override
    public void close() {
        nameField.textProperty().unbindBidirectional(viewModel.nameProperty());
        hostField.textProperty().unbindBidirectional(viewModel.hostProperty());
        loginUrlField.textProperty().unbindBidirectional(viewModel.loginUrlProperty());
        usernameField.textProperty().unbindBidirectional(viewModel.usernameProperty());
        passwordField.textProperty().unbindBidirectional(viewModel.passwordProperty());
        passwordPlainField.textProperty().unbindBidirectional(viewModel.passwordProperty());
        notesArea.textProperty().unbindBidirectional(viewModel.notesProperty());
    }
}
