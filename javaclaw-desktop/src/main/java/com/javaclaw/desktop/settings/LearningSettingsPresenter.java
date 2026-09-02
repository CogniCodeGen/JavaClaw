package com.javaclaw.desktop.settings;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.client.CommandOptions;

/** 协调 Workspace 级 Memory 学习策略、草稿和 revision 冲突。 */
public final class LearningSettingsPresenter {
    private final LearningSettingsGateway gateway;
    private Consumer<LearningSettingsState> listener = ignored -> {};
    private LearningSettingsState state = LearningSettingsState.initial();

    /** @param gateway 强类型 SDK 学习设置边界 */
    public LearningSettingsPresenter(LearningSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /** @param value 完整状态订阅者；注册后立即收到当前状态 */
    public void subscribe(Consumer<LearningSettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** 重新读取 Workspace 目录，并保留仍存在的当前选择。 */
    public void reload() {
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.LOADING, "正在读取学习策略…", epoch));
        gateway.workspaces().whenComplete((workspaces, failure) -> completeCatalog(epoch, workspaces, failure));
    }

    /** @param workspace 切换到该 Workspace 并读取权威策略 */
    public void select(Workspace workspace) {
        Workspace checked = Objects.requireNonNull(workspace, "workspace");
        if (!state.workspaces().contains(checked)) {
            throw new IllegalArgumentException("Workspace 不在当前目录中");
        }
        load(checked, nextEpoch());
    }

    /** @param policy 用户选择的学习策略 */
    public void edit(MemoryContracts.LearningPolicy policy) {
        publish(new LearningSettingsState(
                state.phase(),
                state.workspaces(),
                state.workspace(),
                state.saved(),
                Objects.requireNonNull(policy, "policy"),
                "学习策略草稿尚未保存",
                state.epoch()));
    }

    /** 条件保存草稿；revision 冲突时保留草稿供用户刷新比较。 */
    public void save() {
        Workspace workspace = state.workspace().orElseThrow(() -> new IllegalStateException("请先选择 Workspace"));
        MemoryContracts.LearningSettings current =
                state.saved().orElseThrow(() -> new IllegalStateException("学习策略尚未读取"));
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.SAVING, "正在保存学习策略…", epoch));
        gateway.update(workspace.id(), state.draft(), CommandOptions.create(current.revision()))
                .whenComplete((updated, failure) -> completeSave(epoch, updated, failure));
    }

    /** 丢弃草稿并恢复最近一次权威策略。 */
    public void discardDraft() {
        MemoryContracts.LearningPolicy policy = state.saved()
                .map(MemoryContracts.LearningSettings::policy)
                .orElse(MemoryContracts.LearningPolicy.SUGGEST);
        publish(new LearningSettingsState(
                state.phase(), state.workspaces(), state.workspace(), state.saved(), policy, "", state.epoch()));
    }

    /** @return 当前不可变状态 */
    public LearningSettingsState state() {
        return state;
    }

    private void completeCatalog(long epoch, List<Workspace> workspaces, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        List<Workspace> catalog = workspaces.stream()
                .sorted(Comparator.comparing(Workspace::name))
                .toList();
        Optional<Workspace> selected = selected(catalog);
        publish(new LearningSettingsState(
                SettingsLoadState.READY,
                catalog,
                selected,
                Optional.empty(),
                MemoryContracts.LearningPolicy.SUGGEST,
                selected.isEmpty() ? "暂无 Workspace" : "",
                epoch));
        selected.ifPresent(value -> load(value, nextEpoch()));
    }

    private void load(Workspace workspace, long epoch) {
        publish(new LearningSettingsState(
                SettingsLoadState.LOADING,
                state.workspaces(),
                Optional.of(workspace),
                Optional.empty(),
                MemoryContracts.LearningPolicy.SUGGEST,
                "正在读取 " + workspace.name() + " 的学习策略…",
                epoch));
        gateway.read(workspace.id()).whenComplete((settings, failure) -> completeLoad(epoch, settings, failure));
    }

    private void completeLoad(long epoch, MemoryContracts.LearningSettings settings, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        MemoryContracts.LearningSettings checked = Objects.requireNonNull(settings, "settings");
        publish(new LearningSettingsState(
                SettingsLoadState.READY,
                state.workspaces(),
                state.workspace(),
                Optional.of(checked),
                checked.policy(),
                "",
                epoch));
    }

    private void completeSave(long epoch, MemoryContracts.LearningSettings updated, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        MemoryContracts.LearningSettings checked = Objects.requireNonNull(updated, "updated");
        publish(new LearningSettingsState(
                SettingsLoadState.READY,
                state.workspaces(),
                state.workspace(),
                Optional.of(checked),
                checked.policy(),
                "学习策略已保存；只影响之后的学习候选",
                epoch));
    }

    private void fail(long epoch, Throwable failure) {
        publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
    }

    private Optional<Workspace> selected(List<Workspace> catalog) {
        return state.workspace()
                .flatMap(current -> catalog.stream()
                        .filter(candidate -> candidate.id().equals(current.id()))
                        .findFirst())
                .or(() -> catalog.stream().findFirst());
    }

    private LearningSettingsState copy(SettingsLoadState phase, String message, long epoch) {
        return new LearningSettingsState(
                phase, state.workspaces(), state.workspace(), state.saved(), state.draft(), message, epoch);
    }

    private long nextEpoch() {
        return state.epoch() + 1;
    }

    private boolean stale(long epoch) {
        return epoch != state.epoch();
    }

    private void publish(LearningSettingsState next) {
        state = Objects.requireNonNull(next, "next");
        listener.accept(state);
    }
}
