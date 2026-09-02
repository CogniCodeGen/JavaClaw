package com.javaclaw.desktop.settings;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeArtifact;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.Workspace;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.protocol.WorktreeRpcContracts;

/** 协调受管 Worktree 的只读恢复操作和主窗口 Thread 导航。 */
public final class ManagedWorktreeSettingsPresenter {
    private final CoreSettingsGateway gateway;
    private Consumer<ManagedWorktreeSettingsState> listener = ignored -> {};
    private ManagedWorktreeSettingsState state = ManagedWorktreeSettingsState.initial();

    /** @param gateway 强类型 SDK 设置边界 */
    public ManagedWorktreeSettingsPresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /** @param value 页面状态监听器 */
    public void subscribe(Consumer<ManagedWorktreeSettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** 重新读取 Workspace 并选择首个 Workspace。 */
    public void reload() {
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.LOADING, "正在读取 Workspace…", epoch));
        gateway.workspaces().whenComplete((workspaces, failure) -> applyWorkspaces(epoch, workspaces, failure));
    }

    /** @param workspace 要查看的 Workspace */
    public void selectWorkspace(Workspace workspace) {
        loadWorkspace(Objects.requireNonNull(workspace, "workspace"), state.includeCleaned(), Optional.empty(), "");
    }

    /** @param include 是否包含 CLEANED 历史 */
    public void includeCleaned(boolean include) {
        state.workspace()
                .ifPresentOrElse(
                        workspace -> loadWorkspace(workspace, include, Optional.empty(), ""),
                        () -> publish(new ManagedWorktreeSettingsState(
                                state.phase(),
                                state.workspaces(),
                                Optional.empty(),
                                include,
                                List.of(),
                                Optional.empty(),
                                Optional.empty(),
                                state.message(),
                                state.epoch())));
    }

    /** @param worktree 选中的 Worktree */
    public void selectWorktree(ManagedWorktree worktree) {
        publish(new ManagedWorktreeSettingsState(
                state.phase(),
                state.workspaces(),
                state.workspace(),
                state.includeCleaned(),
                state.worktrees(),
                Optional.ofNullable(worktree),
                state.artifact(),
                state.message(),
                state.epoch()));
    }

    /** @param reason 脱敏中断原因 */
    public void interrupt(String reason) {
        ManagedWorktree current = state.selected().orElseThrow();
        executeWorktree(
                gateway.interruptManagedWorktree(
                        current, Objects.requireNonNullElse(reason, ""), CommandOptions.create(current.revision())),
                "子 Thread 已请求中断");
    }

    /** 生成有界 Patch Attachment，不触发合并。 */
    public void exportPatch() {
        ManagedWorktree current = state.selected().orElseThrow();
        executeArtifact(
                gateway.exportManagedWorktreePatch(current, CommandOptions.create(current.revision())),
                "Patch Attachment 已生成");
    }

    /** 生成并回读验证 cleanup 所需 Backup Attachment。 */
    public void backup() {
        ManagedWorktree current = state.selected().orElseThrow();
        executeArtifact(
                gateway.backupManagedWorktree(current, CommandOptions.create(current.revision())),
                "Backup Attachment 已生成并验证");
    }

    /** 使用固定危险确认永久清理受管目录。 */
    public void cleanup() {
        ManagedWorktree current = state.selected().orElseThrow();
        executeWorktree(
                gateway.cleanupManagedWorktree(
                        current, WorktreeRpcContracts.CLEANUP_CONFIRMATION, CommandOptions.create(current.revision())),
                "受管 Worktree 已清理");
    }

    /** @param parent 为 true 导航父 Thread，否则导航子 Thread */
    public void navigate(boolean parent) {
        ManagedWorktree current = state.selected().orElseThrow();
        ThreadId target = parent ? current.parentThreadId() : current.childThreadId();
        executeNavigation(gateway.navigateToThread(target));
    }

    private void loadWorkspace(
            Workspace workspace,
            boolean includeCleaned,
            Optional<ManagedWorktreeArtifact> artifact,
            String successMessage) {
        long epoch = nextEpoch();
        publish(new ManagedWorktreeSettingsState(
                SettingsLoadState.LOADING,
                state.workspaces(),
                Optional.of(workspace),
                includeCleaned,
                state.worktrees(),
                state.selected(),
                artifact,
                successMessage.isBlank() ? "正在读取受管 Worktree…" : successMessage,
                epoch));
        gateway.managedWorktrees(workspace.id(), includeCleaned)
                .whenComplete(
                        (worktrees, failure) -> applyWorktrees(epoch, worktrees, artifact, successMessage, failure));
    }

    private void executeWorktree(CompletionStage<ManagedWorktree> operation, String success) {
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.SAVING, "正在执行恢复操作…", epoch));
        operation.whenComplete((worktree, failure) -> commandCompleted(epoch, Optional.empty(), success, failure));
    }

    private void executeArtifact(CompletionStage<ManagedWorktreeArtifact> operation, String success) {
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.SAVING, "正在生成并验证 Artifact…", epoch));
        operation.whenComplete(
                (artifact, failure) -> commandCompleted(epoch, Optional.ofNullable(artifact), success, failure));
    }

    private void executeNavigation(CompletionStage<ConversationThread> operation) {
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.SAVING, "正在主窗口打开 Thread…", epoch));
        operation.whenComplete((thread, failure) -> {
            if (epoch != state.epoch()) {
                return;
            }
            publish(copy(
                    failure == null ? SettingsLoadState.READY : SettingsLoadState.ERROR,
                    failure == null ? "已在主窗口打开 “" + thread.title() + "”" : SettingsFailures.message(failure),
                    epoch));
        });
    }

    private void commandCompleted(
            long epoch, Optional<ManagedWorktreeArtifact> artifact, String success, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
            return;
        }
        state.workspace().ifPresent(workspace -> loadWorkspace(workspace, state.includeCleaned(), artifact, success));
    }

    private void applyWorkspaces(long epoch, List<Workspace> workspaces, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
            return;
        }
        List<Workspace> sorted = workspaces.stream()
                .sorted(Comparator.comparing(Workspace::name))
                .toList();
        publish(new ManagedWorktreeSettingsState(
                SettingsLoadState.READY,
                sorted,
                Optional.empty(),
                state.includeCleaned(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                sorted.isEmpty() ? "暂无 Workspace" : "",
                epoch));
        sorted.stream().findFirst().ifPresent(this::selectWorkspace);
    }

    private void applyWorktrees(
            long epoch,
            List<ManagedWorktree> worktrees,
            Optional<ManagedWorktreeArtifact> artifact,
            String message,
            Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
            return;
        }
        List<ManagedWorktree> sorted = worktrees.stream()
                .sorted(Comparator.comparing(ManagedWorktree::updatedAt).reversed())
                .toList();
        Optional<ManagedWorktree> selected = selectAfterReload(sorted);
        publish(new ManagedWorktreeSettingsState(
                SettingsLoadState.READY,
                state.workspaces(),
                state.workspace(),
                state.includeCleaned(),
                sorted,
                selected,
                artifact,
                message,
                epoch));
    }

    private Optional<ManagedWorktree> selectAfterReload(List<ManagedWorktree> worktrees) {
        return state.selected()
                .flatMap(current -> worktrees.stream()
                        .filter(worktree -> worktree.id().equals(current.id()))
                        .findFirst())
                .or(() -> worktrees.stream().findFirst());
    }

    private ManagedWorktreeSettingsState copy(SettingsLoadState phase, String message, long epoch) {
        return new ManagedWorktreeSettingsState(
                phase,
                state.workspaces(),
                state.workspace(),
                state.includeCleaned(),
                state.worktrees(),
                state.selected(),
                state.artifact(),
                message,
                epoch);
    }

    private void publish(ManagedWorktreeSettingsState next) {
        state = Objects.requireNonNull(next, "next");
        listener.accept(next);
    }

    private long nextEpoch() {
        return state.epoch() + 1;
    }
}
