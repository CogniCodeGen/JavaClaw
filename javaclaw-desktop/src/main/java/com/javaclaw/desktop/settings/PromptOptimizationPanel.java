package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.PromptOptimizationDraft;
import com.javaclaw.api.PromptOptimizationState;
import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.PlatformDialogs;
import com.javaclaw.protocol.PromptOptimizationRpcContracts;

/** Agent页内可复用的提示词优化、历史草稿与人工采纳面板。 */
public final class PromptOptimizationPanel {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final PromptOptimizationSettingsPresenter presenter;
    private final FormSection content =
            new FormSection("提示词优化草稿", "优化会通过当前Agent启动普通受预算任务，并可能产生模型服务费用。结果只保存为草稿，人工采纳后才创建新版本。");
    private final Button start;
    private final Button refresh;
    private final Button cancel;
    private final Button adopt;
    private final Label status = new Label();
    private final Label identity = new Label("尚未选择任务");
    private final Label provenance = new Label("—");
    private final Label adoption = new Label("尚未采纳");
    private final ListView<PromptOptimizationDraft> drafts = new ListView<>();
    private final TextArea result = new TextArea();
    private Optional<Workspace> scopedWorkspace = Optional.empty();
    private boolean rendering;
    private boolean reportedPending;
    private Runnable pendingChanged = () -> {};

    /**
     * 创建面板。
     *
     * @param gateway 提示词优化 SDK 边界
     * @param adoptedCallback 采纳成功后刷新Agent目录
     */
    public PromptOptimizationPanel(PromptOptimizationSettingsGateway gateway, Runnable adoptedCallback) {
        presenter = new PromptOptimizationSettingsPresenter(gateway, adoptedCallback);
        start = components.action("生成优化草稿", ActionStyle.SOFT, ActionSize.COMPACT);
        refresh = components.action("刷新状态", ActionStyle.GHOST, ActionSize.COMPACT);
        cancel = components.action("取消任务", ActionStyle.GHOST, ActionSize.COMPACT);
        adopt = components.action("采纳草稿", ActionStyle.PRIMARY, ActionSize.COMPACT);
        configureControls();
        buildLayout();
        bindEvents();
        presenter.subscribe(this::render);
    }

    /** @return 可嵌入Agent页面表单的根节点 */
    public Node content() {
        return content;
    }

    /** 激活面板并刷新当前固定 Workspace 的草稿目录。 */
    public void activate() {
        scopedWorkspace.ifPresent(presenter::selectWorkspace);
    }

    /**
     * 固定提示词优化的 Workspace 作用域。
     *
     * @param workspace 设置中心作用域
     */
    public void workspaceChanged(Optional<Workspace> workspace) {
        Optional<Workspace> checked = java.util.Objects.requireNonNull(workspace, "workspace");
        if (scopedWorkspace.equals(checked)) {
            return;
        }
        scopedWorkspace = checked;
        presenter.selectWorkspace(checked.orElse(null));
    }

    /** @return 是否有不能被作用域切换中断的写操作 */
    public boolean pending() {
        return presenter.state().pending();
    }

    /**
     * 监听本面板操作状态，供所属页面即时更新编辑与导入的互斥控件。
     *
     * @param listener 状态改变时在 UI Thread 调用，不修改优化任务
     */
    public void onPendingChanged(Runnable listener) {
        pendingChanged = Objects.requireNonNull(listener, "listener");
    }

    /**
     * 切换当前已保存Agent；新建或脏草稿传空。
     *
     * @param role 当前权威Agent
     */
    public void selectRole(Optional<AgentRole> role) {
        presenter.selectRole(role);
    }

    private void configureControls() {
        drafts.setPrefHeight(150);
        drafts.setCellFactory(ignored -> components.detailCell(
                draft -> SettingsLabels.promptOptimizationState(draft.result().state()) + " · "
                        + draft.ref().id(),
                PromptOptimizationPanel::draftDetail));
        result.setEditable(false);
        result.setWrapText(true);
        result.setPrefRowCount(7);
        identity.setWrapText(true);
        provenance.setWrapText(true);
        provenance.getStyleClass().add("platform-monospace");
        adoption.setWrapText(true);
        status.setWrapText(true);
        status.getStyleClass().add("sec-hint");
    }

    private void buildLayout() {
        content.addFullWidth(new HBox(8, start, refresh, cancel, adopt));
        content.addField("历史草稿", drafts);
        content.addField("任务引用", identity);
        content.addField("优化说明来源", provenance);
        content.addField("草稿正文", result);
        content.addField("采纳状态", adoption);
        content.addFullWidth(status);
        HBox.setHgrow(status, Priority.ALWAYS);
    }

