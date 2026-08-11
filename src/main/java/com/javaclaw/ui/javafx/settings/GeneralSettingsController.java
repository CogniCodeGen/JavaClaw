package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.BehaviorSettingsApplicationService;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.GeneralSettings;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService.SaveResult;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import com.javaclaw.ui.javafx.theme.ThemeOption;
import com.javaclaw.ui.javafx.theme.ThemeSelectionService;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseEvent;
import javafx.util.StringConverter;

import java.util.Objects;
import java.util.function.Consumer;

/** 通用桌面行为设置 Controller；主题选择立即生效，持久化设置异步保存。 */
public final class GeneralSettingsController implements AutoCloseable {

    @FXML private ScrollPane root;
    @FXML private ComboBox<ThemeOption> themeCombo;
    @FXML private ToggleSwitch minimizeToTrayCheck;
    @FXML private ToggleSwitch taskRiskAutoApproveCheck;

    private final BehaviorSettingsApplicationService useCases;
    private final ThemeSelectionService themes;
    private final GeneralSettingsViewModel viewModel = new GeneralSettingsViewModel();
    private final UiAsyncAction<SaveResult> mutation;
    private final ChangeListener<String> currentThemeListener =
            (ignored, previous, current) -> selectCurrentTheme(current);
    private Consumer<SaveResult> onApplied = ignored -> { };

    public GeneralSettingsController(
            BehaviorSettingsApplicationService useCases,
            ThemeSelectionService themes,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.themes = Objects.requireNonNull(themes, "themes");
        mutation = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        themeCombo.setItems(viewModel.themes());
        themeCombo.setConverter(new StringConverter<>() {
            @Override public String toString(ThemeOption theme) {
                return theme == null ? "" : theme.name() + " — " + theme.subtitle();
            }
            @Override public ThemeOption fromString(String value) { return null; }
        });
        themeCombo.getProperties().put("jc-dirty-exempt", Boolean.TRUE);
        themeCombo.valueProperty().bindBidirectional(viewModel.selectedThemeProperty());
        minimizeToTrayCheck.selectedProperty().bindBidirectional(
                viewModel.minimizeToTrayOnCloseProperty());
        taskRiskAutoApproveCheck.selectedProperty().bindBidirectional(
                viewModel.taskRiskAutoApproveEnabledProperty());
        viewModel.busyProperty().bind(mutation.busyProperty());
        themes.currentThemeProperty().addListener(currentThemeListener);
        reload();
    }

    public void configure(Consumer<SaveResult> callback) {
        onApplied = Objects.requireNonNull(callback, "callback");
    }

    public GeneralSettingsViewModel viewModel() {
        return viewModel;
    }

    public void reload() {
        SettingsFieldSupport.loading(root, () -> viewModel.load(
                useCases.snapshot().general(), themes.availableThemes(), themes.currentTheme()));
    }

    public void save(Consumer<SaveResult> success, Consumer<Throwable> failure) {
        GeneralSettings command = new GeneralSettings(minimizeToTrayCheck.isSelected(),
                taskRiskAutoApproveCheck.isSelected());
        mutation.execute(TaskSpec.io("settings-general-save"),
                context -> useCases.saveGeneral(command), result -> {
                    reload();
                    onApplied.accept(result);
                    success.accept(result);
                }, thrown -> failed(thrown, failure));
    }

    @FXML
    private void themeChanged() {
        ThemeOption selected = themeCombo.getValue();
        if (!SettingsFieldSupport.isLoading(root) && selected != null
                && !selected.id().equals(themes.currentThemeId())) {
            themes.select(selected.id());
        }
    }

    @FXML private void minimizeToTrayRowClicked(MouseEvent event) {
        toggleRow(event, minimizeToTrayCheck);
    }

    @FXML private void taskRiskRowClicked(MouseEvent event) {
        toggleRow(event, taskRiskAutoApproveCheck);
    }

    private void selectCurrentTheme(String themeId) {
        ThemeOption selected = viewModel.themes().stream()
                .filter(theme -> theme.id().equals(themeId))
                .findFirst()
                .orElse(null);
        if (selected != null) {
            SettingsFieldSupport.loading(root,
                    () -> viewModel.selectedThemeProperty().set(selected));
        }
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
        themes.currentThemeProperty().removeListener(currentThemeListener);
        viewModel.busyProperty().unbind();
        themeCombo.valueProperty().unbindBidirectional(viewModel.selectedThemeProperty());
        minimizeToTrayCheck.selectedProperty().unbindBidirectional(
                viewModel.minimizeToTrayOnCloseProperty());
        taskRiskAutoApproveCheck.selectedProperty().unbindBidirectional(
                viewModel.taskRiskAutoApproveEnabledProperty());
    }
}
