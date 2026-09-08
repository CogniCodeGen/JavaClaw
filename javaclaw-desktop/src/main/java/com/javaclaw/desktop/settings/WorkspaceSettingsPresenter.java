package com.javaclaw.desktop.settings;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;

/** 只管理 Workspace 登记；独立执行配置使用相同 SDK 边界的执行面板。 */
public final class WorkspaceSettingsPresenter {
    private final CoreSettingsGateway gateway;
    private Consumer<WorkspaceSettingsState> listener = ignored -> {};
    private WorkspaceSettingsState state = WorkspaceSettingsState.initial();
    private Optional<WorkspaceId> scope = Optional.empty();
    private boolean writing;
    private boolean refreshPending;

    /** @param gateway 强类型 SDK 设置边界 */
    public WorkspaceSettingsPresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /** @param value 状态监听器，立即收到当前快照 */
    public void subscribe(Consumer<WorkspaceSettingsState> value) {
        listener = Objects.requireNonNull(value, "value");
        listener.accept(state);
    }

    /** 读取当前固定 Workspace 的登记；有名称草稿时只更新目录，保留原始写入版本。 */
    public void reload() {
        if (writing) {
            refreshPending = true;
            return;
        }
        refreshPending = false;
        Optional<WorkspaceId> requestedScope = scope;
        long epoch = state.epoch() + 1;
        publish(copy(SettingsLoadState.LOADING, "正在读取工作区…", epoch));
        gateway.workspaces().whenComplete((catalog, failure) -> {
            if (epoch != state.epoch()) {
                return;
            }
            if (failure != null) {
                publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
                refreshAgain();
                return;
            }
            List<Workspace> workspaces = catalog.stream()
                    .filter(value -> requestedScope.map(value.id()::equals).orElse(true))
                    .sorted(Comparator.comparing(Workspace::name))
                    .toList();
            applyCatalog(workspaces, epoch);
            refreshAgain();
        });
    }

    /** 合并写入或读取期间收到的失效，结束后自动补读，不静默丢失刷新请求。 */
    public void refresh() {
        if (writing || state.phase() == SettingsLoadState.LOADING) {
            refreshPending = true;
            return;
        }
        reload();
    }

    /** @param workspace 管理中心固定作用域，缺失时禁止操作 */
    public void bindWorkspace(Optional<Workspace> workspace) {
        Optional<WorkspaceId> next = Objects.requireNonNull(workspace, "workspace").map(Workspace::id);
        boolean same = scope.equals(next) && state.selected().isPresent();
        scope = next;
        if (workspace.isPresent()) {
            if (same && (state.dirty() || writing)) {
                publish(new WorkspaceSettingsState(state.phase(), List.of(workspace.orElseThrow()), state.selected(),
                        state.draftName(), "工作区登记已更新；名称草稿及原版本已保留", state.epoch()));
            } else {
                select(workspace.orElseThrow());
            }
        } else {
            publish(new WorkspaceSettingsState(
                    SettingsLoadState.READY, List.of(), Optional.empty(), "", "当前没有可用工作区", state.epoch() + 1));
        }
    }

    /** @param workspace 当前目录的权威登记 */
    public void select(Workspace workspace) {
        Workspace checked = Objects.requireNonNull(workspace, "workspace");
        publish(new WorkspaceSettingsState(
                SettingsLoadState.READY,
                List.of(checked),
                Optional.of(checked),
                checked.name(),
                "",
                state.epoch() + 1));
    }

    /** @param name 新名称草稿 */
    public void editName(String name) {
        publish(new WorkspaceSettingsState(
                state.phase(), state.workspaces(), state.selected(), name, state.message(), state.epoch()));
    }

    /** 保存 Workspace 名称，根目录始终只读。 */
    public void saveName() {
        Workspace current = state.selected().orElseThrow();
        String name = state.draftName().strip();
        if (name.isEmpty()) {
            publish(copy(SettingsLoadState.ERROR, "工作区名称不能为空", state.epoch()));
            return;
        }
        execute(gateway.renameWorkspace(current, name, CommandOptions.create(current.revision())), "工作区已重命名");
    }

    /** 归档登记，不删除用户根目录。 */
    public void archive() {
        Workspace current = state.selected().orElseThrow();
        execute(gateway.archiveWorkspace(current, CommandOptions.create(current.revision())), "工作区已归档");
    }

    /** 恢复最近读取的 Workspace 名称。 */
    public void discardDraft() {
        publish(new WorkspaceSettingsState(
                state.phase(),
                state.workspaces(),
                state.selected(),
                state.selected().map(Workspace::name).orElse(""),
                "",
                state.epoch()));
        refresh();
    }

    private void execute(CompletionStage<Workspace> operation, String success) {
        long epoch = state.epoch() + 1;
        writing = true;
        publish(copy(SettingsLoadState.LOADING, "正在保存…", epoch));
        operation.whenComplete((saved, failure) -> {
            if (epoch != state.epoch()) {
                return;
            }
            writing = false;
            if (failure != null) {
                publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
            } else {
                publish(new WorkspaceSettingsState(SettingsLoadState.READY, List.of(saved), Optional.of(saved),
                        saved.name(), success, epoch));
            }
            refreshAgain();
        });
    }

    private void applyCatalog(List<Workspace> workspaces, long epoch) {
        Optional<Workspace> remote = state.selected()
                .flatMap(previous -> workspaces.stream().filter(value -> value.id().equals(previous.id())).findFirst())
                .or(() -> state.selected().isEmpty() ? workspaces.stream().findFirst() : Optional.empty());
        boolean preserve = state.dirty();
        Optional<Workspace> selected = preserve ? state.selected() : remote.or(() -> state.selected());
        String message = preserve && !remote.equals(state.selected())
                ? "工作区登记已更新；名称草稿及原版本已保留" : "";
        publish(new WorkspaceSettingsState(SettingsLoadState.READY, workspaces, selected,
                preserve ? state.draftName() : selected.map(Workspace::name).orElse(""), message, epoch));
    }

    private void refreshAgain() {
        if (refreshPending) {
            reload();
        }
    }

    private WorkspaceSettingsState copy(SettingsLoadState phase, String message, long epoch) {
        return new WorkspaceSettingsState(
                phase, state.workspaces(), state.selected(), state.draftName(), message, epoch);
    }

    private void publish(WorkspaceSettingsState next) {
        state = next;
        listener.accept(next);
    }
}