    private void bindEvents() {
        drafts.getSelectionModel().selectedItemProperty().addListener((ignored, previous, value) -> {
            if (!rendering && value != null) {
                presenter.selectDraft(value);
            }
        });
        start.setOnAction(event -> confirmStart());
        refresh.setOnAction(event -> presenter.refresh());
        cancel.setOnAction(event -> presenter.cancel());
        adopt.setOnAction(event -> confirmAdoption());
    }

    private void render(PromptOptimizationSettingsState state) {
        rendering = true;
        try {
            PromptOptimizationSelection selection = state.selection();
            drafts.getItems().setAll(selection.drafts());
            drafts.getSelectionModel().select(selection.selected().orElse(null));
            renderDraft(selection.selected());
            renderButtons(state);
            renderStatus(state);
        } finally {
            rendering = false;
        }
        if (reportedPending != state.pending()) {
            reportedPending = state.pending();
            pendingChanged.run();
        }
    }

    private void renderDraft(Optional<PromptOptimizationDraft> selected) {
        PromptOptimizationDraft draft = selected.orElse(null);
        if (draft == null) {
            identity.setText("尚未选择任务");
            provenance.setText("—");
            result.clear();
            adoption.setText("尚未采纳");
            return;
        }
        identity.setText("Agent " + draft.ref().sourceRole().id() + "@"
                + draft.ref().sourceRole().revision()
                + " · 对话 " + draft.ref().threadId() + " · 任务 "
                + draft.ref().turnId()
                + " · 任务版本 " + draft.result().turnRevision());
        provenance.setText(draft.provenance().instructionRevision() + " · "
                + draft.provenance().instructionDigest());
        result.setText(draft.result().content().orElse(""));
        adoption.setText(draft.adoptedRole()
                .map(role -> "已采纳为 " + role.id() + "@" + role.revision())
                .orElse("尚未采纳；草稿不会自动修改Agent"));
    }

    private void renderButtons(PromptOptimizationSettingsState state) {
        Optional<PromptOptimizationDraft> selected = state.selection().selected();
        PromptOptimizationState taskState =
                selected.map(value -> value.result().state()).orElse(null);
        boolean pending = state.pending();
        boolean active = taskState == PromptOptimizationState.QUEUED || taskState == PromptOptimizationState.RUNNING;
        boolean ready = taskState == PromptOptimizationState.READY
                && selected.flatMap(PromptOptimizationDraft::adoptedRole).isEmpty();
        boolean canStart = scopedWorkspace.isPresent()
                && state.selection().workspace().isPresent()
                && state.selection()
                        .role()
                        .filter(role -> role.lifecycle() == com.javaclaw.api.RoleLifecycle.ACTIVE)
                        .isPresent();
        start.setDisable(pending || !canStart);
        refresh.setDisable(pending || state.selection().workspace().isEmpty());
        cancel.setDisable(pending || !active);
        adopt.setDisable(pending || !ready);
    }

    private void renderStatus(PromptOptimizationSettingsState state) {
        status.getStyleClass().remove("platform-action-error");
        if (state.phase() == SettingsLoadState.ERROR) {
            status.getStyleClass().add("platform-action-error");
        }
        String conflict = state.revisionConflict() ? "Agent版本已改变；草稿仍保留，可刷新后重新决策。" : "";
        status.setText(conflict.isEmpty() ? state.message() : conflict + " " + state.message());
    }

    private void confirmStart() {
        TextInputDialog dialog = confirmationDialog(
                "确认可能计费的提示词优化",
                "此操作会用当前Agent启动真实任务，并调用所选模型服务",
                PromptOptimizationRpcContracts.BILLING_CONFIRMATION,
                "启动优化");
        dialog.showAndWait().ifPresent(value -> presenter.start(true, value));
    }

    private void confirmAdoption() {
        TextInputDialog dialog = confirmationDialog(
                "确认人工采纳提示词草稿",
                "采纳会以源Agent版本为前提创建新版本；不会覆盖或删除草稿",
                PromptOptimizationRpcContracts.ADOPTION_CONFIRMATION,
                "采纳草稿");
        dialog.showAndWait().ifPresent(value -> presenter.adopt(true, value));
    }

    private TextInputDialog confirmationDialog(
            String title, String consequence, String confirmation, String actionLabel) {
        return PlatformDialogs.exactText(content, title, consequence, confirmation, actionLabel);
    }

    private static String draftDetail(PromptOptimizationDraft draft) {
        String adoption = draft.adoptedRole().map(ignored -> " · 已采纳").orElse("");
        String error = draft.result().errorCode().map(value -> " · " + value).orElse("");
        return draft.provenance().createdAt() + " · Agent "
                + draft.ref().sourceRole().revision() + adoption + error;
    }
}
