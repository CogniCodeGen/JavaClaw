package com.javaclaw.desktop.settings;

import java.util.Objects;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** Workspace 级 Memory 学习策略的强类型设置页面。 */
public final class LearningSettingsPage extends VBox implements ManagedSettingsPage {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final LearningSettingsPresenter presenter;
    private final ComboBox<Workspace> workspace = new ComboBox<>();
    private final ComboBox<MemoryContracts.LearningPolicy> policy = new ComboBox<>();
    private final Label revision = value();
    private final Label updatedAt = value();
    private final Label safety = value();
    private final Button reload;
    private final Button discard;
    private final Button save;
    private final AsyncActionBar actions;
    private LearningSettingsState state = LearningSettingsState.initial();
    private boolean rendering;

    /** @param gateway 强类型 SDK 学习设置边界 */
    public LearningSettingsPage(LearningSettingsGateway gateway) {
        presenter = new LearningSettingsPresenter(gateway);
        reload = components.action("刷新", ActionStyle.SOFT, ActionSize.NORMAL);
        discard = components.action("丢弃", ActionStyle.SOFT, ActionSize.NORMAL);
        save = components.action("保存", ActionStyle.PRIMARY, ActionSize.NORMAL);
        reload.setOnAction(event -> presenter.reload());
        discard.setOnAction(event -> presenter.discardDraft());
        save.setOnAction(event -> presenter.save());
        actions = new AsyncActionBar(reload, discard, save);
        configurePage();
        presenter.subscribe(this::render);
    }

    @Override
    public Node content() {
        return this;
    }

    @Override
    public void activate() {
        presenter.reload();
    }

    @Override
    public boolean dirty() {
        return state.dirty();
    }

    @Override
    public void warnUnsavedChanges() {
        actions.show(ActionState.DIRTY, "学习策略草稿尚未保存");
    }

    @Override
    public void discardDraft() {
        presenter.discardDraft();
    }

    private void configurePage() {
        Label title = new Label("学习策略");
        title.getStyleClass().addAll("sec-title", "platform-page-title");
        Label hint = new Label("Memory 默认只生成提案。自动学习仅接受同 Workspace、来源可逐字核验的低风险新增 FACT；Skill 永远只生成 Draft，仍需人工发布。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        configureWorkspace();
        configurePolicy();
        FormSection settings = new FormSection("Workspace 策略", "策略按 Workspace 保存，并使用 revision 条件写入；冲突时保留当前草稿。");
        settings.addField("Workspace", workspace);
        settings.addField("学习策略", policy);
        settings.addField("Revision", revision);
        settings.addField("最近更新", updatedAt);
        FormSection boundary = new FormSection("安全边界", "自动学习不能写入 Persona、敏感内容、推测、冲突或不确定工具结果。");
        boundary.addFullWidth(safety);
        getChildren().addAll(title, hint, settings, boundary, actions);
        getStyleClass().add("platform-page");
    }

    private void configureWorkspace() {
        workspace.setMaxWidth(Double.MAX_VALUE);
        workspace.setAccessibleText("学习策略 Workspace");
        workspace.setCellFactory(ignored ->
                components.detailCell(Workspace::name, value -> value.id().toString()));
        workspace.setButtonCell(components.textCell(Workspace::name));
        workspace.valueProperty().addListener((observable, previous, selected) -> {
            if (!rendering && selected != null) {
                presenter.select(selected);
            }
        });
    }

    private void configurePolicy() {
        policy.getItems().setAll(MemoryContracts.LearningPolicy.values());
        policy.setMaxWidth(Double.MAX_VALUE);
        policy.setAccessibleText("Memory 学习策略");
        policy.setCellFactory(ignored -> components.textCell(LearningSettingsPage::policyLabel));
        policy.setButtonCell(components.textCell(LearningSettingsPage::policyLabel));
        policy.valueProperty().addListener((observable, previous, selected) -> {
            if (!rendering && selected != null) {
                presenter.edit(selected);
            }
        });
    }

    private void render(LearningSettingsState snapshot) {
        state = Objects.requireNonNull(snapshot, "snapshot");
        rendering = true;
        try {
            workspace.getItems().setAll(snapshot.workspaces());
            workspace.setValue(snapshot.workspace().orElse(null));
            policy.setValue(snapshot.draft());
            revision.setText(snapshot.saved()
                    .map(value -> Long.toString(value.revision()))
                    .orElse("—"));
            updatedAt.setText(
                    snapshot.saved().map(value -> value.updatedAt().toString()).orElse("—"));
            safety.setText(policyDescription(snapshot.draft()));
        } finally {
            rendering = false;
        }
        boolean pending = snapshot.phase() == SettingsLoadState.LOADING || snapshot.phase() == SettingsLoadState.SAVING;
        workspace.setDisable(pending);
        policy.setDisable(pending || snapshot.saved().isEmpty());
        reload.setDisable(pending);
        discard.setDisable(pending || !snapshot.dirty());
        save.setDisable(pending || !snapshot.dirty());
        actions.show(actionState(snapshot), snapshot.message());
    }

    private static ActionState actionState(LearningSettingsState snapshot) {
        if (snapshot.phase() == SettingsLoadState.LOADING || snapshot.phase() == SettingsLoadState.SAVING) {
            return ActionState.PENDING;
        }
        if (snapshot.phase() == SettingsLoadState.ERROR) {
            return ActionState.ERROR;
        }
        if (snapshot.dirty()) {
            return ActionState.DIRTY;
        }
        return snapshot.message().isBlank() ? ActionState.IDLE : ActionState.SUCCESS;
    }

    private static String policyLabel(MemoryContracts.LearningPolicy value) {
        return switch (value) {
            case OFF -> "关闭";
            case SUGGEST -> "仅提案（推荐）";
            case AUTO_LOW_RISK -> "自动接受低风险事实";
        };
    }

    private static String policyDescription(MemoryContracts.LearningPolicy value) {
        return switch (value) {
            case OFF -> "忽略新的学习候选，不创建 Proposal。";
            case SUGGEST -> "所有候选都形成可审计 Proposal，由用户显式接受或拒绝。";
            case AUTO_LOW_RISK -> "仅自动接受逐字来源可核验的低风险 FACT；其余候选仍形成 Proposal。";
        };
    }

    private static Label value() {
        Label label = new Label("—");
        label.setWrapText(true);
        label.getStyleClass().add("platform-detail-text");
        return label;
    }
}
