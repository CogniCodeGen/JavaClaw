package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.UUID;

/** Read-only inference contribution details and managed service-plugin logs. */
public final class InferenceRuntimeSettingsController implements AutoCloseable {
    @FXML private VBox root;
    @FXML private ListView<InferenceSettingsChoice<String>> runtimeList;
    @FXML private Label runtimeDetailsLabel;
    @FXML private ComboBox<InferenceSettingsChoice<UUID>> logProfileCombo;
    @FXML private TextArea runtimeLogsArea;
    @FXML private Label statusLabel;

    private final InferenceManagementApplicationService useCases;
    private final InferenceSettingsUiActions ui;
    private InferenceManagementApplicationService.Snapshot snapshot;
    private Runnable reloadAll = () -> { };

    public InferenceRuntimeSettingsController(
            InferenceManagementApplicationService useCases, DialogService dialogs,
            ManagedTaskExecutor tasks, FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        ui = new InferenceSettingsUiActions(dialogs, tasks, fx);
    }

    @FXML
    private void initialize() {
        ui.attach(statusLabel);
        runtimeList.getSelectionModel().selectedItemProperty().addListener(
                (ignored, previous, selected) -> showRuntime(selected == null ? null : selected.value()));
    }

    void configure(Runnable reload) { reloadAll = Objects.requireNonNull(reload, "reload"); }

    void apply(InferenceManagementApplicationService.Snapshot value) {
        String selectedRuntime = selectedRuntime();
        UUID selectedProfile = logProfileCombo.getValue() == null ? null : logProfileCombo.getValue().value();
        snapshot = value;
        runtimeList.getItems().setAll(value.runtimes().stream().map(runtime ->
                new InferenceSettingsChoice<>(runtime.manifest().engineVersion() + " / adapter "
                        + runtime.manifest().adapterVersion() + (runtime.active() ? " · 当前" : ""),
                        runtime.manifest().runtimeId())).toList());
        selectRuntime(selectedRuntime);
        logProfileCombo.getItems().setAll(value.profiles().stream().map(profile ->
                new InferenceSettingsChoice<>(profile.name() + " · " + profile.state(), profile.id())).toList());
        select(logProfileCombo, selectedProfile);
        showRuntime(selectedRuntime());
    }

    @FXML
    private void refreshLogsRequested() {
        var selected = logProfileCombo.getValue();
        if (selected != null) runtimeLogsArea.setText(
                String.join("\n", useCases.recentLogs(selected.value(), 500)));
    }

    private void showRuntime(String runtimeId) {
        var runtime = snapshot == null || runtimeId == null ? null : snapshot.runtimes().stream()
                .filter(value -> value.manifest().runtimeId().equals(runtimeId)).findFirst().orElse(null);
        runtimeDetailsLabel.setText(runtime == null ? "" : runtime.manifest().runtimeId() + " · "
                + runtime.manifest().platform() + "/" + runtime.manifest().architecture() + " · 协议 "
                + runtime.manifest().protocol().major() + "." + runtime.manifest().protocol().minor()
                + " · " + String.join(", ", runtime.manifest().capabilities()));
    }

    private String selectedRuntime() {
        var selected = runtimeList.getSelectionModel().getSelectedItem();
        return selected == null ? null : selected.value();
    }

    private void selectRuntime(String id) {
        if (id == null && !runtimeList.getItems().isEmpty()) {
            runtimeList.getItems().stream().filter(item -> snapshot.runtimes().stream()
                    .anyMatch(runtime -> runtime.active()
                            && runtime.manifest().runtimeId().equals(item.value())))
                    .findFirst().ifPresentOrElse(runtimeList.getSelectionModel()::select,
                            () -> runtimeList.getSelectionModel().selectFirst());
            return;
        }
        runtimeList.getItems().stream().filter(item -> item.value().equals(id)).findFirst()
                .ifPresent(runtimeList.getSelectionModel()::select);
    }

    private static <T> void select(ComboBox<InferenceSettingsChoice<T>> box, T value) {
        if (value == null) { if (!box.getItems().isEmpty()) box.getSelectionModel().selectFirst(); return; }
        box.getItems().stream().filter(item -> item.value().equals(value)).findFirst()
                .ifPresent(box.getSelectionModel()::select);
    }

    void cancel() { ui.cancel(); }
    @Override public void close() { ui.close(); }
}
