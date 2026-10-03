package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.ModelDiscoveryApplicationService;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService.DiscoveryRequest;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService.DiscoveryResult;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService.ModelOption;
import com.javaclaw.application.settings.ModelProviderCatalog;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.util.Duration;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 临时模型候选列表；选择值始终是模型 ID，编辑器也允许输入列表外的 ID。 */
final class ModelDiscoveryCombo implements AutoCloseable {
    private final ComboBox<String> combo;
    private final Button refreshButton;
    private final Label status;
    private final ModelDiscoveryApplicationService service;
    private final Supplier<DiscoveryRequest> request;
    private final UiAsyncAction<DiscoveryResult> lookup;
    private final PauseTransition debounce = new PauseTransition(Duration.millis(500));
    private boolean updating;
    private boolean searching;
    private final ChangeListener<String> searchListener = (ignored, oldValue, newValue) -> {
        if (!updating) {
            searching = true;
            filter(newValue);
        }
    };
    private List<ModelOption> options = List.of();
    private Map<String, String> names = Map.of();
    private boolean active = true;
    private boolean closed;

    ModelDiscoveryCombo(ComboBox<String> combo, Button refreshButton, Label status,
            ModelDiscoveryApplicationService service, Supplier<DiscoveryRequest> request,
            ManagedTaskExecutor tasks, FxDispatcher fx) {
        this.combo = Objects.requireNonNull(combo, "combo");
        this.refreshButton = Objects.requireNonNull(refreshButton, "refreshButton");
        this.status = Objects.requireNonNull(status, "status");
        this.service = Objects.requireNonNull(service, "service");
        this.request = Objects.requireNonNull(request, "request");
        lookup = new UiAsyncAction<>(tasks, fx);
        combo.setEditable(true);
        combo.setCellFactory(ignored -> new ListCell<>() {
            @Override protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                if (empty || id == null) {
                    setText(null);
                    return;
                }
                String name = names.get(id);
                setText(name == null || name.isBlank() || name.equals(id) ? id
                        : id + " · " + name);
            }
        });
        combo.getEditor().textProperty().addListener(searchListener);
        combo.setOnAction(ignored -> {
            if (!updating && combo.getSelectionModel().getSelectedItem() != null)
                searching = false;
        });
        combo.setOnShowing(ignored -> showAllOptions());
        refreshButton.setOnAction(ignored -> refreshNow());
        debounce.setOnFinished(ignored -> refreshNow());
        lookup.busyProperty().addListener((ignored, oldValue, busy) ->
                refreshButton.setDisable(!active || busy));
    }

    static Optional<ModelProviderCatalog.Provider> provider(
            ModelProviderCatalog catalog, String selected) {
        if (selected == null) return Optional.empty();
        return catalog.providers().stream()
                .filter(value -> value.id().equalsIgnoreCase(selected)
                        || value.displayName().equals(selected))
                .findFirst();
    }

    String text() {
        String value = combo.getEditor().getText();
        return value == null ? "" : value.strip();
    }

    void setText(String value) {
        String normalized = value == null ? "" : value;
        updating = true;
        try {
            combo.setValue(normalized);
            combo.getEditor().setText(normalized);
            searching = false;
        } finally {
            updating = false;
        }
        filter(normalized);
    }

    void setActive(boolean enabled) {
        boolean wasActive = active;
        active = enabled;
        combo.setDisable(!enabled);
        refreshButton.setDisable(!enabled || lookup.busyProperty().get());
        status.setVisible(enabled);
        status.setManaged(enabled);
        if (enabled) {
            if (!wasActive || options.isEmpty()) scheduleRefresh();
        } else {
            cancel();
            clearOptions();
            status.setText("");
        }
    }

    void scheduleRefresh() {
        if (closed || !active) return;
        lookup.cancel();
        clearOptions();
        debounce.playFromStart();
    }

    void refreshNow() {
        if (closed || !active) return;
        debounce.stop();
        lookup.cancel();
        DiscoveryRequest input = request.get();
        if (input == null || input.baseUrl() == null || input.baseUrl().isBlank()) {
            clearOptions();
            status.setText("输入 API 地址后可获取模型列表；也可直接输入模型名称。");
            return;
        }
        status.setText("正在获取模型列表…");
        lookup.execute(TaskSpec.io("settings-model-discovery"),
                ignored -> service.discover(input), this::showResult,
                ignored -> {
                    clearOptions();
                    status.setText("获取模型列表失败，请检查地址、网络或密钥；仍可手动输入。");
                });
    }

    private void showResult(DiscoveryResult result) {
        if (!result.succeeded()) {
            clearOptions();
            status.setText(result.message() == null || result.message().isBlank()
                    ? "模型列表不可用；仍可手动输入。"
                    : result.message() + "；仍可手动输入。");
            return;
        }
        options = result.models().stream().filter(option -> option.id() != null
                        && !option.id().isBlank())
                .distinct().toList();
        names = options.stream().collect(Collectors.toMap(ModelOption::id,
                option -> option.displayName() == null ? "" : option.displayName(),
                (first, ignored) -> first));
        showAllOptions();
        status.setText(result.message() == null || result.message().isBlank()
                ? "已获取 " + options.size() + " 个模型；也可手动输入。"
                : result.message());
    }

    private void clearOptions() {
        options = List.of();
        names = Map.of();
        filter(text());
    }

    private void filter(String query) {
        if (updating || closed) return;
        String value = query == null ? "" : query;
        String needle = value.strip().toLowerCase(Locale.ROOT);
        List<String> matches = options.stream().filter(option -> needle.isEmpty()
                        || option.id().toLowerCase(Locale.ROOT).contains(needle)
                        || names.getOrDefault(option.id(), "").toLowerCase(Locale.ROOT).contains(needle))
                .map(ModelOption::id).toList();
        updateItems(matches, value);
    }

    private void showAllOptions() {
        if (closed) return;
        if (searching) filter(text());
        else updateItems(options.stream().map(ModelOption::id).toList(), text());
    }

    private void updateItems(List<String> matches, String value) {
        if (combo.getItems().equals(matches)) return;
        int caret = combo.getEditor().getCaretPosition();
        updating = true;
        Object previousSilent = combo.getProperties().put(SettingsDirtyTracker.DIRTY_SILENT, true);
        try {
            combo.getItems().setAll(matches);
            combo.setValue(value);
            combo.getEditor().setText(value);
            combo.getEditor().positionCaret(Math.min(caret, value.length()));
        } finally {
            if (previousSilent == null) combo.getProperties().remove(SettingsDirtyTracker.DIRTY_SILENT);
            else combo.getProperties().put(SettingsDirtyTracker.DIRTY_SILENT, previousSilent);
            updating = false;
        }
    }

    void cancel() {
        debounce.stop();
        lookup.cancel();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        debounce.stop();
        combo.getEditor().textProperty().removeListener(searchListener);
        combo.setOnAction(null);
        combo.setOnShowing(null);
        refreshButton.setOnAction(null);
        lookup.close();
    }
}
