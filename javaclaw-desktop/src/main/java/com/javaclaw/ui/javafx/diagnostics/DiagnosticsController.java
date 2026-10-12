package com.javaclaw.ui.javafx.diagnostics;

import com.javaclaw.application.diagnostics.DiagnosticsApplicationService;
import com.javaclaw.application.diagnostics.DiagnosticsApplicationService.ExportReceipt;
import com.javaclaw.application.diagnostics.DiagnosticsApplicationService.Query;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** FXML Controller：协调筛选、文件选择和异步用例，不执行阻塞 I/O。 */
public final class DiagnosticsController implements AutoCloseable {

    @FXML private ComboBox<DiagnosticsViewModel.RangeChoice> rangeBox;
    @FXML private TextField agentField;
    @FXML private ComboBox<String> eventBox;
    @FXML private TextField keywordField;
    @FXML private Button queryButton;
    @FXML private ListView<String> resultList;
    @FXML private Label summaryLabel;
    @FXML private Button exportButton;

    private final DiagnosticsApplicationService useCases;
    private final DiagnosticsExportTargetPicker targetPicker;
    private final UiAsyncAction<List<String>> queryAction;
    private final UiAsyncAction<ExportReceipt> exportAction;
    private final DiagnosticsViewModel viewModel = new DiagnosticsViewModel();
    private final AtomicBoolean closed = new AtomicBoolean();

    public DiagnosticsController(
            DiagnosticsApplicationService useCases,
            DiagnosticsExportTargetPicker targetPicker,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.targetPicker = Objects.requireNonNull(targetPicker, "targetPicker");
        queryAction = new UiAsyncAction<>(tasks, fx);
        exportAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        rangeBox.setItems(viewModel.ranges());
        eventBox.setItems(viewModel.eventTypes());
        resultList.setItems(viewModel.results());

        rangeBox.valueProperty().bindBidirectional(viewModel.selectedRangeProperty());
        eventBox.valueProperty().bindBidirectional(viewModel.selectedEventProperty());
        agentField.textProperty().bindBidirectional(viewModel.agentProperty());
        keywordField.textProperty().bindBidirectional(viewModel.keywordProperty());
        summaryLabel.textProperty().bind(viewModel.summaryProperty());
        viewModel.queryingProperty().bind(queryAction.busyProperty());
        viewModel.exportingProperty().bind(exportAction.busyProperty());
        queryButton.disableProperty().bind(viewModel.queryingProperty());
        exportButton.disableProperty().bind(Bindings.or(
                viewModel.exportingProperty(), viewModel.queryingProperty()));
    }

    @FXML
    private void queryRequested() {
        DiagnosticsViewModel.RangeChoice selected = viewModel.selectedRangeProperty().get();
        Query query = new Query(
                selected == null ? null : selected.value(),
                viewModel.agentProperty().get(),
                normalizedEvent(),
                viewModel.keywordProperty().get(),
                DiagnosticsViewModel.RESULT_LIMIT);
        queryAction.execute(
                TaskSpec.io("diagnostics-query"),
                context -> useCases.query(query),
                viewModel::showResults,
                viewModel::showQueryFailure);
    }

    @FXML
    private void exportRequested() {
        Scene scene = exportButton.getScene();
        targetPicker.choose(scene == null ? null : scene.getWindow())
                .ifPresent(this::exportTo);
    }

    private void exportTo(Path target) {
        exportAction.execute(
                TaskSpec.io("diagnostics-export"),
                context -> useCases.export(target),
                receipt -> viewModel.showExportSuccess(
                        receipt.target().toString(), receipt.bytes()),
                viewModel::showExportFailure);
    }

    private String normalizedEvent() {
        String selected = viewModel.selectedEventProperty().get();
        return DiagnosticsViewModel.ALL_EVENTS.equals(selected) ? null : selected;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        queryAction.close();
        exportAction.close();
        unbindViewState();
    }

    private void unbindViewState() {
        rangeBox.valueProperty().unbindBidirectional(viewModel.selectedRangeProperty());
        eventBox.valueProperty().unbindBidirectional(viewModel.selectedEventProperty());
        agentField.textProperty().unbindBidirectional(viewModel.agentProperty());
        keywordField.textProperty().unbindBidirectional(viewModel.keywordProperty());
        summaryLabel.textProperty().unbind();
        viewModel.queryingProperty().unbind();
        viewModel.exportingProperty().unbind();
        queryButton.disableProperty().unbind();
        exportButton.disableProperty().unbind();
    }

    boolean isClosed() { return closed.get(); }
}
