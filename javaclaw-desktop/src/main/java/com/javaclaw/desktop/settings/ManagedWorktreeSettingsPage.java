package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeArtifact;
import com.javaclaw.api.ManagedWorktreeState;
import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.ListDetailPane;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.PlatformComponentFactory.FeedbackKind;
import com.javaclaw.protocol.WorktreeRpcContracts;

/** 父子 Thread 受管 Worktree 的恢复与危险清理页面。 */
public final class ManagedWorktreeSettingsPage extends VBox implements ManagedSettingsPage {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final ManagedWorktreeSettingsPresenter presenter;
    private final ComboBox<Workspace> workspace = new ComboBox<>();
    private final CheckBox includeCleaned = new CheckBox("显示已清理历史");
    private final ListDetailPane<ManagedWorktree> worktrees = new ListDetailPane<>();
    private final Label worktreeId = value();
    private final Label worktreeState = value();
    private final Label parentThread = value();
    private final Label childThread = value();
    private final Label baseCommit = value();
    private final Label revision = value();
    private final Label backupRef = value();
    private final Label updatedAt = value();
    private final Label artifactKind = value();
    private final Label artifactDigest = value();
    private final Label artifactName = value();
    private final Label artifactSize = value();
    private final Button openParent;
    private final Button openChild;
    private final Button interrupt;
    private final Button patch;
    private final Button backup;
    private final Button cleanup;
    private final AsyncActionBar actions;
    private ManagedWorktreeSettingsState state = ManagedWorktreeSettingsState.initial();
    private Node detail;
    private boolean rendering;

    /** @param gateway 强类型 SDK 设置边界 */
    public ManagedWorktreeSettingsPage(CoreSettingsGateway gateway) {
        presenter = new ManagedWorktreeSettingsPresenter(gateway);
        openParent = action("打开父 Thread", ActionStyle.GHOST, event -> presenter.navigate(true));
        openChild = action("打开子 Thread", ActionStyle.GHOST, event -> presenter.navigate(false));
        interrupt = action("中断子任务", ActionStyle.DANGER, event -> interrupt());
        patch = action("生成 Patch Attachment", ActionStyle.SOFT, event -> presenter.exportPatch());
        backup = action("生成验证 Backup", ActionStyle.SOFT, event -> presenter.backup());
        cleanup = action("危险 Cleanup", ActionStyle.DANGER, event -> cleanup());
        actions = new AsyncActionBar(interrupt, patch, backup, cleanup);
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
        return false;
    }

    @Override
    public void warnUnsavedChanges() {}

    @Override
    public void discardDraft() {}

