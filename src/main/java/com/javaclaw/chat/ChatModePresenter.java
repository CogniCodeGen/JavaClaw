package com.javaclaw.chat;

import com.javaclaw.api.conversation.PlanProfile;
import com.javaclaw.application.chat.ChatModeApplicationService;
import com.javaclaw.config.ToolReviewMode;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.scene.control.ComboBox;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** 把模式栏页面状态映射到 FXML 控件，并协调异步工作流查询。 */
final class ChatModePresenter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatModePresenter.class);
    private static final List<LoopTemplate> LOOP_TEMPLATES = List.of(
            new LoopTemplate("快速推进", "@loop max=10 judge=on\n持续推进目标，直到验收条件满足"),
            new LoopTemplate("定时轮询", "@loop interval=5m max=20 judge=on\n检查目标状态，满足后停止"),
            new LoopTemplate("构建守护", "@loop interval=30s max=20 judge=on\n反复修复并运行测试，直到全部通过"));

    private final ChatModeApplicationService useCases;
    private final ChatModeViewModel viewModel;
    private final ComboBox<ChatModeViewModel.ModeChoice> modeSelector;
    private final ComboBox<PlanProfile> planProfileSelector;
    private final MenuButton loopTemplateMenu;
    private final ComboBox<ChatModeViewModel.WorkflowChoice> workflowSelector;
    private final javafx.scene.control.Button workflowEmptyShortcut;
    private final MenuButton reviewModeMenu;
    private final Consumer<String> loopTemplateSelected;
    private final UiAsyncAction<ChatModeApplicationService.WorkflowSnapshot> workflowRefresh;
    private boolean updatingMode;
    private boolean updatingWorkflow;
    private boolean closed;

    ChatModePresenter(
            ChatModeApplicationService useCases,
            ManagedTaskExecutor tasks,
            FxDispatcher fx,
            ChatModeViewModel viewModel,
            ComboBox<ChatModeViewModel.ModeChoice> modeSelector,
            ComboBox<PlanProfile> planProfileSelector,
            MenuButton loopTemplateMenu,
            ComboBox<ChatModeViewModel.WorkflowChoice> workflowSelector,
            javafx.scene.control.Button workflowEmptyShortcut,
            MenuButton reviewModeMenu,
            Consumer<String> loopTemplateSelected) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
        this.modeSelector = Objects.requireNonNull(modeSelector, "modeSelector");
        this.planProfileSelector = Objects.requireNonNull(planProfileSelector, "planProfileSelector");
        this.loopTemplateMenu = Objects.requireNonNull(loopTemplateMenu, "loopTemplateMenu");
        this.workflowSelector = Objects.requireNonNull(workflowSelector, "workflowSelector");
        this.workflowEmptyShortcut = Objects.requireNonNull(
                workflowEmptyShortcut, "workflowEmptyShortcut");
        this.reviewModeMenu = Objects.requireNonNull(reviewModeMenu, "reviewModeMenu");
        this.loopTemplateSelected = Objects.requireNonNull(
                loopTemplateSelected, "loopTemplateSelected");
        workflowRefresh = new UiAsyncAction<>(
                Objects.requireNonNull(tasks, "tasks"), Objects.requireNonNull(fx, "fx"));
    }

    void initialize() {
        modeSelector.setItems(viewModel.modes());
        ensureSingleClick(modeSelector);
        modeSelector.valueProperty().addListener((observable, previous, selected) -> {
            if (!updatingMode && selected != null) applyMode(selected);
        });

        planProfileSelector.getItems().setAll(PlanProfile.values());
        planProfileSelector.valueProperty().bindBidirectional(viewModel.planProfileProperty());
        planProfileSelector.setTooltip(new Tooltip(
                "AUTO 自动判断；QUICK 轻量；STANDARD 标准；DEEP 深度"));

        configureLoopTemplates();
        workflowSelector.setItems(viewModel.workflows());
        ensureSingleClick(workflowSelector);
        workflowSelector.setOnShowing(event -> refreshWorkflows());
        workflowSelector.valueProperty().addListener((observable, previous, selected) -> {
            if (!updatingWorkflow) selectWorkflow(selected);
        });
        configureReviewModes();
        refreshModes("chat");
        refreshWorkflows();
    }

    String selectedModeId() {
        return viewModel.selectedModeIdProperty().get();
    }

    PlanProfile planProfile() {
        PlanProfile selected = viewModel.planProfileProperty().get();
        return selected == null ? PlanProfile.AUTO : selected;
    }

    void refreshModes(String preferredId) {
        if (closed) return;
        List<ChatModeViewModel.ModeChoice> choices = useCases.availableConversationModes().stream()
                .map(mode -> new ChatModeViewModel.ModeChoice(
                        mode.id(), modeLabel(mode), mode.tooltip()))
                .toList();
        ChatModeViewModel.ModeChoice resolved = resolveMode(choices, preferredId);
        updatingMode = true;
        try {
            viewModel.modes().setAll(choices);
            modeSelector.setValue(resolved);
        } finally {
            updatingMode = false;
        }
        if (resolved != null) applyMode(resolved);
    }

    void selectMode(String id) {
        if (id == null || id.isBlank()) return;
        viewModel.modes().stream()
                .filter(choice -> choice.id().equals(id))
                .findFirst()
                .ifPresentOrElse(choice -> {
                    if (Objects.equals(modeSelector.getValue(), choice)) applyMode(choice);
                    else modeSelector.setValue(choice);
                }, () -> log.warn("无法切换到未注册的会话模式: {}", id));
    }

    void refreshWorkflows() {
        if (closed || useCases.isTransitioning()) return;
        workflowRefresh.execute(
                TaskSpec.io("chat-mode-workflows"),
                context -> useCases.publishedWorkflows(),
                this::applyWorkflowSnapshot,
                failure -> log.warn("刷新已发布工作流失败: {}", failure.getMessage()));
    }

    void workflowPublished(String workflowId, String workflowName) {
        if (closed || workflowId == null || workflowId.isBlank()) return;
        workflowRefresh.cancel();
        ChatModeViewModel.WorkflowChoice selected = workflowSelector.getValue();
        ChatModeViewModel.WorkflowChoice published =
                new ChatModeViewModel.WorkflowChoice(workflowId, workflowName);
        viewModel.workflows().removeIf(choice -> choice.id().equals(workflowId));
        viewModel.workflows().addFirst(published);
        ChatModeViewModel.WorkflowChoice resolved = selected == null
                || selected.id().equals(workflowId) ? published : selected;
        setWorkflowSelection(resolved);
        renderWorkflowShortcut();
    }

    void refreshReviewMode() {
        renderReviewMode(useCases.currentReviewMode());
    }

    private void applyMode(ChatModeViewModel.ModeChoice choice) {
        viewModel.selectedModeIdProperty().set(choice.id());
        modeSelector.setTooltip(choice.tooltip().isBlank() ? null : new Tooltip(choice.tooltip()));
        renderModeSpecificControls();
        log.info("切换到会话模式: {}", choice.id());
    }

    private void renderModeSpecificControls() {
        String modeId = selectedModeId();
        setShown(planProfileSelector, "plan".equals(modeId));
        setShown(loopTemplateMenu, "loop".equals(modeId));
        setShown(workflowSelector, "workflow".equals(modeId));
        renderWorkflowShortcut();
        if ("workflow".equals(modeId)) refreshWorkflows();
    }

    private void applyWorkflowSnapshot(ChatModeApplicationService.WorkflowSnapshot snapshot) {
        if (closed || !useCases.isCurrent(snapshot)) return;
        String selectedId = viewModel.selectedWorkflowIdProperty().get();
        List<ChatModeViewModel.WorkflowChoice> workflows = snapshot.workflows().stream()
                .map(workflow -> new ChatModeViewModel.WorkflowChoice(
                        workflow.id(), workflow.name()))
                .toList();
        viewModel.workflows().setAll(workflows);
        workflowSelector.setPromptText(workflows.isEmpty()
                ? "暂无已发布工作流" : "选择已发布工作流");
        ChatModeViewModel.WorkflowChoice resolved = workflows.stream()
                .filter(workflow -> workflow.id().equals(selectedId))
                .findFirst()
                .orElse(workflows.isEmpty() ? null : workflows.getFirst());
        setWorkflowSelection(resolved);
        renderWorkflowShortcut();
    }

    private void setWorkflowSelection(ChatModeViewModel.WorkflowChoice choice) {
        updatingWorkflow = true;
        try {
            workflowSelector.setValue(choice);
            viewModel.selectedWorkflowIdProperty().set(choice == null ? null : choice.id());
        } finally {
            updatingWorkflow = false;
        }
        useCases.selectWorkflow(choice == null ? null : choice.id());
    }

    private void selectWorkflow(ChatModeViewModel.WorkflowChoice choice) {
        viewModel.selectedWorkflowIdProperty().set(choice == null ? null : choice.id());
        useCases.selectWorkflow(choice == null ? null : choice.id());
    }

    private void renderWorkflowShortcut() {
        boolean visible = "workflow".equals(selectedModeId()) && viewModel.workflows().isEmpty();
        setShown(workflowEmptyShortcut, visible);
    }

    private void configureLoopTemplates() {
        loopTemplateMenu.setTooltip(new Tooltip(
                "语法示例：@loop interval=5m max=20 judge=on\n目标描述"));
        for (LoopTemplate template : LOOP_TEMPLATES) {
            MenuItem item = new MenuItem(template.label());
            item.setOnAction(event -> loopTemplateSelected.accept(template.value()));
            loopTemplateMenu.getItems().add(item);
        }
    }

    private void configureReviewModes() {
        for (ToolReviewMode mode : ToolReviewMode.values()) {
            MenuItem item = new MenuItem(mode.displayName());
            item.setOnAction(event -> changeReviewMode(mode));
            reviewModeMenu.getItems().add(item);
        }
        refreshReviewMode();
    }

    private void changeReviewMode(ToolReviewMode mode) {
        renderReviewMode(mode);
        try {
            useCases.changeReviewMode(mode);
            log.info("工具审核模式切换为：{} ({})", mode.displayName(), mode.id());
        } catch (RuntimeException failure) {
            log.warn("工具审核模式切换失败", failure);
            refreshReviewMode();
        }
    }

    private void renderReviewMode(ToolReviewMode mode) {
        ToolReviewMode resolved = mode == null ? ToolReviewMode.SMART : mode;
        reviewModeMenu.setText(resolved.displayName());
        reviewModeMenu.setTooltip(new Tooltip(
                "工具审核：" + resolved.displayName() + "\n" + resolved.description()));
    }

    private static ChatModeViewModel.ModeChoice resolveMode(
            List<ChatModeViewModel.ModeChoice> choices, String preferredId) {
        return choices.stream()
                .filter(choice -> choice.id().equals(preferredId))
                .findFirst()
                .orElseGet(() -> choices.stream()
                        .filter(choice -> "chat".equals(choice.id()))
                        .findFirst()
                        .orElse(choices.isEmpty() ? null : choices.getFirst()));
    }

    private static String modeLabel(ChatModeApplicationService.ModeOption mode) {
        return switch (mode.id()) {
            case "chat" -> "对话";
            case "plan" -> "研讨";
            case "loop" -> "循环";
            case "workflow" -> "工作流";
            default -> mode.displayName();
        };
    }

    private static void ensureSingleClick(ComboBox<?> combo) {
        combo.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY
                    || combo.isDisabled() || combo.isShowing()) return;
            combo.requestFocus();
            combo.show();
            event.consume();
        });
    }

    private static void setShown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        workflowRefresh.close();
        planProfileSelector.valueProperty().unbindBidirectional(viewModel.planProfileProperty());
    }

    private record LoopTemplate(String label, String value) {
    }
}
