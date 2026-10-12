package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.knowledge.KnowledgeApplicationService;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Health;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.HealthStatus;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Scope;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Snapshot;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.control.WindowToast;
import com.javaclaw.ui.javafx.control.WindowToastFactory;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates knowledge-center navigation, snapshots, health events and window lifecycle. */
public final class KnowledgeCenterController implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeCenterController.class);
    private static final List<String> HEALTH_STYLES = List.of(
            "kc-health-healthy", "kc-health-checking", "kc-health-degraded",
            "kc-health-unavailable", "kc-health-unconfigured");

    @FXML private StackPane root;
    @FXML private HBox ragBadge;
    @FXML private Label ragBadgeLabel;
    @FXML private Region embeddingDot;
    @FXML private Label embeddingModelLabel;
    @FXML private Label embeddingDimensionsLabel;
    @FXML private Label workspaceNameLabel;
    @FXML private Label allCountLabel;
    @FXML private Label workspaceCountLabel;
    @FXML private Label globalCountLabel;
    @FXML private Label totalDocumentsLabel;
    @FXML private Label totalChunksLabel;
    @FXML private Label enabledDocumentsLabel;
    @FXML private HBox allNavigation;
    @FXML private HBox workspaceNavigation;
    @FXML private HBox globalNavigation;
    @FXML private HBox searchNavigation;
    @FXML private HBox settingsNavigation;
    @FXML private VBox documentsPanel;
    @FXML private KnowledgeDocumentsController documentsPanelController;
    @FXML private VBox searchPanel;
    @FXML private KnowledgeSearchController searchPanelController;
    @FXML private VBox settingsPanel;
    @FXML private KnowledgeSettingsController settingsPanelController;
    @FXML private Label feedbackLabel;
    @FXML private StackPane loadingOverlay;
    @FXML private StackPane toastHost;

    private final KnowledgeApplicationService useCases;
    private final WindowToastFactory windowToasts;
    private final UserInteractionPort interaction;
    private final FxDispatcher fx;
    private final KnowledgeCenterViewModel viewModel = new KnowledgeCenterViewModel();
    private final UiAsyncAction<Snapshot> loadAction;
    private final AtomicBoolean closed = new AtomicBoolean();
    private WindowToast windowToast;
    private AutoCloseable healthSubscription;
    private Runnable closeAction = () -> { };
    private Runnable onConfigChanged = () -> { };
    private boolean chunkSettingsDirty;
    private boolean prepared;

    public KnowledgeCenterController(
            KnowledgeApplicationService useCases,
            WindowToastFactory windowToasts,
            UserInteractionPort interaction,
            @Qualifier("workspaceTaskScope") TaskScope tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.windowToasts = Objects.requireNonNull(windowToasts, "windowToasts");
        this.interaction = Objects.requireNonNull(interaction, "interaction");
        this.fx = Objects.requireNonNull(fx, "fx");
        loadAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        feedbackLabel.textProperty().bind(viewModel.feedbackProperty());
        loadingOverlay.visibleProperty().bind(loadAction.busyProperty());
        loadingOverlay.managedProperty().bind(loadingOverlay.visibleProperty());
        documentsPanelController.configure(this::acceptSnapshot, this::notifyUser);
        searchPanelController.configure(this::notifyUser);
        settingsPanelController.configure(
                this::acceptSnapshot, this::markChunkSettingsDirty, this::notifyUser);
        showDocuments(Scope.ALL);
    }

    void configure(
            Stage stage,
            Runnable close,
            Runnable configChanged,
            Runnable openModelSettings) {
        closeAction = close == null ? () -> { } : close;
        onConfigChanged = configChanged == null ? () -> { } : configChanged;
        settingsPanelController.setOpenModelSettings(openModelSettings);
        windowToast = windowToasts.create();
        toastHost.getChildren().setAll(windowToast.node());
        windowToast.bindToPort(stage, interaction);
    }

    void prepare() {
        if (closed.get()) return;
        if (!prepared) {
            prepared = true;
            healthSubscription = useCases.observeHealth(this::healthChanged);
        }
        requestSnapshot();
    }

    @FXML private void closeRequested() { closeAction.run(); }
    @FXML private void allRequested() { showDocuments(Scope.ALL); }
    @FXML private void workspaceRequested() { showDocuments(Scope.WORKSPACE); }
    @FXML private void globalRequested() { showDocuments(Scope.GLOBAL); }
    @FXML private void searchRequested() { showPage(KnowledgeCenterViewModel.Page.SEARCH); }
    @FXML private void settingsRequested() { showPage(KnowledgeCenterViewModel.Page.SETTINGS); }
    @FXML private void embeddingSettingsRequested() { settingsPanelController.openModelSettings(); }

    private void showDocuments(Scope scope) {
        documentsPanelController.setScope(scope);
        showPage(KnowledgeCenterViewModel.Page.DOCUMENTS);
        activeNavigation(switch (scope) {
            case WORKSPACE -> workspaceNavigation;
            case GLOBAL -> globalNavigation;
            case ALL -> allNavigation;
        });
    }

    private void showPage(KnowledgeCenterViewModel.Page page) {
        viewModel.show(page);
        visible(documentsPanel, page == KnowledgeCenterViewModel.Page.DOCUMENTS);
        visible(searchPanel, page == KnowledgeCenterViewModel.Page.SEARCH);
        visible(settingsPanel, page == KnowledgeCenterViewModel.Page.SETTINGS);
        if (page == KnowledgeCenterViewModel.Page.SEARCH) activeNavigation(searchNavigation);
        if (page == KnowledgeCenterViewModel.Page.SETTINGS) activeNavigation(settingsNavigation);
    }

    private void activeNavigation(HBox active) {
        for (HBox item : List.of(allNavigation, workspaceNavigation, globalNavigation,
                searchNavigation, settingsNavigation)) {
            item.getStyleClass().remove("kc-rail-item-active");
        }
        active.getStyleClass().add("kc-rail-item-active");
    }

    private void requestSnapshot() {
        loadAction.execute(TaskSpec.io("knowledge-snapshot"),
                context -> useCases.snapshot(), this::acceptSnapshot,
                failure -> notifyUser("加载知识库失败：" + message(failure)));
    }

    private void acceptSnapshot(Snapshot snapshot) {
        viewModel.apply(snapshot);
        workspaceNameLabel.setText(snapshot.workspaceName());
        allCountLabel.setText(Long.toString(snapshot.count(Scope.ALL)));
        workspaceCountLabel.setText(Long.toString(snapshot.count(Scope.WORKSPACE)));
        globalCountLabel.setText(Long.toString(snapshot.count(Scope.GLOBAL)));
        totalDocumentsLabel.setText(Integer.toString(snapshot.documents().size()));
        totalChunksLabel.setText(Integer.toString(snapshot.totalChunks()));
        enabledDocumentsLabel.setText(Long.toString(snapshot.enabledCount()));
        embeddingModelLabel.setText(blank(snapshot.settings().model(), "未配置"));
        embeddingDimensionsLabel.setText(snapshot.settings().dimensions() + "d");
        applyHealth(snapshot.health(), snapshot.enabled());
        documentsPanelController.apply(snapshot);
        searchPanelController.apply(snapshot);
        settingsPanelController.apply(snapshot);
    }

    private void healthChanged(Health health) {
        fx.dispatch(() -> {
            if (closed.get()) return;
            Snapshot snapshot = viewModel.snapshot();
            applyHealth(health, snapshot != null && snapshot.enabled());
            settingsPanelController.updateHealth(health);
        });
    }

    private void applyHealth(Health health, boolean enabled) {
        HealthPresentation state = enabled ? presentation(health.status())
                : new HealthPresentation("RAG 未启用", "unconfigured");
        ragBadgeLabel.setText(state.label());
        applyHealthStyle(ragBadge, state.style());
        applyHealthStyle(embeddingDot, presentation(health.status()).style());
    }

    private void markChunkSettingsDirty() { chunkSettingsDirty = true; }

    private void notifyUser(String message) {
        viewModel.feedback(message);
        if (windowToast != null) windowToast.show("[知识库中心] " + message);
    }

    private static HealthPresentation presentation(HealthStatus status) {
        return switch (status) {
            case HEALTHY -> new HealthPresentation("RAG 正常", "healthy");
            case CHECKING -> new HealthPresentation("RAG 检查中", "checking");
            case DEGRADED -> new HealthPresentation("RAG 已降级", "degraded");
            case UNAVAILABLE -> new HealthPresentation("RAG 不可用", "unavailable");
            case UNCONFIGURED -> new HealthPresentation("RAG 未配置", "unconfigured");
        };
    }

    private static void applyHealthStyle(Node node, String style) {
        node.getStyleClass().removeAll(HEALTH_STYLES);
        node.getStyleClass().add("kc-health-" + style);
    }

    private static void visible(Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        loadAction.close();
        closeSubscription();
        if (windowToast != null) {
            windowToast.close();
            windowToast = null;
        }
        if (chunkSettingsDirty) onConfigChanged.run();
        closeAction = () -> { };
        onConfigChanged = () -> { };
        feedbackLabel.textProperty().unbind();
        loadingOverlay.visibleProperty().unbind();
        loadingOverlay.managedProperty().unbind();
    }

    private void closeSubscription() {
        if (healthSubscription == null) return;
        try {
            healthSubscription.close();
        } catch (Exception failure) {
            log.debug("关闭知识库健康订阅失败", failure);
        }
        healthSubscription = null;
    }

    private record HealthPresentation(String label, String style) { }
}
