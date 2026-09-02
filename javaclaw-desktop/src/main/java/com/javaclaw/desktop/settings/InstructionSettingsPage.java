package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import com.javaclaw.api.InstructionResolution;
import com.javaclaw.api.InstructionSourceResolution;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** 管理 Workspace fallback，并展示 AGENTS 层级、摘要、截断与错误。 */
public final class InstructionSettingsPage extends VBox implements ManagedSettingsPage {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final InstructionSettingsPresenter presenter;
    private final ComboBox<Workspace> workspace = new ComboBox<>();
    private final ComboBox<ExecutionRootChoice> executionRoot = new ComboBox<>();
    private final Label digest = value();
    private final Label globalBytes = value();
    private final Label projectBytes = value();
    private final Label resolvedAt = value();
    private final Label warnings = value();
    private final ListView<InstructionSourceResolution> sources = new ListView<>();
    private final TextField fallback = new TextField();
    private final Button save;
    private final Button reload;
    private final AsyncActionBar actions;
    private InstructionSettingsState state = InstructionSettingsState.initial();
    private boolean rendering;

    /**
     * 创建项目约定管理页。
     *
     * @param gateway 强类型 SDK 项目约定边界
     */
    public InstructionSettingsPage(InstructionSettingsGateway gateway) {
        presenter = new InstructionSettingsPresenter(gateway);
        save = components.action("保存备用文件名", ActionStyle.PRIMARY, ActionSize.NORMAL);
        save.setOnAction(event -> presenter.saveFallback());
        reload = components.action("重新解析", ActionStyle.SOFT, ActionSize.NORMAL);
        reload.setOnAction(event -> presenter.reload());
        actions = new AsyncActionBar(save, reload);
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
        actions.show(ActionState.DIRTY, "请先保存或丢弃备用文件名草稿");
    }

    @Override
    public void discardDraft() {
        presenter.discardDraft();
    }

