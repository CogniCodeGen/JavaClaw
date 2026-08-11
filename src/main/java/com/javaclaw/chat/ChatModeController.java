package com.javaclaw.chat;

import com.javaclaw.api.conversation.PlanProfile;
import com.javaclaw.application.chat.ChatModeApplicationService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.util.Duration;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Objects;
import java.util.function.Consumer;

/** 聊天模式栏 FXML Controller；仅协调页面事件和父 Chat 页面回调。 */
public final class ChatModeController implements AutoCloseable {

    @FXML private HBox root;
    @FXML private ComboBox<ChatModeViewModel.ModeChoice> conversationModeSelector;
    @FXML private ComboBox<PlanProfile> planProfileSelector;
    @FXML private MenuButton loopTemplateMenu;
    @FXML private ComboBox<ChatModeViewModel.WorkflowChoice> workflowSelector;
    @FXML private Button workflowEmptyShortcut;
    @FXML private MenuButton reviewModeMenu;
    @FXML private Label tokenLabel;
    @FXML private Tooltip tokenSummaryTooltip;

    private final ChatModeApplicationService useCases;
    private final ManagedTaskExecutor tasks;
    private final FxDispatcher fx;
    private final ChatModeViewModel viewModel = new ChatModeViewModel();
    private ChatModePresenter presenter;
    private Consumer<String> loopTemplateSelected = ignored -> { };
    private Runnable openWorkflowCenter = () -> { };
    private Runnable openTaskManager = () -> { };
    private Runnable resetTokens = () -> { };
    private boolean closed;

    @Autowired
    public ChatModeController(
            ChatModeApplicationService useCases,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.fx = Objects.requireNonNull(fx, "fx");
    }

    @FXML
    private void initialize() {
        presenter = new ChatModePresenter(
                useCases, tasks, fx, viewModel,
                conversationModeSelector, planProfileSelector, loopTemplateMenu,
                workflowSelector, workflowEmptyShortcut, reviewModeMenu,
                value -> loopTemplateSelected.accept(value));
        tokenSummaryTooltip.setShowDelay(Duration.millis(250));
        tokenLabel.setOnMouseClicked(event -> resetTokens.run());
        presenter.initialize();
    }

    String selectedModeId() {
        return presenter.selectedModeId();
    }

    PlanProfile planProfile() {
        return presenter.planProfile();
    }

    void selectMode(String modeId) {
        presenter.selectMode(modeId);
    }

    void refreshModes(String preferredModeId) {
        presenter.refreshModes(preferredModeId);
    }

    void refreshWorkflows() {
        presenter.refreshWorkflows();
    }

    void workflowPublished(String workflowId, String workflowName) {
        presenter.workflowPublished(workflowId, workflowName);
    }

    void refreshReviewMode() {
        presenter.refreshReviewMode();
    }

    void updateTokenSummary(String summary, String details) {
        tokenLabel.setText(Objects.requireNonNullElse(summary, ""));
        tokenSummaryTooltip.setText(Objects.requireNonNullElse(details, ""));
    }

    void setOnLoopTemplateSelected(Consumer<String> action) {
        loopTemplateSelected = Objects.requireNonNull(action, "action");
    }

    void setOnOpenWorkflowCenter(Runnable action) {
        openWorkflowCenter = Objects.requireNonNull(action, "action");
    }

    void setOnOpenTaskManager(Runnable action) {
        openTaskManager = Objects.requireNonNull(action, "action");
    }

    void setOnResetTokens(Runnable action) {
        resetTokens = Objects.requireNonNull(action, "action");
    }

    @FXML
    private void openWorkflowCenterRequested() {
        openWorkflowCenter.run();
    }

    @FXML
    private void openTaskManagerRequested() {
        openTaskManager.run();
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (presenter != null) presenter.close();
    }
}
