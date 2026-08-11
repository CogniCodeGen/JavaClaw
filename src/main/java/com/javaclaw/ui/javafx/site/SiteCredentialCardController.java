package com.javaclaw.ui.javafx.site;

import com.javaclaw.application.site.SiteCredentialApplicationService.Credential;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.function.Consumer;

/** 可复用站点凭据卡片 Controller；仅转发用户动作。 */
public final class SiteCredentialCardController implements AutoCloseable {

    @FXML private VBox root;
    @FXML private Label sessionBadge;
    @FXML private Label nameLabel;
    @FXML private Button resetButton;
    @FXML private Label hostLabel;
    @FXML private Label accountLabel;
    @FXML private Label timestampsLabel;
    @FXML private Label notesLabel;

    private final SiteCredentialCardViewModel viewModel = new SiteCredentialCardViewModel();
    private String credentialId;
    private Consumer<String> edit = ignored -> {};
    private Consumer<String> reset = ignored -> {};
    private Consumer<String> delete = ignored -> {};

    @FXML
    private void initialize() {
        nameLabel.textProperty().bind(viewModel.nameProperty());
        sessionBadge.textProperty().bind(viewModel.sessionProperty());
        hostLabel.textProperty().bind(viewModel.hostProperty());
        accountLabel.textProperty().bind(viewModel.accountProperty());
        timestampsLabel.textProperty().bind(viewModel.timestampsProperty());
        timestampsLabel.visibleProperty().bind(viewModel.timestampsVisibleProperty());
        timestampsLabel.managedProperty().bind(viewModel.timestampsVisibleProperty());
        notesLabel.textProperty().bind(viewModel.notesProperty());
        notesLabel.visibleProperty().bind(viewModel.notesVisibleProperty());
        notesLabel.managedProperty().bind(viewModel.notesVisibleProperty());
        resetButton.disableProperty().bind(viewModel.hasSessionProperty().not());
    }

    void configure(
            Credential credential,
            Consumer<String> editAction,
            Consumer<String> resetAction,
            Consumer<String> deleteAction) {
        Objects.requireNonNull(credential, "credential");
        credentialId = credential.id();
        edit = Objects.requireNonNull(editAction, "editAction");
        reset = Objects.requireNonNull(resetAction, "resetAction");
        delete = Objects.requireNonNull(deleteAction, "deleteAction");
        viewModel.apply(credential);
        applySessionStyle(credential.hasSession());
        root.setAccessibleText(credential.name() + (credential.hasSession()
                ? "，已保存会话" : "，未登录"));
    }

    private void applySessionStyle(boolean active) {
        sessionBadge.getStyleClass().removeAll("mcp-state-running", "mcp-state-stopped");
        sessionBadge.getStyleClass().add(active ? "mcp-state-running" : "mcp-state-stopped");
    }

    @FXML private void editRequested() { edit.accept(credentialId); }
    @FXML private void resetRequested() { reset.accept(credentialId); }
    @FXML private void deleteRequested() { delete.accept(credentialId); }

    @Override
    public void close() {
        nameLabel.textProperty().unbind();
        sessionBadge.textProperty().unbind();
        hostLabel.textProperty().unbind();
        accountLabel.textProperty().unbind();
        timestampsLabel.textProperty().unbind();
        timestampsLabel.visibleProperty().unbind();
        timestampsLabel.managedProperty().unbind();
        notesLabel.textProperty().unbind();
        notesLabel.visibleProperty().unbind();
        notesLabel.managedProperty().unbind();
        resetButton.disableProperty().unbind();
    }
}
