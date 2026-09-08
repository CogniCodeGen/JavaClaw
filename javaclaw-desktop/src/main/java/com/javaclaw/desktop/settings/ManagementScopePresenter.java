package com.javaclaw.desktop.settings;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;

/** 读取并固定设置中心 Workspace；不持有 JavaFX 控件。 */
final class ManagementScopePresenter {
    private final CoreSettingsGateway gateway;
    private final Supplier<Optional<WorkspaceId>> preferredWorkspace;
    private Consumer<ManagementScopeState> listener = ignored -> {};
    private ManagementScopeState state = ManagementScopeState.initial();
    private boolean refreshPending;

    ManagementScopePresenter(CoreSettingsGateway gateway, Supplier<Optional<WorkspaceId>> preferredWorkspace) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.preferredWorkspace = Objects.requireNonNull(preferredWorkspace, "preferredWorkspace");
    }

    void subscribe(Consumer<ManagementScopeState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    void reload() {
        refreshPending = false;
        long epoch = state.epoch() + 1;
        publish(new ManagementScopeState(
                SettingsLoadState.LOADING, state.workspaces(), state.selected(), "正在读取工作区…", epoch));
        gateway.workspaces().whenComplete((workspaces, failure) -> completeReload(epoch, workspaces, failure));
    }

    /** 合并在途自动刷新；当前读取结束后至少再取一次权威目录。 */
    void refresh() {
        if (state.loading()) {
            refreshPending = true;
            return;
        }
        reload();
    }

    void select(Workspace workspace) {
        Workspace requested = Objects.requireNonNull(workspace, "workspace");
        Workspace selected = state.workspaces().stream()
                .filter(candidate -> candidate.id().equals(requested.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("工作区已不在可用目录中"));
        publish(new ManagementScopeState(
                SettingsLoadState.READY,
                state.workspaces(),
                Optional.of(selected),
                "已固定到“" + selected.name() + "”",
                state.epoch() + 1));
    }

    ManagementScopeState state() {
        return state;
    }

    private void completeReload(long epoch, List<Workspace> workspaces, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(new ManagementScopeState(
                    SettingsLoadState.ERROR,
                    state.workspaces(),
                    state.selected(),
                    "工作区读取失败，已保留固定作用域并暂停写入：" + SettingsFailures.message(failure),
                    epoch));
            refreshAgain();
            return;
        }
        List<Workspace> active = Objects.requireNonNull(workspaces, "workspaces").stream()
                .filter(workspace -> workspace.lifecycle() == WorkspaceLifecycle.ACTIVE)
                .sorted(Comparator.comparing(Workspace::name)
                        .thenComparing(workspace -> workspace.id().value()))
                .toList();
        Optional<Workspace> selected = retainSelection(active);
        publish(new ManagementScopeState(
                SettingsLoadState.READY, active, selected, selectionMessage(active, selected), epoch));
        refreshAgain();
    }

    private void refreshAgain() {
        if (refreshPending) {
            reload();
        }
    }

    private Optional<Workspace> retainSelection(List<Workspace> candidates) {
        if (state.selected().isPresent()) {
            Workspace frozen = state.selected().orElseThrow();
            return candidates.stream()
                    .filter(candidate -> candidate.id().equals(frozen.id()))
                    .findFirst()
                    .or(() -> Optional.of(frozen));
        }
        Optional<WorkspaceId> preferred = preferredWorkspace.get();
        return preferred
                .flatMap(id -> candidates.stream()
                        .filter(candidate -> candidate.id().equals(id))
                        .findFirst())
                .or(() -> candidates.stream().findFirst());
    }

    private String selectionMessage(List<Workspace> candidates, Optional<Workspace> selected) {
        if (selected.isEmpty()) {
            return "尚无可用工作区";
        }
        Workspace frozen = selected.orElseThrow();
        boolean available =
                candidates.stream().anyMatch(candidate -> candidate.id().equals(frozen.id()));
        if (!available) {
            return "固定工作区已归档或不可用；草稿已保留，写操作已暂停";
        }
        Optional<WorkspaceId> currentMain = preferredWorkspace.get();
        if (currentMain.isPresent() && !currentMain.orElseThrow().equals(frozen.id())) {
            return "主窗口已切换；设置中心仍固定到“" + frozen.name() + "”";
        }
        return "";
    }

    private void publish(ManagementScopeState next) {
        state = next;
        listener.accept(next);
    }
}
