package com.javaclaw.desktop.settings;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.PromptManifestPreview;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceLifecycle;

/** Prompt provenance 面板的异步状态机；请求返回前切换选择会使旧响应失效。 */
public final class PromptPreviewSettingsPresenter {
    private final PromptPreviewSettingsGateway gateway;
    private Consumer<PromptPreviewSettingsState> listener = ignored -> {};
    private PromptPreviewSettingsState state = PromptPreviewSettingsState.initial();

    /**
     * 创建 Presenter。
     *
     * @param gateway SDK 异步边界
     */
    public PromptPreviewSettingsPresenter(PromptPreviewSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /**
     * 订阅完整状态并立即收到当前快照。
     *
     * @param value 页面渲染回调
     */
    public void subscribe(Consumer<PromptPreviewSettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** 读取可选择的 Workspace；只保留活动登记。 */
    public void reloadWorkspaces() {
        long epoch = state.epoch() + 1;
        publish(new PromptPreviewSettingsState(
                SettingsLoadState.LOADING,
                state.workspaces(),
                state.workspace(),
                state.profile(),
                Optional.empty(),
                "正在读取 Workspace…",
                epoch));
        gateway.workspaces().whenComplete((workspaces, failure) -> completeWorkspaces(epoch, workspaces, failure));
    }

    /**
     * 选择用于解析项目约定的 Workspace。
     *
     * @param workspace Workspace；清除选择时为空
     */
    public void selectWorkspace(Workspace workspace) {
        publish(new PromptPreviewSettingsState(
                SettingsLoadState.READY,
                state.workspaces(),
                Optional.ofNullable(workspace),
                state.profile(),
                Optional.empty(),
                "",
                state.epoch() + 1));
    }

    /**
     * 设置当前 Profile；新建草稿或归档 Profile 不允许预览。
     *
     * @param profile 当前权威 Profile；没有权威版本时为空
     */
    public void selectProfile(Optional<AgentProfile> profile) {
        Optional<AgentProfile> checked = Objects.requireNonNull(profile, "profile");
        if (state.profile().equals(checked)) {
            return;
        }
        publish(new PromptPreviewSettingsState(
                SettingsLoadState.READY,
                state.workspaces(),
                state.workspace(),
                checked,
                Optional.empty(),
                "Profile 已变化，请重新预览",
                state.epoch() + 1));
    }

    /** 请求权威 Prompt provenance；项目约定、Skill 和 Context 正文不会进入结果。 */
    public void preview() {
        Workspace workspace = state.workspace().orElseThrow(() -> new IllegalStateException("请先选择 Workspace"));
        AgentProfile profile = state.profile().orElseThrow(() -> new IllegalStateException("请先保存并选择 Agent Profile"));
        long epoch = state.epoch() + 1;
        publish(new PromptPreviewSettingsState(
                SettingsLoadState.LOADING,
                state.workspaces(),
                state.workspace(),
                state.profile(),
                Optional.empty(),
                "正在生成下一 Turn 的 Prompt provenance…",
                epoch));
        AgentProfileRef reference = new AgentProfileRef(profile.id(), profile.revision());
        gateway.preview(workspace.id(), reference)
                .whenComplete((result, failure) -> completePreview(epoch, result, failure));
    }

    /** @return 当前不可变状态 */
    public PromptPreviewSettingsState state() {
        return state;
    }

    private void completeWorkspaces(long epoch, List<Workspace> workspaces, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publishFailure(epoch, failure);
            return;
        }
        List<Workspace> active = Objects.requireNonNull(workspaces, "workspaces").stream()
                .filter(workspace -> workspace.lifecycle() == WorkspaceLifecycle.ACTIVE)
                .sorted(Comparator.comparing(Workspace::name)
                        .thenComparing(workspace -> workspace.id().value()))
                .toList();
        Optional<Workspace> selected =
                retainSelection(active).or(() -> active.stream().findFirst());
        publish(new PromptPreviewSettingsState(
                SettingsLoadState.READY,
                active,
                selected,
                state.profile(),
                Optional.empty(),
                active.isEmpty() ? "尚未登记活动 Workspace" : "",
                epoch));
    }

    private void completePreview(long epoch, PromptManifestPreview preview, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publishFailure(epoch, failure);
            return;
        }
        publish(new PromptPreviewSettingsState(
                SettingsLoadState.READY,
                state.workspaces(),
                state.workspace(),
                state.profile(),
                Optional.of(Objects.requireNonNull(preview, "preview")),
                "Prompt provenance 已按当前 revision 生成",
                epoch));
    }

    private Optional<Workspace> retainSelection(List<Workspace> candidates) {
        return state.workspace()
                .flatMap(selected -> candidates.stream()
                        .filter(candidate -> candidate.id().equals(selected.id()))
                        .findFirst());
    }

    private void publishFailure(long epoch, Throwable failure) {
        publish(new PromptPreviewSettingsState(
                SettingsLoadState.ERROR,
                state.workspaces(),
                state.workspace(),
                state.profile(),
                Optional.empty(),
                SettingsFailures.message(failure),
                epoch));
    }

    private void publish(PromptPreviewSettingsState value) {
        state = Objects.requireNonNull(value, "value");
        listener.accept(state);
    }
}
