package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import com.javaclaw.api.InstructionResolution;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceInstructionSettings;
import com.javaclaw.client.CommandOptions;

/** 项目约定页面异步状态机；只处理脱敏结果和资源标识。 */
public final class InstructionSettingsPresenter {
    private final InstructionSettingsGateway gateway;
    private Consumer<InstructionSettingsState> listener = ignored -> {};
    private InstructionSettingsState state = InstructionSettingsState.initial();

    /** @param gateway 项目约定 SDK 边界 */
    public InstructionSettingsPresenter(InstructionSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /** @param value 完整状态订阅者；注册后立即收到当前快照 */
    public void subscribe(Consumer<InstructionSettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** 重新读取 Workspace 目录与首个 Workspace 根的解析结果。 */
    public void reload() {
        long epoch = nextEpoch();
        publish(new InstructionSettingsState(
                SettingsLoadState.LOADING,
                state.workspaces(),
                state.workspace(),
                state.worktrees(),
                state.worktree(),
                state.settings(),
                state.fallbackDraft(),
                state.resolution(),
                new InstructionSettingsState.Feedback("正在读取项目约定来源…", epoch)));
        gateway.workspaces()
                .whenComplete((workspaces, failure) -> completeWorkspaceCatalog(epoch, workspaces, failure));
    }

    /** @param workspace 新的 Workspace；立即切回其根 execution scope */
    public void chooseWorkspace(Workspace workspace) {
        loadWorkspace(Objects.requireNonNull(workspace, "workspace"), nextEpoch());
    }

    /** @param fallbackBasename 安全 basename 草稿；空白表示关闭 fallback */
    public void editFallback(String fallbackBasename) {
        publish(new InstructionSettingsState(
                state.phase(),
                state.workspaces(),
                state.workspace(),
                state.worktrees(),
                state.worktree(),
                state.settings(),
                Objects.requireNonNullElse(fallbackBasename, ""),
                state.resolution(),
                state.feedback()));
    }

    /** 保存 fallback 设置并按新 revision 重新解析当前 Workspace。 */
    public void saveFallback() {
        Workspace workspace = state.workspace().orElseThrow(() -> new IllegalStateException("请先选择 Workspace"));
        WorkspaceInstructionSettings settings = state.settings().orElseThrow();
        String draft = state.fallbackDraft().strip();
        if (!draft.isEmpty() && !draft.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) {
            publish(copy(
                    SettingsLoadState.ERROR,
                    "备用文件名只能使用字母、数字、点、下划线和连字符",
                    state.feedback().epoch()));
            return;
        }
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.SAVING, "正在保存项目约定设置…", epoch));
        gateway.updateInstructionSettings(
                        workspace.id(),
                        draft.isEmpty() ? Optional.empty() : Optional.of(draft),
                        CommandOptions.create(settings.revision()))
                .whenComplete((ignored, failure) -> completeSave(epoch, workspace, failure));
    }

    /** 丢弃 fallback 草稿并恢复权威值。 */
    public void discardDraft() {
        String persisted = state.settings()
                .flatMap(WorkspaceInstructionSettings::fallbackBasename)
                .orElse("");
        publish(new InstructionSettingsState(
                state.phase(),
                state.workspaces(),
                state.workspace(),
                state.worktrees(),
                state.worktree(),
                state.settings(),
                persisted,
                state.resolution(),
                state.feedback()));
    }

    /**
     * 选择 Workspace 根或受管 Worktree 作为 execution root。
     *
     * @param worktree 可选 Worktree
     */
    public void chooseWorktree(Optional<ManagedWorktree> worktree) {
        Workspace workspace = state.workspace().orElseThrow(() -> new IllegalStateException("请先选择 Workspace"));
        Optional<ManagedWorktree> checked = Objects.requireNonNull(worktree, "worktree");
        checked.ifPresent(value -> {
            if (!value.workspaceId().equals(workspace.id())
                    || !state.worktrees().contains(value)) {
                throw new IllegalArgumentException("Managed Worktree 不属于当前 Workspace 目录");
            }
        });
        loadResolution(workspace, checked, nextEpoch());
    }

    /** @return 当前不可变状态 */
    public InstructionSettingsState state() {
        return state;
    }

    private void completeWorkspaceCatalog(long epoch, List<Workspace> workspaces, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        List<Workspace> catalog = List.copyOf(workspaces);
        Optional<Workspace> first = catalog.stream().findFirst();
        publish(new InstructionSettingsState(
                SettingsLoadState.READY,
                catalog,
                first,
                List.of(),
                Optional.empty(),
                Optional.empty(),
                "",
                Optional.empty(),
                new InstructionSettingsState.Feedback(first.isEmpty() ? "暂无 Workspace" : "", epoch)));
        first.ifPresent(workspace -> loadWorkspace(workspace, nextEpoch()));
    }

