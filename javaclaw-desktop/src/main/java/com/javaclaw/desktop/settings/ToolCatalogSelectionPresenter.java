package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ToolCatalogQueryResult;
import com.javaclaw.api.WorkspaceId;

/** 读取固定 Workspace 与精确权限版本工具目录的异步状态机。 */
public final class ToolCatalogSelectionPresenter {
    private static final int RESULT_LIMIT = 100;

    private final CoreSettingsGateway gateway;
    private Consumer<ToolCatalogSelectionState> listener = ignored -> {};
    private ToolCatalogSelectionState state = ToolCatalogSelectionState.initial();

    /** @param gateway Java SDK 设置边界 */
    public ToolCatalogSelectionPresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /** @param value 完整状态监听器 */
    public void subscribe(Consumer<ToolCatalogSelectionState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /**
     * 绑定新的固定作用域；任何旧请求完成后都会因 epoch 不匹配而丢弃。
     *
     * @param workspaceId Workspace；空值关闭并清空目录
     * @param permissionProfile 精确权限版本；必须与 Workspace 同时存在
     * @param agentProfile 可选精确 Agent Profile
     */
    public void bind(
            Optional<WorkspaceId> workspaceId,
            Optional<PermissionProfileRef> permissionProfile,
            Optional<AgentProfileRef> agentProfile) {
        Optional<WorkspaceId> workspace = Objects.requireNonNull(workspaceId, "workspaceId");
        Optional<PermissionProfileRef> permission = Objects.requireNonNull(permissionProfile, "permissionProfile");
        Optional<AgentProfileRef> agent = Objects.requireNonNull(agentProfile, "agentProfile");
        if (sameBinding(workspace, permission, agent) && state.phase() != SettingsLoadState.ERROR) {
            return;
        }
        long epoch = state.epoch() + 1;
        if (workspace.isEmpty() || permission.isEmpty()) {
            publish(new ToolCatalogSelectionState(
                    SettingsLoadState.INITIAL,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    "",
                    "请选择有效工作区和权限版本",
                    epoch));
            return;
        }
        publish(new ToolCatalogSelectionState(
                SettingsLoadState.LOADING, workspace, permission, agent, Optional.empty(), "", "正在读取工具目录…", epoch));
        request(epoch, "");
    }

    /** @param query 名称、说明或标签查询词 */
    public void search(String query) {
        if (state.workspaceId().isEmpty() || state.permissionProfile().isEmpty()) {
            return;
        }
        String normalized = Objects.requireNonNullElse(query, "").strip();
        long epoch = state.epoch() + 1;
        publish(new ToolCatalogSelectionState(
                SettingsLoadState.LOADING,
                state.workspaceId(),
                state.permissionProfile(),
                state.agentProfile(),
                Optional.empty(),
                normalized,
                "正在筛选工具目录…",
                epoch));
        request(epoch, normalized);
    }

    /** @return 当前不可变状态 */
    public ToolCatalogSelectionState state() {
        return state;
    }

    private void request(long epoch, String query) {
        gateway.toolCatalog(
                        state.workspaceId().orElseThrow(),
                        state.permissionProfile().orElseThrow(),
                        state.agentProfile(),
                        query,
                        RESULT_LIMIT)
                .whenComplete((result, failure) -> complete(epoch, query, result, failure));
    }

    private void complete(long epoch, String query, ToolCatalogQueryResult result, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(new ToolCatalogSelectionState(
                    SettingsLoadState.ERROR,
                    state.workspaceId(),
                    state.permissionProfile(),
                    state.agentProfile(),
                    Optional.empty(),
                    query,
                    SettingsFailures.message(failure),
                    epoch));
            return;
        }
        ToolCatalogQueryResult checked = Objects.requireNonNull(result, "result");
        publish(new ToolCatalogSelectionState(
                SettingsLoadState.READY,
                state.workspaceId(),
                state.permissionProfile(),
                state.agentProfile(),
                Optional.of(checked),
                query,
                checked.tools().isEmpty() ? "没有匹配的工具" : "已读取 " + checked.tools().size() + " 个工具",
                epoch));
    }

    private boolean sameBinding(
            Optional<WorkspaceId> workspace,
            Optional<PermissionProfileRef> permission,
            Optional<AgentProfileRef> agent) {
        return state.workspaceId().equals(workspace)
                && state.permissionProfile().equals(permission)
                && state.agentProfile().equals(agent);
    }

    private void publish(ToolCatalogSelectionState next) {
        state = Objects.requireNonNull(next, "next");
        listener.accept(next);
    }
}
