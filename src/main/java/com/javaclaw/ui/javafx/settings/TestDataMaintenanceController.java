package com.javaclaw.ui.javafx.settings;

import com.javaclaw.api.interaction.ConfirmDecision;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.application.settings.TestDataMaintenanceApplicationService;
import com.javaclaw.application.settings.TestDataMaintenanceApplicationService.Candidate;
import com.javaclaw.application.settings.TestDataMaintenanceApplicationService.CleanupResult;
import com.javaclaw.application.settings.TestDataMaintenanceApplicationService.ScanResult;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;

import java.util.List;
import java.util.Objects;

/** 历史测试数据维护 Controller；扫描、确认和删除全部经托管 I/O 任务执行。 */
public final class TestDataMaintenanceController implements AutoCloseable {

    @FXML private ScrollPane root;
    @FXML private ListView<Candidate> candidatesView;
    @FXML private Label placeholderLabel;
    @FXML private Label statusLabel;
    @FXML private Button scanButton;
    @FXML private Button cleanupButton;

    private final TestDataMaintenanceApplicationService useCases;
    private final DialogService dialogs;
    private final TestDataCandidateCellFactory cells;
    private final TestDataMaintenanceViewModel viewModel = new TestDataMaintenanceViewModel();
    private final UiAsyncAction<ScanResult> scanAction;
    private final UiAsyncAction<CleanupOutcome> cleanupAction;

    public TestDataMaintenanceController(
            TestDataMaintenanceApplicationService useCases,
            DialogService dialogs,
            TestDataCandidateCellFactory cells,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.cells = Objects.requireNonNull(cells, "cells");
        scanAction = new UiAsyncAction<>(tasks, fx);
        cleanupAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        candidatesView.setItems(viewModel.candidates());
        candidatesView.setCellFactory(ignored -> cells.create());
        placeholderLabel.textProperty().bind(viewModel.placeholderProperty());
        statusLabel.textProperty().bind(viewModel.statusProperty());
        viewModel.busyProperty().bind(
                scanAction.busyProperty().or(cleanupAction.busyProperty()));
        scanButton.disableProperty().bind(viewModel.busyProperty());
        cleanupButton.disableProperty().bind(viewModel.busyProperty().or(
                Bindings.isEmpty(viewModel.candidates())));
    }

    public TestDataMaintenanceViewModel viewModel() {
        return viewModel;
    }

    @FXML
    private void scanRequested() {
        viewModel.scanning();
        scanAction.execute(TaskSpec.io("legacy-test-data-scan"),
                context -> useCases.scan(), viewModel::apply, this::failed);
    }

    @FXML
    private void cleanupRequested() {
        List<Candidate> candidates = List.copyOf(viewModel.candidates());
        if (candidates.isEmpty()) return;
        viewModel.awaitingConfirmation();
        cleanupAction.execute(TaskSpec.io("legacy-test-data-cleanup"), context -> {
            ConfirmDecision decision = dialogs.confirm(new ConfirmRequest(
                    "清理历史测试数据", "不可逆",
                    "将永久删除扫描结果中的 " + candidates.size()
                            + " 个带 JavaClaw 数据库标记的 junit-* 目录。\n"
                            + "该操作无法撤销，是否继续？",
                    ConfirmKind.CONFIRM, 60, "", false));
            return decision.isAllow()
                    ? new CleanupOutcome(true, useCases.cleanup(candidates))
                    : new CleanupOutcome(false, null);
        }, this::cleaned, this::failed);
    }

    private void cleaned(CleanupOutcome outcome) {
        if (outcome.confirmed()) {
            viewModel.cleaned(outcome.result());
        } else {
            viewModel.cleanupCancelled();
        }
    }

    private void failed(Throwable failure) {
        viewModel.failed(SettingsFieldSupport.failureMessage(failure));
    }

    @Override
    public void close() {
        scanAction.close();
        cleanupAction.close();
        cleanupButton.disableProperty().unbind();
        scanButton.disableProperty().unbind();
        viewModel.busyProperty().unbind();
        placeholderLabel.textProperty().unbind();
        statusLabel.textProperty().unbind();
        candidatesView.setItems(null);
        candidatesView.setCellFactory(null);
    }

    private record CleanupOutcome(boolean confirmed, CleanupResult result) { }
}
