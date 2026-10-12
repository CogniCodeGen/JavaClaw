package com.javaclaw.ui.javafx.skill;

import com.javaclaw.application.skill.SkillManagementApplicationService;
import com.javaclaw.application.skill.SkillManagementApplicationService.BundleCommand;
import com.javaclaw.application.skill.SkillManagementApplicationService.BundleItem;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 技能包 Controller：维护包列表选择和不可变编辑命令。 */
public final class SkillBundlesController implements AutoCloseable {

    @FXML private StackPane root;
    @FXML private ListView<BundleItem> bundleList;
    @FXML private TextField nameField;
    @FXML private TextField descriptionField;
    @FXML private TextField skillsField;
    @FXML private TextArea instructionsArea;
    @FXML private CheckBox enabledCheck;
    @FXML private Label statusLabel;
    @FXML private Button saveButton;
    @FXML private Button deleteButton;
    @FXML private StackPane loadingOverlay;

    private final SkillManagementApplicationService useCases;
    private final UiAsyncAction<List<BundleItem>> action;
    private final SkillBundleCellFactory cells;
    private final AtomicBoolean closed = new AtomicBoolean();
    private String selectedName = "";

    public SkillBundlesController(
            SkillManagementApplicationService useCases,
            SkillBundleCellFactory cells,
            @Qualifier("workspaceTaskScope") TaskScope tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.cells = Objects.requireNonNull(cells, "cells");
        action = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        bundleList.setCellFactory(ignored -> cells.create());
        bundleList.getSelectionModel().selectedItemProperty().addListener(
                (ignored, previous, selected) -> { if (selected != null) apply(selected); });
        loadingOverlay.visibleProperty().bind(action.busyProperty());
        loadingOverlay.managedProperty().bind(loadingOverlay.visibleProperty());
        saveButton.disableProperty().bind(action.busyProperty());
        deleteButton.disableProperty().bind(action.busyProperty());
    }

    void refresh() {
        if (closed.get()) return;
        action.execute(TaskSpec.io("skill-bundles-load"),
                context -> useCases.bundles(), this::render, this::showFailure);
    }

    @FXML
    private void newRequested() {
        selectedName = "";
        bundleList.getSelectionModel().clearSelection();
        nameField.clear();
        descriptionField.clear();
        skillsField.clear();
        instructionsArea.clear();
        enabledCheck.setSelected(true);
        showStatus("", true);
    }

    @FXML
    private void saveRequested() {
        BundleCommand command = new BundleCommand(
                selectedName, nameField.getText(), descriptionField.getText(),
                commaSeparated(skillsField.getText()), instructionsArea.getText(),
                enabledCheck.isSelected());
        action.execute(TaskSpec.io("skill-bundle-save"),
                context -> useCases.saveBundle(command),
                bundles -> {
                    selectedName = nameField.getText() == null ? "" : nameField.getText().strip();
                    render(bundles);
                    select(selectedName);
                    showStatus("已保存", true);
                }, this::showFailure);
    }

    @FXML
    private void deleteRequested() {
        if (selectedName.isBlank()) return;
        String target = selectedName;
        action.execute(TaskSpec.io("skill-bundle-delete-" + target),
                context -> useCases.deleteBundle(target),
                bundles -> {
                    newRequested();
                    render(bundles);
                    showStatus("已删除", true);
                }, this::showFailure);
    }

    private void render(List<BundleItem> bundles) {
        bundleList.getItems().setAll(bundles);
        if (!selectedName.isBlank()) select(selectedName);
    }

    private void select(String name) {
        bundleList.getItems().stream().filter(bundle -> bundle.name().equals(name))
                .findFirst().ifPresent(bundleList.getSelectionModel()::select);
    }

    private void apply(BundleItem bundle) {
        selectedName = bundle.name();
        nameField.setText(bundle.name());
        descriptionField.setText(bundle.description());
        skillsField.setText(String.join(", ", bundle.skills()));
        instructionsArea.setText(bundle.extraInstructions());
        enabledCheck.setSelected(bundle.enabled());
        showStatus("", true);
    }

    private void showFailure(Throwable failure) {
        showStatus(failure.getMessage() == null ? "操作失败" : failure.getMessage(), false);
    }

    private void showStatus(String text, boolean success) {
        statusLabel.setText(text == null ? "" : text);
        statusLabel.getStyleClass().removeAll("status-success", "status-error");
        if (!statusLabel.getText().isBlank()) {
            statusLabel.getStyleClass().add(success ? "status-success" : "status-error");
        }
    }

    private static List<String> commaSeparated(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split("[,，]")).map(String::strip)
                .filter(item -> !item.isBlank()).toList();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) action.close();
    }
}
