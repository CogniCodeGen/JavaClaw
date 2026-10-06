package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.application.knowledge.KnowledgeApplicationService;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Health;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.HealthStatus;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.ReindexResult;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Settings;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Snapshot;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.beans.value.ChangeListener;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.input.InputEvent;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Coordinates chunk settings, index rebuild and destructive knowledge maintenance. */
public final class KnowledgeSettingsController implements AutoCloseable {
    private static final List<String> HEALTH_STYLES = List.of(
            "kc-health-healthy", "kc-health-checking", "kc-health-degraded",
            "kc-health-unavailable", "kc-health-unconfigured");

    @FXML private VBox root;
    @FXML private Region healthDot;
    @FXML private Label healthLabel;
    @FXML private Label modelLabel;
    @FXML private Label providerLabel;
    @FXML private Label dimensionsLabel;
    @FXML private Label chunkValueLabel;
    @FXML private Slider chunkSlider;
    @FXML private Label overlapValueLabel;
    @FXML private Label overlapMaxLabel;
    @FXML private Slider overlapSlider;
    @FXML private Button rebuildButton;
    @FXML private Button clearButton;
    @FXML private StackPane loadingOverlay;

    private final KnowledgeApplicationService useCases;
    private final DialogService dialogs;
    private final KnowledgeSettingsViewModel viewModel = new KnowledgeSettingsViewModel();
    private final UiAsyncAction<Settings> saveAction;
    private final UiAsyncAction<ReindexResult> rebuildAction;
    private final UiAsyncAction<Snapshot> clearAction;
    private Consumer<Snapshot> snapshotConsumer = ignored -> { };
    private Runnable settingsChanged = () -> { };
    private Consumer<String> notifier = ignored -> { };
    private Runnable openModelSettings = () -> { };
    private boolean applying;
    private boolean updatingValues;
    private boolean savingChunkSettings;
    private final EventHandler<InputEvent> chunkInputGuard = this::guardChunkSettingsInput;
    private final ChangeListener<Boolean> chunkSaveBusyListener = (ignored, previous, busy) -> {
        if (!busy) savingChunkSettings = false;
    };

    public KnowledgeSettingsController(
            KnowledgeApplicationService useCases,
            DialogService dialogs,
            @Qualifier("workspaceTaskScope") TaskScope tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        saveAction = new UiAsyncAction<>(tasks, fx);
        rebuildAction = new UiAsyncAction<>(tasks, fx);
        clearAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        loadingOverlay.visibleProperty().bind(saveAction.busyProperty()
                .or(rebuildAction.busyProperty()).or(clearAction.busyProperty()));
        loadingOverlay.managedProperty().bind(loadingOverlay.visibleProperty());
        rebuildButton.disableProperty().bind(rebuildAction.busyProperty());
        clearButton.disableProperty().bind(clearAction.busyProperty());
        chunkSlider.disableProperty().bind(rebuildAction.busyProperty().or(clearAction.busyProperty()));
        overlapSlider.disableProperty().bind(rebuildAction.busyProperty().or(clearAction.busyProperty()));
        chunkSlider.addEventFilter(InputEvent.ANY, chunkInputGuard);
        overlapSlider.addEventFilter(InputEvent.ANY, chunkInputGuard);
        saveAction.busyProperty().addListener(chunkSaveBusyListener);
        chunkSlider.valueProperty().addListener((ignored, previous, value) -> updateValues());
        overlapSlider.valueProperty().addListener((ignored, previous, value) -> updateValues());
    }

    void configure(
            Consumer<Snapshot> snapshots,
            Runnable changed,
            Consumer<String> messages) {
        snapshotConsumer = snapshots == null ? ignored -> { } : snapshots;
        settingsChanged = changed == null ? () -> { } : changed;
        notifier = messages == null ? ignored -> { } : messages;
    }

    void setOpenModelSettings(Runnable action) {
        openModelSettings = action == null ? () -> { } : action;
    }

    void apply(Snapshot snapshot) {
        viewModel.apply(snapshot.settings(), snapshot.health());
        applySettings(snapshot.settings());
        updateHealth(snapshot.health());
    }

    void updateHealth(Health health) {
        viewModel.updateHealth(health);
        String style;
        String label;
        switch (health.status()) {
            case HEALTHY -> { style = "healthy"; label = "已连接"; }
            case CHECKING -> { style = "checking"; label = "检查中"; }
            case DEGRADED -> { style = "degraded"; label = "已降级"; }
            case UNAVAILABLE -> { style = "unavailable"; label = "不可用"; }
            case UNCONFIGURED -> { style = "unconfigured"; label = "未配置"; }
            default -> throw new IllegalStateException("未知嵌入健康状态");
        }
        healthLabel.setText(label);
        applyHealthStyle(healthDot, style);
        applyHealthStyle(healthLabel, style);
    }

    void openModelSettings() { openModelSettings.run(); }

    @FXML private void modelSettingsRequested() { openModelSettings(); }
    @FXML private void chunkSettingsReleased(Event event) {
        if (!(event instanceof KeyEvent key) || changesChunkSettings(key)) {
            saveChunkSettings();
        }
    }

    private void guardChunkSettingsInput(InputEvent event) {
        if (savingChunkSettings
                && (!(event instanceof KeyEvent key) || changesChunkSettings(key))) {
            event.consume();
        }
    }

