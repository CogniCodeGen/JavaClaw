package com.javaclaw.desktop.settings;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.PromptOptimizationAdoption;
import com.javaclaw.api.PromptOptimizationDraft;
import com.javaclaw.api.PromptOptimizationState;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.protocol.PromptOptimizationRpcContracts;

/** Prompt 优化面板的异步状态机；所有旧响应按 epoch 丢弃。 */
public final class PromptOptimizationSettingsPresenter {
    private final PromptOptimizationSettingsGateway gateway;
    private final Runnable adoptedCallback;
    private Consumer<PromptOptimizationSettingsState> listener = ignored -> {};
    private PromptOptimizationSettingsState state = PromptOptimizationSettingsState.initial();

    /**
     * 创建 Presenter。
     *
     * @param gateway SDK 异步边界
     * @param adoptedCallback 成功采纳后刷新 Agent Profile 目录
     */
    public PromptOptimizationSettingsPresenter(PromptOptimizationSettingsGateway gateway, Runnable adoptedCallback) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.adoptedCallback = Objects.requireNonNull(adoptedCallback, "adoptedCallback");
    }

    /**
     * 订阅完整状态并立即收到当前快照。
     *
     * @param value 页面渲染回调
     */
    public void subscribe(Consumer<PromptOptimizationSettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** 读取活动 Workspace，并加载当前 Profile 的历史草稿。 */
    public void activate() {
        long epoch = nextEpoch();
        publish(state(SettingsLoadState.LOADING, state.selection(), "正在读取 Prompt 优化任务…", false, epoch));
        gateway.workspaces().whenComplete((workspaces, failure) -> completeWorkspaces(epoch, workspaces, failure));
    }

    /**
     * 设置 Agent Profile 页面当前权威版本。
     *
     * @param profile 已保存 Profile；新建草稿时为空
     */
    public void selectProfile(Optional<AgentProfile> profile) {
        Optional<AgentProfile> checked = Objects.requireNonNull(profile, "profile");
        if (state.selection().profile().equals(checked)) {
            return;
        }
        PromptOptimizationSelection selection = new PromptOptimizationSelection(
                state.selection().workspaces(), state.selection().workspace(), checked, List.of(), Optional.empty());
        publish(state(SettingsLoadState.READY, selection, "Profile 已变化，正在刷新草稿…", false, nextEpoch()));
        loadDrafts();
    }

    /**
     * 选择 Workspace 并刷新目录。
     *
     * @param workspace Workspace；清除选择时为空
     */
    public void selectWorkspace(Workspace workspace) {
        PromptOptimizationSelection selection = new PromptOptimizationSelection(
                state.selection().workspaces(),
                Optional.ofNullable(workspace),
                state.selection().profile(),
                List.of(),
                Optional.empty());
        publish(state(SettingsLoadState.READY, selection, "", false, nextEpoch()));
        loadDrafts();
    }

    /**
     * 选择草稿。
     *
     * @param draft 目录中的任务
     */
    public void selectDraft(PromptOptimizationDraft draft) {
        PromptOptimizationDraft checked = Objects.requireNonNull(draft, "draft");
        PromptOptimizationSelection selection = state.selection();
        if (!selection.drafts().contains(checked)) {
            throw new IllegalArgumentException("Prompt draft is not in current catalog");
        }
        publish(state(
                SettingsLoadState.READY,
                new PromptOptimizationSelection(
                        selection.workspaces(),
                        selection.workspace(),
                        selection.profile(),
                        selection.drafts(),
                        Optional.of(checked)),
                "",
                false,
                nextEpoch()));
    }

    /** 启动可能计费的普通 Harness Turn；确认文本必须由 UI 精确输入。 */
    public void start(boolean billingConfirmed, String confirmation) {
        if (!exactConfirmation(billingConfirmed, confirmation, PromptOptimizationRpcContracts.BILLING_CONFIRMATION)) {
            publish(state(SettingsLoadState.ERROR, state.selection(), "确认文本不匹配，未发送任何 Provider 请求", false, nextEpoch()));
            return;
        }
        Workspace workspace =
                state.selection().workspace().orElseThrow(() -> new IllegalStateException("请先选择 Workspace"));
        AgentProfile profile = requireActiveProfile();
        long epoch = nextEpoch();
        publish(state(SettingsLoadState.SAVING, state.selection(), "正在提交 Prompt 优化 Turn…", false, epoch));
        AgentProfileRef reference = new AgentProfileRef(profile.id(), profile.revision());
        gateway.start(workspace.id(), reference, true, confirmation, CommandOptions.create(0))
                .whenComplete((draft, failure) -> completeDraft(epoch, draft, failure, "Prompt 优化 Turn 已提交"));
    }

    /** 刷新当前草稿；没有选择时重新加载目录。 */
    public void refresh() {
        Optional<PromptOptimizationDraft> selected = state.selection().selected();
        if (selected.isEmpty()) {
            loadDrafts();
            return;
        }
        long epoch = nextEpoch();
        publish(state(SettingsLoadState.LOADING, state.selection(), "正在读取 Turn/Item 状态…", false, epoch));
        gateway.read(selected.orElseThrow().ref().id())
                .whenComplete((draft, failure) -> completeDraft(epoch, draft, failure, "Prompt 草稿已刷新"));
    }

    /** 取消当前 QUEUED/RUNNING 优化任务。 */
    public void cancel() {
        PromptOptimizationDraft draft = requireSelected();
        if (draft.result().state() != PromptOptimizationState.QUEUED
                && draft.result().state() != PromptOptimizationState.RUNNING) {
            throw new IllegalStateException("只有活动 Prompt 优化任务可以取消");
        }
        long epoch = nextEpoch();
        publish(state(SettingsLoadState.SAVING, state.selection(), "正在取消 Prompt 优化 Turn…", false, epoch));
        gateway.cancel(
                        draft.ref().id(),
                        "用户在设置中心取消 Prompt 优化",
                        CommandOptions.create(draft.result().turnRevision()))
                .whenComplete((result, failure) -> completeDraft(epoch, result, failure, "Prompt 优化已取消"));
    }

    /** 以源 Profile 精确 revision 人工采纳当前 READY 草稿。 */
    public void adopt(boolean adoptionConfirmed, String confirmation) {
        if (!exactConfirmation(adoptionConfirmed, confirmation, PromptOptimizationRpcContracts.ADOPTION_CONFIRMATION)) {
            publish(state(
                    SettingsLoadState.ERROR, state.selection(), "确认文本不匹配，草稿与 Agent Profile 均未改变", false, nextEpoch()));
            return;
        }
        PromptOptimizationDraft draft = requireSelected();
        if (draft.result().state() != PromptOptimizationState.READY
                || draft.adoptedProfile().isPresent()) {
            throw new IllegalStateException("只有尚未采纳的 READY Prompt 草稿可以采纳");
        }
        long epoch = nextEpoch();
        publish(state(SettingsLoadState.SAVING, state.selection(), "正在创建新的 Agent Profile revision…", false, epoch));
        gateway.adopt(
                        draft.ref().id(),
                        true,
                        confirmation,
                        CommandOptions.create(draft.ref().sourceProfile().revision()))
                .whenComplete((adoption, failure) -> completeAdoption(epoch, adoption, failure));
    }

    /** @return 当前不可变状态 */
    public PromptOptimizationSettingsState state() {
        return state;
    }

    private void loadDrafts() {
        Optional<Workspace> workspace = state.selection().workspace();
        if (workspace.isEmpty() || state.selection().profile().isEmpty()) {
            publish(state(
                    SettingsLoadState.READY, state.selection(), "请先保存 Profile 并选择 Workspace", false, nextEpoch()));
            return;
        }
        long epoch = nextEpoch();
        publish(state(SettingsLoadState.LOADING, state.selection(), "正在读取历史草稿…", false, epoch));
        gateway.list(workspace.orElseThrow().id())
                .whenComplete((drafts, failure) -> completeList(epoch, drafts, failure));
    }

    private void completeWorkspaces(long epoch, List<Workspace> workspaces, Throwable failure) {
        if (!current(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        List<Workspace> active = Objects.requireNonNull(workspaces, "workspaces").stream()
                .filter(workspace -> workspace.lifecycle() == WorkspaceLifecycle.ACTIVE)
                .sorted(Comparator.comparing(Workspace::name)
                        .thenComparing(value -> value.id().value()))
                .toList();
        Optional<Workspace> selected =
                retainWorkspace(active).or(() -> active.stream().findFirst());
        PromptOptimizationSelection selection = new PromptOptimizationSelection(
                active, selected, state.selection().profile(), List.of(), Optional.empty());
        publish(state(SettingsLoadState.READY, selection, active.isEmpty() ? "尚未登记活动 Workspace" : "", false, epoch));
        loadDrafts();
    }

    private void completeList(long epoch, List<PromptOptimizationDraft> drafts, Throwable failure) {
        if (!current(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        AgentProfile profile = state.selection().profile().orElseThrow();
        List<PromptOptimizationDraft> filtered = Objects.requireNonNull(drafts, "drafts").stream()
                .filter(draft -> draft.ref().sourceProfile().id().equals(profile.id()))
                .toList();
        Optional<PromptOptimizationDraft> selected =
                retainDraft(filtered).or(() -> filtered.stream().findFirst());
        PromptOptimizationSelection selection = new PromptOptimizationSelection(
                state.selection().workspaces(),
                state.selection().workspace(),
                state.selection().profile(),
                filtered,
                selected);
        publish(state(SettingsLoadState.READY, selection, filtered.isEmpty() ? "尚无 Prompt 优化草稿" : "", false, epoch));
    }

    private void completeDraft(long epoch, PromptOptimizationDraft draft, Throwable failure, String successMessage) {
        if (!current(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        PromptOptimizationDraft checked = Objects.requireNonNull(draft, "draft");
        List<PromptOptimizationDraft> drafts = replace(state.selection().drafts(), checked);
        PromptOptimizationSelection selection = new PromptOptimizationSelection(
                state.selection().workspaces(),
                state.selection().workspace(),
                state.selection().profile(),
                drafts,
                Optional.of(checked));
        publish(state(SettingsLoadState.READY, selection, successMessage, false, epoch));
    }

    private void completeAdoption(long epoch, PromptOptimizationAdoption adoption, Throwable failure) {
        if (!current(epoch)) {
            return;
        }
        if (failure != null) {
            publish(state(
                    SettingsLoadState.ERROR,
                    state.selection(),
                    SettingsFailures.message(failure),
                    SettingsFailures.revisionConflict(failure),
                    epoch));
            return;
        }
        completeDraft(
                epoch, Objects.requireNonNull(adoption, "adoption").draft(), null, "草稿已采纳为新的 Agent Profile revision");
        adoptedCallback.run();
    }

    private AgentProfile requireActiveProfile() {
        AgentProfile profile =
                state.selection().profile().orElseThrow(() -> new IllegalStateException("请先保存并选择 Agent Profile"));
        if (profile.lifecycle() != ProfileLifecycle.ACTIVE) {
            throw new IllegalStateException("只有 ACTIVE Agent Profile 可以启动优化");
        }
        return profile;
    }

    private PromptOptimizationDraft requireSelected() {
        return state.selection().selected().orElseThrow(() -> new IllegalStateException("请先选择 Prompt 优化任务"));
    }

    private Optional<Workspace> retainWorkspace(List<Workspace> candidates) {
        return state.selection()
                .workspace()
                .flatMap(selected -> candidates.stream()
                        .filter(candidate -> candidate.id().equals(selected.id()))
                        .findFirst());
    }

    private Optional<PromptOptimizationDraft> retainDraft(List<PromptOptimizationDraft> candidates) {
        return state.selection()
                .selected()
                .flatMap(selected -> candidates.stream()
                        .filter(candidate ->
                                candidate.ref().id().equals(selected.ref().id()))
                        .findFirst());
    }

    private static List<PromptOptimizationDraft> replace(
            List<PromptOptimizationDraft> current, PromptOptimizationDraft updated) {
        List<PromptOptimizationDraft> result = new java.util.ArrayList<>(current.size() + 1);
        result.add(updated);
        current.stream()
                .filter(candidate -> !candidate.ref().id().equals(updated.ref().id()))
                .forEach(result::add);
        return List.copyOf(result);
    }

    private void fail(long epoch, Throwable failure) {
        publish(state(SettingsLoadState.ERROR, state.selection(), SettingsFailures.message(failure), false, epoch));
    }

    private static boolean exactConfirmation(boolean confirmed, String actual, String expected) {
        return confirmed
                && expected.equals(Objects.requireNonNullElse(actual, "").strip());
    }

    private boolean current(long epoch) {
        return state.epoch() == epoch;
    }

    private long nextEpoch() {
        return state.epoch() + 1;
    }

    private void publish(PromptOptimizationSettingsState value) {
        state = Objects.requireNonNull(value, "value");
        listener.accept(state);
    }

    private static PromptOptimizationSettingsState state(
            SettingsLoadState phase,
            PromptOptimizationSelection selection,
            String message,
            boolean conflict,
            long epoch) {
        return new PromptOptimizationSettingsState(phase, selection, message, conflict, epoch);
    }
}
