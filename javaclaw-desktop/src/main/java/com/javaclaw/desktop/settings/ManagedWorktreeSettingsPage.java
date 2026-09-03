package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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
import com.javaclaw.desktop.component.PlatformDialogs;
import com.javaclaw.protocol.WorktreeRpcContracts;

/** 父子对话受管隔离工作区的恢复与危险清理页面。 */
public final class ManagedWorktreeSettingsPage extends VBox implements ManagedSettingsPage {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final ManagedWorktreeSettingsPresenter presenter;
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
    private Optional<Workspace> scopedWorkspace = Optional.empty();
    private boolean rendering;

    /** @param gateway 强类型 SDK 设置边界 */
    public ManagedWorktreeSettingsPage(CoreSettingsGateway gateway) {
        presenter = new ManagedWorktreeSettingsPresenter(gateway);
        openParent = action("打开父对话", ActionStyle.GHOST, event -> presenter.navigate(true));
        openChild = action("打开子对话", ActionStyle.GHOST, event -> presenter.navigate(false));
        interrupt = action("中断子任务", ActionStyle.DANGER, event -> interrupt());
        patch = action("生成补丁附件", ActionStyle.SOFT, event -> presenter.exportPatch());
        backup = action("生成验证备份", ActionStyle.SOFT, event -> presenter.backup());
        cleanup = action("危险清理", ActionStyle.DANGER, event -> cleanup());
        actions = new AsyncActionBar(interrupt, patch, backup, cleanup);
        configurePage();
        presenter.subscribe(this::render);
    }

    @Override
    public Node content() {
        return this;
    }

    @Override
    public Optional<Node> actionContent() {
        return Optional.of(actions);
    }

    @Override
    public void activate() {
        scopedWorkspace.ifPresent(presenter::selectWorkspace);
    }

    @Override
    public boolean dirty() {
        return false;
    }

    @Override
    public boolean pending() {
        return state.phase() == SettingsLoadState.LOADING || state.phase() == SettingsLoadState.SAVING;
    }

    @Override
    public void workspaceChanged(Optional<Workspace> workspace) {
        Optional<Workspace> checked = Objects.requireNonNull(workspace, "workspace");
        if (scopedWorkspace.equals(checked)) {
            return;
        }
        scopedWorkspace = checked;
        checked.ifPresentOrElse(presenter::selectWorkspace, presenter::invalidateWorkspace);
        renderActions(state);
    }

    @Override
    public void warnUnsavedChanges() {}

    @Override
    public void discardDraft() {}

    private void configurePage() {
        Label title = new Label("隔离工作区恢复");
        title.getStyleClass().addAll("sec-title", "platform-page-title");
        Label hint = new Label("这里只管理平台为可写子对话创建的隔离工作区；合并必须由父任务中受审批的补丁应用工具执行。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        includeCleaned.setAccessibleText("显示已清理隔离工作区历史");
        includeCleaned.selectedProperty().addListener((observable, previous, selected) -> includeCleaned(selected));
        configureList();
        getChildren().addAll(title, hint, includeCleaned, worktrees);
        getStyleClass().add("platform-page");
    }

    private void configureList() {
        worktrees
                .list()
                .setCellFactory(ignored -> components.detailCell(
                        value -> SettingsLabels.managedWorktreeState(value.state()) + " · " + value.childThreadId(),
                        value -> "父 " + value.parentThreadId() + " · 版本 " + value.revision()));
        worktrees
                .list()
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previous, selected) -> selectWorktree(selected));
        worktrees.list().setPlaceholder(components.feedback(FeedbackKind.EMPTY, "暂无受管隔离工作区", "可写子对话启动后，平台会自动创建隔离工作区。"));
        worktrees.showDetail(components.feedback(FeedbackKind.EMPTY, "选择隔离工作区", "选择左侧记录查看父子任务、附件与恢复操作。"));
    }

    private Node worktreeDetail() {
        FormSection snapshot = new FormSection("受管快照", "不显示主机绝对路径，也不提供任意创建、删除或直接合并入口。");
        snapshot.addField("隔离工作区标识", worktreeId);
        snapshot.addField("状态", worktreeState);
        snapshot.addField("父对话", parentThread);
        snapshot.addField("子对话", childThread);
        snapshot.addField("起始提交", baseCommit);
        snapshot.addField("版本", revision);
        snapshot.addField("已验证备份", backupRef);
        snapshot.addField("更新时间", updatedAt);
        snapshot.addFullWidth(new HBox(8, openParent, openChild));
        FormSection artifact = new FormSection("最近生成的附件", "补丁和备份都使用内容指纹定位；页面不显示服务端路径。");
        artifact.addField("类型", artifactKind);
        artifact.addField("内容指纹", artifactDigest);
        artifact.addField("文件名", artifactName);
        artifact.addField("大小", artifactSize);
        VBox box = new VBox(12, snapshot, artifact);
        box.getStyleClass().add("platform-page");
        return box;
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
            worktrees.showDetail(components.feedback(FeedbackKind.EMPTY, "选择隔离工作区", "选择左侧记录查看父子任务、附件与恢复操作。"));
            return;
        }
        if (detail == null) {
            detail = worktreeDetail();
        }
        worktrees.showDetail(detail);
        worktreeId.setText(selected.id().toString());
        worktreeState.setText(SettingsLabels.managedWorktreeState(selected.state()));
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
        artifactKind.setText(artifact.map(value -> SettingsLabels.managedWorktreeArtifactKind(value.kind()))
                .orElse("—"));
        artifactDigest.setText(
                artifact.map(value -> value.attachment().digest()).orElse("—"));
        artifactName.setText(
                artifact.map(value -> value.attachment().fileName()).orElse("—"));
        artifactSize.setText(
                artifact.map(value -> value.attachment().sizeBytes() + " 字节").orElse("—"));
    }

    private void renderActions(ManagedWorktreeSettingsState snapshot) {
        boolean pending = snapshot.phase() == SettingsLoadState.LOADING || snapshot.phase() == SettingsLoadState.SAVING;
        includeCleaned.setDisable(pending);
        updateActionAvailability(snapshot.selected(), pending || scopedWorkspace.isEmpty());
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
        TextInputDialog dialog = PlatformDialogs.requiredText(
                this, "中断写型子对话", "中断请求会持久化到活动任务", "请填写不含敏感信息的中断原因，便于后续审计和恢复任务。", "例如：需要调整实现方向", "", "中断任务");
        dialog.showAndWait()
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .ifPresent(presenter::interrupt);
    }

    private void cleanup() {
        TextInputDialog dialog = PlatformDialogs.exactText(
                this, "永久清理隔离工作区", "只有已验证备份且没有活动任务时才能清理", WorktreeRpcContracts.CLEANUP_CONFIRMATION, "永久清理");
        dialog.showAndWait()
                .filter(WorktreeRpcContracts.CLEANUP_CONFIRMATION::equals)
                .ifPresent(ignored -> presenter.cleanup());
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