    private static boolean changesChunkSettings(KeyEvent event) {
        return switch (event.getCode()) {
            case HOME, END, LEFT, RIGHT, UP, DOWN, PAGE_UP, PAGE_DOWN -> true;
            default -> false;
        };
    }
    @FXML private void rebuildRequested() { rebuildIndex(); }
    @FXML private void clearRequested() { clearKnowledge(); }

    private void applySettings(Settings settings) {
        applying = true;
        try {
            modelLabel.setText(settings.model().isBlank() ? "未配置" : settings.model());
            providerLabel.setText(settings.provider() + " · "
                    + (settings.baseUrl().isBlank() ? "—" : settings.baseUrl()));
            dimensionsLabel.setText(Integer.toString(settings.dimensions()));
            chunkSlider.setValue(settings.chunkSize());
            overlapSlider.setValue(settings.chunkOverlap());
            updateValues();
        } finally {
            applying = false;
        }
    }

    private void updateValues() {
        if (updatingValues) return;
        updatingValues = true;
        try {
            int chunkSize = (int) Math.round(chunkSlider.getValue());
            int maximumOverlap = Math.min(256, Math.max(0, chunkSize / 2));
            overlapSlider.setMax(maximumOverlap);
            overlapSlider.setValue(Math.max(0,
                    Math.min(maximumOverlap, overlapSlider.getValue())));
            int overlap = (int) Math.round(overlapSlider.getValue());
            int percentage = chunkSize == 0 ? 0 : Math.round(overlap * 100f / chunkSize);
            chunkValueLabel.setText(chunkSize + " 字符");
            overlapValueLabel.setText(overlap + " 字符 · " + percentage + "%");
            overlapMaxLabel.setText(Integer.toString(maximumOverlap));
        } finally {
            updatingValues = false;
        }
    }

    private void saveChunkSettings() {
        if (applying || updatingValues || savingChunkSettings || saveAction.busyProperty().get()
                || viewModel.settings() == null) return;
        updateValues();
        int chunkSize = (int) Math.round(chunkSlider.getValue());
        int overlap = (int) Math.round(overlapSlider.getValue());
        Settings current = viewModel.settings();
        if (chunkSize == current.chunkSize() && overlap == current.chunkOverlap()) return;
        savingChunkSettings = true;
        try {
            saveAction.execute(TaskSpec.io("knowledge-save-chunk-settings"),
                    context -> useCases.saveChunkSettings(chunkSize, overlap),
                    settings -> {
                        try {
                            viewModel.updateSettings(settings);
                            applySettings(settings);
                            settingsChanged.run();
                            notifier.accept("分块参数已保存");
                        } finally {
                            savingChunkSettings = false;
                        }
                    }, failure -> {
                        try {
                            applySettings(current);
                            notifier.accept("保存分块参数失败：" + errorMessage(failure));
                        } finally {
                            savingChunkSettings = false;
                        }
                    });
        } catch (RuntimeException failure) {
            savingChunkSettings = false;
            throw failure;
        }
    }

    private void rebuildIndex() {
        rebuildAction.execute(TaskSpec.io("knowledge-rebuild-index"),
                context -> useCases.rebuildIndex(), result -> {
                    snapshotConsumer.accept(result.snapshot());
                    notifier.accept("索引重建完成，已重新嵌入 "
                            + result.rebuiltChunks() + " 个片段");
                }, failure -> notifier.accept("重建索引失败：" + errorMessage(failure)));
    }

    private void clearKnowledge() {
        clearAction.execute(TaskSpec.io("knowledge-clear"), context -> {
            boolean allowed = dialogs.confirm(new ConfirmRequest(
                    "knowledge_clear", "不可逆",
                    "确定要清空全局和当前工作区的全部知识文档与向量片段吗？",
                    ConfirmKind.CONFIRM, 0, "", false)).isAllow();
            return allowed ? useCases.clear() : null;
        }, snapshot -> {
            if (snapshot == null) return;
            snapshotConsumer.accept(snapshot);
            notifier.accept("知识库已清空");
        }, failure -> notifier.accept("清空知识库失败：" + errorMessage(failure)));
    }

    private static void applyHealthStyle(Node node, String style) {
        node.getStyleClass().removeAll(HEALTH_STYLES);
        node.getStyleClass().add("kc-health-" + style);
    }

    private static String errorMessage(Throwable failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    @Override
    public void close() {
        savingChunkSettings = false;
        chunkSlider.removeEventFilter(InputEvent.ANY, chunkInputGuard);
        overlapSlider.removeEventFilter(InputEvent.ANY, chunkInputGuard);
        saveAction.busyProperty().removeListener(chunkSaveBusyListener);
        saveAction.close();
        rebuildAction.close();
        clearAction.close();
        loadingOverlay.visibleProperty().unbind();
        loadingOverlay.managedProperty().unbind();
        rebuildButton.disableProperty().unbind();
        clearButton.disableProperty().unbind();
        chunkSlider.disableProperty().unbind();
        overlapSlider.disableProperty().unbind();
        snapshotConsumer = ignored -> { };
        settingsChanged = () -> { };
        notifier = ignored -> { };
        openModelSettings = () -> { };
    }
}