    private void configurePage() {
        Label title = new Label("Worktree 恢复");
        title.getStyleClass().addAll("sec-title", "platform-page-title");
        Label hint = new Label("这里只管理平台为写型子 Thread 创建的隔离 Worktree；合并只能由父 Turn 的受治理 worktree_apply 工具审批执行。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        configureWorkspace();
        configureList();
        HBox filters = new HBox(10, workspace, includeCleaned);
        getChildren().addAll(title, hint, filters, worktrees);
        getStyleClass().add("platform-page");
    }

    private void configureWorkspace() {
        workspace.setMaxWidth(Double.MAX_VALUE);
        workspace.setAccessibleText("Worktree 所属 Workspace");
        workspace.setPromptText("选择 Workspace");
        workspace.setCellFactory(ignored -> components.detailCell(
                Workspace::name, value -> value.id().value() + " · revision " + value.revision()));
        workspace.setButtonCell(components.textCell(Workspace::name));
        workspace.valueProperty().addListener((observable, previous, selected) -> selectWorkspace(selected));
        includeCleaned.setAccessibleText("显示已清理 Worktree 历史");
        includeCleaned.selectedProperty().addListener((observable, previous, selected) -> includeCleaned(selected));
    }

    private void configureList() {
        worktrees
                .list()
                .setCellFactory(ignored -> components.detailCell(
                        value -> value.state() + " · " + value.childThreadId(),
                        value -> "父 " + value.parentThreadId() + " · revision " + value.revision()));
        worktrees
                .list()
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previous, selected) -> selectWorktree(selected));
        worktrees
                .list()
                .setPlaceholder(
                        components.feedback(FeedbackKind.EMPTY, "暂无受管 Worktree", "写型子 Thread 启动后会由平台自动创建隔离 Worktree。"));
        worktrees.showDetail(components.feedback(FeedbackKind.EMPTY, "选择 Worktree", "选择左侧记录查看父子任务、Artifact 与恢复动作。"));
    }

    private Node worktreeDetail() {
        FormSection snapshot = new FormSection("受管快照", "不显示宿主绝对路径，也不提供任意 create/delete 或直接合并入口。");
        snapshot.addField("Worktree ID", worktreeId);
        snapshot.addField("状态", worktreeState);
        snapshot.addField("父 Thread", parentThread);
        snapshot.addField("子 Thread", childThread);
        snapshot.addField("Base commit", baseCommit);
        snapshot.addField("Revision", revision);
        snapshot.addField("已验证 Backup", backupRef);
        snapshot.addField("更新时间", updatedAt);
        snapshot.addFullWidth(new HBox(8, openParent, openChild));
        FormSection artifact = new FormSection("最近 Artifact", "Patch 和 Backup 都是内容寻址 Attachment；页面不返回服务端路径。");
        artifact.addField("类型", artifactKind);
        artifact.addField("Digest", artifactDigest);
        artifact.addField("文件名", artifactName);
        artifact.addField("大小", artifactSize);
        VBox box = new VBox(12, snapshot, artifact, actions);
        box.getStyleClass().add("platform-page");
        return box;
    }

    private void selectWorkspace(Workspace selected) {
        if (!rendering && selected != null && !selected.equals(state.workspace().orElse(null))) {
            presenter.selectWorkspace(selected);
        }
    }

    private void includeCleaned(boolean selected) {
        if (!rendering) {
            presenter.includeCleaned(selected);
        }
    }

    private void selectWorktree(ManagedWorktree selected) {
        if (!rendering) {
            presenter.selectWorktree(selected);
        }
    }

    private void render(ManagedWorktreeSettingsState snapshot) {
        state = Objects.requireNonNull(snapshot, "snapshot");
        rendering = true;
        try {
            workspace.getItems().setAll(snapshot.workspaces());
            workspace.setValue(snapshot.workspace().orElse(null));
            includeCleaned.setSelected(snapshot.includeCleaned());
            worktrees.list().getItems().setAll(snapshot.worktrees());
            worktrees.list().getSelectionModel().select(snapshot.selected().orElse(null));
            renderSelected(snapshot.selected().orElse(null));
            renderArtifact(snapshot.artifact());
            renderActions(snapshot);
        } finally {
            rendering = false;
        }
    }

    private void renderSelected(ManagedWorktree selected) {
        if (selected == null) {
            worktrees.showDetail(
                    components.feedback(FeedbackKind.EMPTY, "选择 Worktree", "选择左侧记录查看父子任务、Artifact 与恢复动作。"));
            return;
        }
        if (detail == null) {
            detail = worktreeDetail();
        }
        worktrees.showDetail(detail);
        worktreeId.setText(selected.id().toString());
        worktreeState.setText(selected.state().name());
        parentThread.setText(selected.parentThreadId().toString());
        childThread.setText(selected.childThreadId().toString());
        baseCommit.setText(selected.baseCommit());
        revision.setText(Long.toString(selected.revision()));
        backupRef.setText(selected.backup()
                .map(value -> value.fileName() + " · " + value.digest())
                .orElse("尚未备份"));
        updatedAt.setText(selected.updatedAt().toString());
    }

    private void renderArtifact(Optional<ManagedWorktreeArtifact> artifact) {
        artifactKind.setText(artifact.map(value -> value.kind().name()).orElse("—"));
        artifactDigest.setText(
                artifact.map(value -> value.attachment().digest()).orElse("—"));
        artifactName.setText(
                artifact.map(value -> value.attachment().fileName()).orElse("—"));
        artifactSize.setText(
                artifact.map(value -> value.attachment().sizeBytes() + " bytes").orElse("—"));
    }

    private void renderActions(ManagedWorktreeSettingsState snapshot) {
        boolean pending = snapshot.phase() == SettingsLoadState.LOADING || snapshot.phase() == SettingsLoadState.SAVING;
        workspace.setDisable(pending);
        includeCleaned.setDisable(pending);
        updateActionAvailability(snapshot.selected(), pending);
        showActionStatus(snapshot, pending);
    }

    private void updateActionAvailability(Optional<ManagedWorktree> selected, boolean pending) {
        boolean hasSelection = selected.isPresent();
        boolean mayCapture = selected.map(value -> capturable(value.state())).orElse(false);
        boolean mayInterrupt =
                selected.map(value -> interruptible(value.state())).orElse(false);
        boolean mayCleanup = selected.filter(value -> capturable(value.state()))
                .flatMap(ManagedWorktree::backup)
                .isPresent();
        openParent.setDisable(pending || !hasSelection);
        openChild.setDisable(pending || !hasSelection);
        interrupt.setDisable(pending || !mayInterrupt);
        patch.setDisable(pending || !mayCapture);
        backup.setDisable(pending || !mayCapture);
        cleanup.setDisable(pending || !mayCleanup);
    }

    private void showActionStatus(ManagedWorktreeSettingsState snapshot, boolean pending) {
        if (pending) {
            actions.show(ActionState.PENDING, snapshot.message());
        } else if (snapshot.phase() == SettingsLoadState.ERROR) {
            actions.show(ActionState.ERROR, snapshot.message());
        } else {
            actions.show(snapshot.message().isBlank() ? ActionState.IDLE : ActionState.SUCCESS, snapshot.message());
        }
    }

    private void interrupt() {
        TextInputDialog dialog = new TextInputDialog();
        own(dialog);
        dialog.setTitle("中断写型子 Thread");
        dialog.setHeaderText("中断请求会持久化到活动 Turn");
        dialog.setContentText("脱敏原因：");
        dialog.showAndWait()
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .ifPresent(presenter::interrupt);
    }

    private void cleanup() {
        TextInputDialog dialog = new TextInputDialog();
        own(dialog);
        dialog.setTitle("永久 Cleanup Worktree");
        dialog.setHeaderText("只有已验证 Backup 且无活动执行时才能清理");
        dialog.setContentText("输入 " + WorktreeRpcContracts.CLEANUP_CONFIRMATION + "：");
        dialog.showAndWait()
                .filter(WorktreeRpcContracts.CLEANUP_CONFIRMATION::equals)
                .ifPresent(ignored -> presenter.cleanup());
    }

    private void own(TextInputDialog dialog) {
        if (getScene() != null && getScene().getWindow() != null) {
            dialog.initOwner(getScene().getWindow());
        }
    }

    private Button action(String text, ActionStyle style, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button button = components.action(text, style, ActionSize.NORMAL);
        button.setOnAction(action);
        return button;
    }

    private static boolean interruptible(ManagedWorktreeState state) {
        return state == ManagedWorktreeState.READY || state == ManagedWorktreeState.RUNNING;
    }

    private static boolean capturable(ManagedWorktreeState state) {
        return switch (state) {
            case READY, INTERRUPTED, COMPLETED, FAILED, APPLIED -> true;
            case RUNNING, CONFLICTED, APPLYING, UNKNOWN_OUTCOME, CLEANING, CLEANED -> false;
        };
    }

    private static Label value() {
        Label label = new Label("—");
        label.setWrapText(true);
        label.getStyleClass().add("platform-detail-text");
        return label;
    }
}
