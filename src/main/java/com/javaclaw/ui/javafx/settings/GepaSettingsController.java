package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.BehaviorSettingsApplicationService;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.GepaSettings;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.SaveResult;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;

import java.util.Objects;
import java.util.function.Consumer;

/** GEPA 设置 Controller；只协调表单状态与异步应用用例。 */
public final class GepaSettingsController implements AutoCloseable {

    @FXML private ScrollPane root;
    @FXML private ToggleSwitch goalDecompositionCheck;
    @FXML private TextField evaluationIntervalField;
    @FXML private TextField evaluationThresholdField;
    @FXML private ToggleSwitch adaptivePlanningCheck;
    @FXML private TextField feedbackMaxRoundsField;

    private final BehaviorSettingsApplicationService useCases;
    private final GepaSettingsViewModel viewModel = new GepaSettingsViewModel();
    private final UiAsyncAction<SaveResult> mutation;
    private Consumer<SaveResult> onApplied = ignored -> { };

    public GepaSettingsController(
            BehaviorSettingsApplicationService useCases,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        mutation = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        goalDecompositionCheck.selectedProperty().bindBidirectional(
                viewModel.goalDecompositionEnabledProperty());
        evaluationIntervalField.textProperty().bindBidirectional(
                viewModel.evaluationIntervalProperty());
        evaluationThresholdField.textProperty().bindBidirectional(
                viewModel.evaluationThresholdProperty());
        adaptivePlanningCheck.selectedProperty().bindBidirectional(
                viewModel.adaptivePlanningEnabledProperty());
        feedbackMaxRoundsField.textProperty().bindBidirectional(
                viewModel.feedbackMaxRoundsProperty());
        feedbackMaxRoundsField.disableProperty().bind(
                viewModel.adaptivePlanningEnabledProperty().not());
        viewModel.busyProperty().bind(mutation.busyProperty());
        SettingsFieldSupport.validateInteger(evaluationIntervalField, 1, 20);
        SettingsFieldSupport.validateDecimal(evaluationThresholdField, 1, 5);
        SettingsFieldSupport.validateInteger(feedbackMaxRoundsField, 0, 10);
        reload();
    }

    public void configure(Consumer<SaveResult> callback) {
        onApplied = Objects.requireNonNull(callback, "callback");
    }

    public GepaSettingsViewModel viewModel() {
        return viewModel;
    }

    public void reload() {
        SettingsFieldSupport.loading(root,
                () -> viewModel.load(useCases.snapshot().gepa()));
    }

    public void save(Consumer<SaveResult> success, Consumer<Throwable> failure) {
        GepaSettings command = form();
        mutation.execute(TaskSpec.io("settings-gepa-save"),
                context -> useCases.saveGepa(command), result -> {
                    reload();
                    onApplied.accept(result);
                    success.accept(result);
                }, thrown -> failed(thrown, failure));
    }

    @FXML private void goalRowClicked(MouseEvent event) {
        toggleRow(event, goalDecompositionCheck);
    }

    @FXML private void adaptiveRowClicked(MouseEvent event) {
        toggleRow(event, adaptivePlanningCheck);
    }

    private GepaSettings form() {
        return new GepaSettings(goalDecompositionCheck.isSelected(),
                SettingsFieldSupport.integer(evaluationIntervalField, 1, 20, "评估间隔"),
                SettingsFieldSupport.decimal(evaluationThresholdField, 1, 5, "评估通过阈值"),
                adaptivePlanningCheck.isSelected(),
                SettingsFieldSupport.integer(feedbackMaxRoundsField, 0, 10, "最大调整轮次"));
    }

    private void failed(Throwable thrown, Consumer<Throwable> failure) {
        viewModel.errorProperty().set(SettingsFieldSupport.failureMessage(thrown));
        failure.accept(thrown);
    }

    private static void toggleRow(MouseEvent event, ToggleSwitch toggle) {
        if (toggle.isDisabled() || originatesFrom(event, toggle)) return;
        toggle.setSelected(!toggle.isSelected());
    }

    private static boolean originatesFrom(MouseEvent event, Node expected) {
        Node current = event.getPickResult().getIntersectedNode();
        while (current != null) {
            if (current == expected) return true;
            current = current.getParent();
        }
        return false;
    }

    @Override
    public void close() {
        mutation.close();
        viewModel.busyProperty().unbind();
        feedbackMaxRoundsField.disableProperty().unbind();
        goalDecompositionCheck.selectedProperty().unbindBidirectional(
                viewModel.goalDecompositionEnabledProperty());
        evaluationIntervalField.textProperty().unbindBidirectional(
                viewModel.evaluationIntervalProperty());
        evaluationThresholdField.textProperty().unbindBidirectional(
                viewModel.evaluationThresholdProperty());
        adaptivePlanningCheck.selectedProperty().unbindBidirectional(
                viewModel.adaptivePlanningEnabledProperty());
        feedbackMaxRoundsField.textProperty().unbindBidirectional(
                viewModel.feedbackMaxRoundsProperty());
    }
}
