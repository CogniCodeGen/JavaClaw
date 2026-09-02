package com.javaclaw.desktop.settings;

import java.util.Optional;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.PromptOptimizationDraft;
import com.javaclaw.api.PromptOptimizationState;
import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.protocol.PromptOptimizationRpcContracts;

/** Agent Profile 页内可复用的 Prompt 优化、历史草稿与人工采纳面板。 */
public final class PromptOptimizationPanel {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final PromptOptimizationSettingsPresenter presenter;
    private final FormSection content = new FormSection(
            "Prompt 优化草稿", "优化会通过当前 Profile 启动普通受预算 Turn，并可能产生 Provider 费用。结果只保存为草稿，人工采纳后才创建新 revision。");
    private final ComboBox<Workspace> workspace = new ComboBox<>();
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
    private boolean rendering;

    /**
     * 创建面板。
     *
     * @param gateway Prompt 优化 SDK 边界
     * @param adoptedCallback 采纳成功后刷新 Profile 目录
     */
    public PromptOptimizationPanel(PromptOptimizationSettingsGateway gateway, Runnable adoptedCallback) {
        presenter = new PromptOptimizationSettingsPresenter(gateway, adoptedCallback);
        start = components.action("生成优化草稿", ActionStyle.SOFT, ActionSize.COMPACT);
        refresh = components.action("刷新状态", ActionStyle.GHOST, ActionSize.COMPACT);
        cancel = components.action("取消 Turn", ActionStyle.GHOST, ActionSize.COMPACT);
        adopt = components.action("采纳草稿", ActionStyle.PRIMARY, ActionSize.COMPACT);
        configureControls();
        buildLayout();
        bindEvents();
        presenter.subscribe(this::render);
    }

    /** @return 可嵌入 Profile 页面表单的根节点 */
    public Node content() {
        return content;
    }

    /** 激活面板并刷新 Workspace 与草稿目录。 */
    public void activate() {
        presenter.activate();
    }

    /**
     * 切换当前已保存 Profile；新建或脏草稿传空。
     *
     * @param profile 当前权威 Profile
     */
    public void selectProfile(Optional<AgentProfile> profile) {
        presenter.selectProfile(profile);
    }

    private void configureControls() {
        workspace.setPromptText("选择 Workspace");
        workspace.setCellFactory(ignored -> components.detailCell(
                Workspace::name, value -> value.id().value().toString()));
        workspace.setButtonCell(components.textCell(
                value -> value == null ? "" : value.name() + " · " + value.id().value()));
        drafts.setPrefHeight(150);
        drafts.setCellFactory(ignored -> components.detailCell(
                draft -> draft.result().state() + " · " + draft.ref().id(), PromptOptimizationPanel::draftDetail));
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
        content.addField("Workspace", workspace);
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
        workspace.valueProperty().addListener((ignored, previous, value) -> {
            if (!rendering) {
                presenter.selectWorkspace(value);
            }
        });
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
            workspace.setItems(FXCollections.observableArrayList(selection.workspaces()));
            workspace.setValue(selection.workspace().orElse(null));
            drafts.getItems().setAll(selection.drafts());
            drafts.getSelectionModel().select(selection.selected().orElse(null));
            renderDraft(selection.selected());
            renderButtons(state);
            renderStatus(state);
        } finally {
            rendering = false;
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
        identity.setText("Profile " + draft.ref().sourceProfile().id() + "@"
                + draft.ref().sourceProfile().revision()
                + " · Thread " + draft.ref().threadId() + " · Turn "
                + draft.ref().turnId()
                + " · Turn revision " + draft.result().turnRevision());
        provenance.setText(draft.provenance().instructionRevision() + " · "
                + draft.provenance().instructionDigest());
        result.setText(draft.result().content().orElse(""));
        adoption.setText(draft.adoptedProfile()
                .map(profile -> "已采纳为 " + profile.id() + "@" + profile.revision())
                .orElse("尚未采纳；草稿不会自动修改 Agent Profile"));
    }

    private void renderButtons(PromptOptimizationSettingsState state) {
        Optional<PromptOptimizationDraft> selected = state.selection().selected();
        PromptOptimizationState taskState =
                selected.map(value -> value.result().state()).orElse(null);
        boolean pending = state.pending();
        boolean active = taskState == PromptOptimizationState.QUEUED || taskState == PromptOptimizationState.RUNNING;
        boolean ready = taskState == PromptOptimizationState.READY
                && selected.flatMap(PromptOptimizationDraft::adoptedProfile).isEmpty();
        boolean canStart = state.selection().workspace().isPresent()
                && state.selection()
                        .profile()
                        .filter(profile -> profile.lifecycle() == com.javaclaw.api.ProfileLifecycle.ACTIVE)
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
        String conflict = state.revisionConflict() ? "Profile revision 已改变；草稿仍保留，可刷新后重新决策。" : "";
        status.setText(conflict.isEmpty() ? state.message() : conflict + " " + state.message());
    }

    private void confirmStart() {
        TextInputDialog dialog = confirmationDialog(
                "确认可能计费的 Prompt 优化",
                "此操作会用当前 Profile 启动真实 Harness Turn，并调用所选 Provider",
                PromptOptimizationRpcContracts.BILLING_CONFIRMATION);
        dialog.showAndWait().ifPresent(value -> presenter.start(true, value));
    }

    private void confirmAdoption() {
        TextInputDialog dialog = confirmationDialog(
                "确认人工采纳 Prompt 草稿",
                "采纳会以源 Profile revision 为前提创建新 revision；不会覆盖或删除草稿",
                PromptOptimizationRpcContracts.ADOPTION_CONFIRMATION);
        dialog.showAndWait().ifPresent(value -> presenter.adopt(true, value));
    }

    private TextInputDialog confirmationDialog(String title, String header, String confirmation) {
        TextInputDialog dialog = new TextInputDialog();
        if (content.getScene() != null && content.getScene().getWindow() != null) {
            dialog.initOwner(content.getScene().getWindow());
        }
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText("精确输入：" + confirmation);
        return dialog;
    }

    private static String draftDetail(PromptOptimizationDraft draft) {
        String adoption = draft.adoptedProfile().map(ignored -> " · 已采纳").orElse("");
        String error = draft.result().errorCode().map(value -> " · " + value).orElse("");
        return draft.provenance().createdAt() + " · Profile "
                + draft.ref().sourceProfile().revision() + adoption + error;
    }
}
