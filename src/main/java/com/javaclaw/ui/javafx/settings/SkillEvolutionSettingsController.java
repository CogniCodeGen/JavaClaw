package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.BehaviorSettingsApplicationService;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.SaveResult;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.SkillEvolutionSettings;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.MouseEvent;

import java.util.Objects;
import java.util.function.Consumer;

/** 技能进化设置 Controller。 */
public final class SkillEvolutionSettingsController implements AutoCloseable {

    @FXML private ScrollPane root;
    @FXML private ToggleGroup modeGroup;
    @FXML private ToggleButton offModeButton;
    @FXML private ToggleButton suggestModeButton;
    @FXML private ToggleButton autoModeButton;
    @FXML private Label modeHintLabel;
    @FXML private TextField minimumToolCallsField;
    @FXML private TextField successThresholdField;
    @FXML private ToggleSwitch nudgeEnabledCheck;
    @FXML private ToggleSwitch bundlesEnabledCheck;

    private final BehaviorSettingsApplicationService useCases;
    private final SkillEvolutionSettingsViewModel viewModel =
            new SkillEvolutionSettingsViewModel();
    private final UiAsyncAction<SaveResult> mutation;
    private final UiAsyncAction<SkillEvolutionSettings> refresh;
    private Consumer<SaveResult> onApplied = ignored -> { };

    public SkillEvolutionSettingsController(
            BehaviorSettingsApplicationService useCases,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        mutation = new UiAsyncAction<>(tasks, fx);
        refresh = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        minimumToolCallsField.textProperty().bindBidirectional(
                viewModel.minimumToolCallsProperty());
        successThresholdField.textProperty().bindBidirectional(
                viewModel.successThresholdProperty());
        nudgeEnabledCheck.selectedProperty().bindBidirectional(viewModel.nudgeEnabledProperty());
        bundlesEnabledCheck.selectedProperty().bindBidirectional(viewModel.bundlesEnabledProperty());
        modeHintLabel.textProperty().bind(viewModel.modeHintProperty());
        viewModel.busyProperty().bind(mutation.busyProperty().or(refresh.busyProperty()));
        SettingsFieldSupport.validateInteger(minimumToolCallsField, 1, 50);
        SettingsFieldSupport.validateDecimal(successThresholdField, 0, 1);
    }

    public void configure(Consumer<SaveResult> callback) {
        onApplied = Objects.requireNonNull(callback, "callback");
    }

    public SkillEvolutionSettingsViewModel viewModel() {
        return viewModel;
    }

    public void reload() {
        refresh.execute(TaskSpec.io("settings-skill-evolution-load"),
                context -> useCases.snapshot().skillEvolution(), value ->
                SettingsFieldSupport.loading(root, () -> {
            viewModel.load(value);
            selectMode(viewModel.modeProperty().get());
        }), failure -> viewModel.errorProperty().set(
                SettingsFieldSupport.failureMessage(failure)));
    }

    public void save(Consumer<SaveResult> success, Consumer<Throwable> failure) {
        SkillEvolutionSettings command = form();
        mutation.execute(TaskSpec.io("settings-skill-evolution-save"),
                context -> useCases.saveSkillEvolution(command), result -> {
                    reload();
                    onApplied.accept(result);
                    success.accept(result);
                }, thrown -> failed(thrown, failure));
    }

    @FXML
    private void modeChanged() {
        Toggle selected = modeGroup.getSelectedToggle();
        if (selected == null) {
            selectMode(viewModel.modeProperty().get());
            return;
        }
        viewModel.setMode(String.valueOf(selected.getUserData()));
    }

    @FXML private void nudgeRowClicked(MouseEvent event) {
        toggleRow(event, nudgeEnabledCheck);
    }

    @FXML private void bundlesRowClicked(MouseEvent event) {
        toggleRow(event, bundlesEnabledCheck);
    }

    private SkillEvolutionSettings form() {
        return new SkillEvolutionSettings(viewModel.modeProperty().get(),
                SettingsFieldSupport.integer(minimumToolCallsField, 1, 50,
                        "最小工具调用数"),
                SettingsFieldSupport.decimal(successThresholdField, 0, 1,
                        "成功率门槛"),
                nudgeEnabledCheck.isSelected(), bundlesEnabledCheck.isSelected());
    }

    private void selectMode(String mode) {
        ToggleButton selected = switch (mode) {
            case "off" -> offModeButton;
            case "auto" -> autoModeButton;
            default -> suggestModeButton;
        };
        modeGroup.selectToggle(selected);
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

    void deactivate() { refresh.cancel(); }

    @Override
    public void close() {
        mutation.close();
        refresh.close();
        viewModel.busyProperty().unbind();
        minimumToolCallsField.textProperty().unbindBidirectional(
                viewModel.minimumToolCallsProperty());
        successThresholdField.textProperty().unbindBidirectional(
                viewModel.successThresholdProperty());
        nudgeEnabledCheck.selectedProperty().unbindBidirectional(viewModel.nudgeEnabledProperty());
        bundlesEnabledCheck.selectedProperty().unbindBidirectional(
                viewModel.bundlesEnabledProperty());
        modeHintLabel.textProperty().unbind();
    }
}
