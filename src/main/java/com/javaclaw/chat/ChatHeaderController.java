package com.javaclaw.chat;

import com.javaclaw.memory.embed.EmbeddingHealthSnapshot;
import com.javaclaw.ui.javafx.knowledge.KnowledgeMenuController;
import com.javaclaw.ui.javafx.knowledge.KnowledgeMenuSnapshot;
import com.javaclaw.ui.javafx.theme.ThemeMenuController;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** FXML controller for the chat top bar; it only coordinates events and view state. */
public final class ChatHeaderController implements AutoCloseable {
    @FXML private HBox root;
    @FXML private Button sidebarToggleButton;
    @FXML private Label statusDot;
    @FXML private Label titleLabel;
    @FXML private Label metadataLabel;
    @FXML private Label localModeBadge;
    @FXML private Label embeddingHealthBadge;
    @FXML private KnowledgeMenuController knowledgeMenuController;
    @FXML private ThemeMenuController themeMenuController;
    @FXML private Button settingsButton;

    private final ChatHeaderViewModel viewModel = new ChatHeaderViewModel();
    private Runnable toggleSidebar = () -> { };
    private Runnable openTasks = () -> { };
    private Runnable openSettings = () -> { };
    private Runnable clearHistory = () -> { };

    @FXML
    private void initialize() {
        titleLabel.textProperty().bind(viewModel.titleProperty());
        metadataLabel.textProperty().bind(viewModel.metadataProperty());
        embeddingHealthBadge.textProperty().bind(viewModel.embeddingProperty());
        localModeBadge.visibleProperty().bind(viewModel.localModeProperty());
        localModeBadge.managedProperty().bind(localModeBadge.visibleProperty());
        embeddingHealthBadge.visibleProperty().bind(viewModel.embeddingVisibleProperty());
        embeddingHealthBadge.managedProperty().bind(embeddingHealthBadge.visibleProperty());
        sidebarToggleButton.visibleProperty().bind(viewModel.sidebarToggleVisibleProperty());
        sidebarToggleButton.managedProperty().bind(sidebarToggleButton.visibleProperty());
        viewModel.streamingProperty().addListener((ignored, previous, streaming) -> {
            statusDot.getStyleClass().removeAll("status-idle", "status-executing");
            statusDot.getStyleClass().add(streaming ? "status-executing" : "status-idle");
        });
        viewModel.embeddingDetailProperty().addListener((ignored, previous, detail) ->
                embeddingHealthBadge.setTooltip(new Tooltip(detail)));
    }

    void configure(
            Runnable sidebarAction,
            Runnable tasksAction,
            Runnable settingsAction,
            Runnable clearAction,
            Supplier<KnowledgeMenuSnapshot> knowledgeSnapshot,
            Consumer<Set<String>> knowledgeSelection,
            Runnable openKnowledgeCenter) {
        toggleSidebar = action(sidebarAction);
        openTasks = action(tasksAction);
        openSettings = action(settingsAction);
        clearHistory = action(clearAction);
        knowledgeMenuController.configure(
                Objects.requireNonNull(knowledgeSnapshot, "knowledgeSnapshot"),
                Objects.requireNonNull(knowledgeSelection, "knowledgeSelection"),
                action(openKnowledgeCenter));
    }

    void setShortcutHints(String modifier) {
        sidebarToggleButton.setTooltip(new Tooltip("显示侧栏 (" + modifier + " + \\)"));
        settingsButton.setTooltip(new Tooltip("设置（" + modifier + " + ,）"));
    }

    void showTitle(String title, String metadata) { viewModel.showTitle(title, metadata); }
    void setStreaming(boolean value) { viewModel.streamingProperty().set(value); }
    void setLocalMode(boolean value) { viewModel.localModeProperty().set(value); }
    void setSidebarToggleVisible(boolean value) {
        viewModel.sidebarToggleVisibleProperty().set(value);
    }

    void showEmbedding(EmbeddingHealthSnapshot snapshot) {
        String text = switch (snapshot.status()) {
            case HEALTHY -> "嵌入：正常";
            case CHECKING -> "嵌入：检查中";
            case DEGRADED -> "嵌入：降级";
            case UNAVAILABLE -> "嵌入：不可用";
            case UNCONFIGURED -> "嵌入：未配置";
        };
        String detail = snapshot.lastError() == null || snapshot.lastError().isBlank()
                ? text : text + "\n" + snapshot.lastError();
        boolean visible = snapshot.status()
                != com.javaclaw.memory.embed.EmbeddingHealthStatus.HEALTHY;
        viewModel.showEmbedding(text, detail, visible);
    }

    void resetKnowledgeMenu() { knowledgeMenuController.reset(); }
    void refreshKnowledgeMenu() { knowledgeMenuController.refresh(); }
    void reloadTheme() { themeMenuController.reloadFromWorkspace(); }

    @FXML private void toggleSidebarRequested() { toggleSidebar.run(); }
    @FXML private void openTasksRequested() { openTasks.run(); }
    @FXML private void openSettingsRequested() { openSettings.run(); }
    @FXML private void clearHistoryRequested() { clearHistory.run(); }

    @Override
    public void close() {
        titleLabel.textProperty().unbind();
        metadataLabel.textProperty().unbind();
        embeddingHealthBadge.textProperty().unbind();
        localModeBadge.visibleProperty().unbind();
        localModeBadge.managedProperty().unbind();
        embeddingHealthBadge.visibleProperty().unbind();
        embeddingHealthBadge.managedProperty().unbind();
        sidebarToggleButton.visibleProperty().unbind();
        sidebarToggleButton.managedProperty().unbind();
        toggleSidebar = () -> { };
        openTasks = () -> { };
        openSettings = () -> { };
        clearHistory = () -> { };
    }

    private static Runnable action(Runnable value) { return value == null ? () -> { } : value; }
}