    private void configurePage() {
        Label title = new Label("项目约定");
        title.getStyleClass().addAll("sec-title", "platform-page-title");
        Label hint = new Label("这里展示下一 Turn 会冻结的 AGENTS 层级元数据。管理 RPC 永远不返回、编辑或导出正文，项目约定也不能授予工具权限。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        configureSelectors();
        configureSources();
        getChildren().addAll(title, hint, scopeSection(), summarySection(), sourcesSection(), actions);
        getStyleClass().add("platform-page");
    }

    private void configureSelectors() {
        workspace.setMaxWidth(Double.MAX_VALUE);
        workspace.setAccessibleText("项目约定 Workspace");
        workspace.setCellFactory(ignored ->
                components.detailCell(Workspace::name, value -> value.id().toString()));
        workspace.setButtonCell(components.textCell(Workspace::name));
        workspace.valueProperty().addListener((observable, previous, selected) -> {
            if (!rendering && selected != null) {
                chooseWorkspace(selected);
            }
        });
        executionRoot.setMaxWidth(Double.MAX_VALUE);
        executionRoot.setAccessibleText("项目约定 execution root");
        executionRoot.setCellFactory(
                ignored -> components.detailCell(ExecutionRootChoice::title, ExecutionRootChoice::detail));
        executionRoot.setButtonCell(components.textCell(ExecutionRootChoice::title));
        executionRoot.valueProperty().addListener((observable, previous, selected) -> {
            if (!rendering && selected != null) {
                chooseExecutionRoot(selected);
            }
        });
        fallback.setAccessibleText("项目约定备用文件名");
        fallback.setPromptText("例如 PROJECT.md；留空表示关闭");
        fallback.getStyleClass().add("settings-field");
        fallback.textProperty().addListener((observable, previous, value) -> {
            if (!rendering) {
                presenter.editFallback(value);
            }
        });
    }

    private void configureSources() {
        sources.setAccessibleText("项目约定来源清单");
        sources.setPrefHeight(240);
        sources.setCellFactory(ignored -> sourceCell());
        sources.setPlaceholder(new Label("当前 execution root 未发现项目约定文件"));
    }

    private FormSection scopeSection() {
        FormSection section = new FormSection("解析范围", "Workspace 根到 execution root 逐层选择 override、默认文件或安全 fallback。");
        section.addField("Workspace", workspace);
        section.addField("Execution root", executionRoot);
        section.addField("备用文件名", fallback);
        Label rule = new Label("仅当同目录没有 AGENTS.override.md 和 AGENTS.md 时才读取；只能填写安全 basename，不接受路径。");
        rule.setWrapText(true);
        rule.getStyleClass().add("sec-hint");
        section.addFullWidth(rule);
        return section;
    }

    private FormSection summarySection() {
        FormSection section = new FormSection("冻结摘要", "全局层与项目层各自最多纳入 32 KiB UTF-8 内容，摘要写入 Prompt manifest。");
        section.addField("总 SHA-256", digest);
        section.addField("全局纳入字节", globalBytes);
        section.addField("项目纳入字节", projectBytes);
        section.addField("解析时间", resolvedAt);
        section.addField("警告", warnings);
        return section;
    }

    private FormSection sourcesSection() {
        FormSection section = new FormSection("来源层级", "只显示相对路径、hash、观测字节、实际纳入字节、截断和脱敏错误代码。");
        section.addFullWidth(sources);
        return section;
    }

    private void render(InstructionSettingsState snapshot) {
        state = Objects.requireNonNull(snapshot, "snapshot");
        rendering = true;
        try {
            workspace.getItems().setAll(snapshot.workspaces());
            workspace.setValue(snapshot.workspace().orElse(null));
            executionRoot.getItems().setAll(choices(snapshot));
            executionRoot.setValue(selectedChoice(snapshot));
            fallback.setText(snapshot.fallbackDraft());
            renderResolution(snapshot.resolution());
        } finally {
            rendering = false;
        }
        boolean pending = snapshot.phase() == SettingsLoadState.LOADING;
        save.setDisable(pending || snapshot.phase() == SettingsLoadState.SAVING || !snapshot.dirty());
        reload.setDisable(pending);
        actions.show(actionState(snapshot), snapshot.feedback().message());
    }

    private void renderResolution(Optional<InstructionResolution> resolution) {
        digest.setText(resolution.map(InstructionResolution::digest).orElse("—"));
        globalBytes.setText(resolution
                .map(value -> value.globalIncludedBytes() + " / 32768")
                .orElse("—"));
        projectBytes.setText(resolution
                .map(value -> value.projectIncludedBytes() + " / 32768")
                .orElse("—"));
        resolvedAt.setText(
                resolution.map(value -> value.resolvedAt().toString()).orElse("—"));
        warnings.setText(resolution
                .map(value -> value.warnings().isEmpty() ? "无" : String.join("、", value.warnings()))
                .orElse("—"));
        sources.getItems().setAll(resolution.map(InstructionResolution::sources).orElseGet(java.util.List::of));
    }

    private static java.util.List<ExecutionRootChoice> choices(InstructionSettingsState snapshot) {
        java.util.ArrayList<ExecutionRootChoice> choices = new java.util.ArrayList<>();
        choices.add(new ExecutionRootChoice(Optional.empty(), "Workspace 根", "从 Workspace 根开始解析"));
        snapshot.worktrees().stream()
                .map(worktree -> new ExecutionRootChoice(
                        Optional.of(worktree),
                        "子任务 " + worktree.childThreadId(),
                        worktree.state() + " · Worktree " + worktree.id()))
                .forEach(choices::add);
        return java.util.List.copyOf(choices);
    }

    private static ExecutionRootChoice selectedChoice(InstructionSettingsState snapshot) {
        return choices(snapshot).stream()
                .filter(choice -> choice.worktree().equals(snapshot.worktree()))
                .findFirst()
                .orElseGet(() -> choices(snapshot).getFirst());
    }

    private static ActionState actionState(InstructionSettingsState snapshot) {
        if (snapshot.phase() == SettingsLoadState.LOADING || snapshot.phase() == SettingsLoadState.SAVING) {
            return ActionState.PENDING;
        }
        if (snapshot.phase() == SettingsLoadState.ERROR) {
            return ActionState.ERROR;
        }
        if (snapshot.dirty()) {
            return ActionState.DIRTY;
        }
        return snapshot.feedback().message().isBlank() ? ActionState.IDLE : ActionState.SUCCESS;
    }

    private void chooseWorkspace(Workspace selected) {
        if (dirty()) {
            warnUnsavedChanges();
            restoreSelectors();
            return;
        }
        presenter.chooseWorkspace(selected);
    }

    private void chooseExecutionRoot(ExecutionRootChoice selected) {
        if (dirty()) {
            warnUnsavedChanges();
            restoreSelectors();
            return;
        }
        presenter.chooseWorktree(selected.worktree());
    }

    private void restoreSelectors() {
        rendering = true;
        try {
            workspace.setValue(state.workspace().orElse(null));
            executionRoot.setValue(selectedChoice(state));
        } finally {
            rendering = false;
        }
    }

    private static ListCell<InstructionSourceResolution> sourceCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(InstructionSourceResolution item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String result = item.errorCode()
                        .map(code -> "错误 " + code)
                        .orElseGet(() -> shortDigest(item) + " · " + item.includedBytes() + "/" + item.byteCount()
                                + " bytes" + (item.truncated() ? " · 已截断" : ""));
                setText(item.scope() + " · " + item.relativePath() + " — " + result);
            }
        };
    }

    private static String shortDigest(InstructionSourceResolution source) {
        String value = source.digest().orElseThrow();
        return value.substring(0, 12) + "…";
    }

    private static Label value() {
        Label label = new Label("—");
        label.setWrapText(true);
        label.getStyleClass().add("platform-detail-text");
        return label;
    }

    /**
     * @param worktree 可选受管 Worktree
     * @param title 下拉框主文案
     * @param detail 不含绝对路径的辅助文案
     */
    private record ExecutionRootChoice(Optional<ManagedWorktree> worktree, String title, String detail) {
        private ExecutionRootChoice {
            worktree = Objects.requireNonNull(worktree, "worktree");
            title = Objects.requireNonNull(title, "title");
            detail = Objects.requireNonNull(detail, "detail");
        }
    }
}
