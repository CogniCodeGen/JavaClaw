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

    /** @param gateway 强类型 SDK 设置边界 */
    public WorkspaceSettingsPresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /** @param value 状态监听器，立即收到当前快照 */
    public void subscribe(Consumer<WorkspaceSettingsState> value) {
        listener = Objects.requireNonNull(value, "value");
        listener.accept(state);
    }

    /** 读取当前固定 Workspace 的登记。 */
    public void reload() {
        long epoch = state.epoch() + 1;
        publish(copy(SettingsLoadState.LOADING, "正在读取工作区…", epoch));
        gateway.workspaces().whenComplete((catalog, failure) -> {
            if (epoch != state.epoch()) {
                return;
            }
            if (failure != null) {
                publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
                return;
            }
            List<Workspace> workspaces = catalog.stream()
                    .filter(value -> scope.map(value.id()::equals).orElse(true))
                    .sorted(Comparator.comparing(Workspace::name))
                    .toList();
            Optional<Workspace> selected = state.selected()
                    .flatMap(previous -> workspaces.stream()
                            .filter(value -> value.id().equals(previous.id()))
                            .findFirst())
                    .or(() -> workspaces.stream().findFirst());
            publish(new WorkspaceSettingsState(
                    SettingsLoadState.READY,
                    workspaces,
                    selected,
                    selected.map(Workspace::name).orElse(""),
                    "",
                    epoch));
        });
    }

    /** @param workspace 管理中心固定作用域，缺失时禁止操作 */
    public void bindWorkspace(Optional<Workspace> workspace) {
        scope = Objects.requireNonNull(workspace, "workspace").map(Workspace::id);
        if (workspace.isPresent()) {
            select(workspace.orElseThrow());
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
                state.workspaces(),
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
    }

    private void execute(CompletionStage<?> operation, String success) {
        long epoch = state.epoch() + 1;
        publish(copy(SettingsLoadState.LOADING, "正在保存…", epoch));
        operation.whenComplete((ignored, failure) -> {
            if (epoch != state.epoch()) {
                return;
            }
            if (failure != null) {
                publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
            } else {
                publish(copy(SettingsLoadState.READY, success, epoch));
                reload();
            }
        });
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