    private void loadWorkspace(Workspace workspace, long epoch) {
        publish(new InstructionSettingsState(
                SettingsLoadState.LOADING,
                state.workspaces(),
                Optional.of(workspace),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                "",
                Optional.empty(),
                new InstructionSettingsState.Feedback("正在解析 Workspace 根项目约定…", epoch)));
        CompletionStage<List<ManagedWorktree>> worktrees = gateway.managedWorktrees(workspace.id(), false);
        CompletionStage<WorkspaceInstructionSettings> settings = gateway.instructionSettings(workspace.id());
        CompletionStage<InstructionResolution> resolution =
                gateway.instructionResolution(workspace.id(), Optional.empty());
        worktrees
                .thenCombine(settings, WorkspaceContext::new)
                .thenCombine(resolution, WorkspaceResolution::new)
                .whenComplete((result, failure) -> completeWorkspace(epoch, result, failure));
    }

    private void completeWorkspace(long epoch, WorkspaceResolution result, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        publish(new InstructionSettingsState(
                SettingsLoadState.READY,
                state.workspaces(),
                state.workspace(),
                result.worktrees(),
                Optional.empty(),
                Optional.of(result.settings()),
                result.settings().fallbackBasename().orElse(""),
                Optional.of(result.resolution()),
                new InstructionSettingsState.Feedback(summary(result.resolution()), epoch)));
    }

    private void loadResolution(Workspace workspace, Optional<ManagedWorktree> worktree, long epoch) {
        publish(new InstructionSettingsState(
                SettingsLoadState.LOADING,
                state.workspaces(),
                Optional.of(workspace),
                state.worktrees(),
                worktree,
                state.settings(),
                state.fallbackDraft(),
                state.resolution(),
                new InstructionSettingsState.Feedback("正在解析所选 execution root…", epoch)));
        gateway.instructionResolution(workspace.id(), worktree.map(ManagedWorktree::id))
                .whenComplete((resolution, failure) -> completeResolution(epoch, resolution, failure));
    }

    private void completeResolution(long epoch, InstructionResolution resolution, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        publish(new InstructionSettingsState(
                SettingsLoadState.READY,
                state.workspaces(),
                state.workspace(),
                state.worktrees(),
                state.worktree(),
                state.settings(),
                state.fallbackDraft(),
                Optional.of(resolution),
                new InstructionSettingsState.Feedback(summary(resolution), epoch)));
    }

    private void fail(long epoch, Throwable failure) {
        publish(new InstructionSettingsState(
                SettingsLoadState.ERROR,
                state.workspaces(),
                state.workspace(),
                state.worktrees(),
                state.worktree(),
                state.settings(),
                state.fallbackDraft(),
                state.resolution(),
                new InstructionSettingsState.Feedback(SettingsFailures.message(failure), epoch)));
    }

    private void completeSave(long epoch, Workspace workspace, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        loadWorkspace(workspace, nextEpoch());
    }

    private InstructionSettingsState copy(SettingsLoadState phase, String message, long epoch) {
        return new InstructionSettingsState(
                phase,
                state.workspaces(),
                state.workspace(),
                state.worktrees(),
                state.worktree(),
                state.settings(),
                state.fallbackDraft(),
                state.resolution(),
                new InstructionSettingsState.Feedback(message, epoch));
    }

    private long nextEpoch() {
        return state.feedback().epoch() + 1;
    }

    private boolean stale(long epoch) {
        return epoch != state.feedback().epoch();
    }

    private void publish(InstructionSettingsState next) {
        state = Objects.requireNonNull(next, "next");
        listener.accept(state);
    }

    private static String summary(InstructionResolution resolution) {
        return resolution.sources().isEmpty()
                ? "当前 execution root 未发现项目约定文件"
                : "已解析 " + resolution.sources().size() + " 个来源；变更只影响下一 Turn";
    }

    private record WorkspaceContext(List<ManagedWorktree> worktrees, WorkspaceInstructionSettings settings) {
        private WorkspaceContext {
            worktrees = List.copyOf(worktrees);
            Objects.requireNonNull(settings, "settings");
        }
    }

    private record WorkspaceResolution(
            List<ManagedWorktree> worktrees, WorkspaceInstructionSettings settings, InstructionResolution resolution) {
        private WorkspaceResolution(WorkspaceContext context, InstructionResolution resolution) {
            this(context.worktrees(), context.settings(), resolution);
        }

        private WorkspaceResolution {
            worktrees = List.copyOf(worktrees);
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(resolution, "resolution");
        }
    }
}
