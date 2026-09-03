package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.Workspace;

/** 服务端五层有效权限预览 Presenter。 */
public final class PermissionPreviewPresenter {
    private final CoreSettingsGateway gateway;
    private Consumer<PermissionPreviewState> listener = ignored -> {};
    private PermissionPreviewState state = PermissionPreviewState.initial();

    /**
     * 创建 Presenter。
     *
     * @param gateway SDK 异步边界
     */
    public PermissionPreviewPresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /**
     * 订阅状态并立即收到当前快照。
     *
     * @param value 页面渲染回调
     */
    public void subscribe(Consumer<PermissionPreviewState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** 读取 Workspace 和可选窄化层目录。 */
    public void reloadWorkspaces() {
        long epoch = state.epoch() + 1;
        publish(new PermissionPreviewState(
                SettingsLoadState.LOADING,
                state.workspaces(),
                state.selectedWorkspace(),
                state.candidates(),
                state.turnGrant(),
                state.toolDeclaration(),
                state.preview(),
                "正在读取工作区…",
                epoch));
        gateway.workspaces()
                .thenCombine(gateway.permissionProfiles(), PreviewCatalog::new)
                .whenComplete((catalog, failure) -> completeCatalog(epoch, catalog, failure));
    }

    /**
     * 选择预览 Workspace。
     *
     * @param workspace Workspace 快照
     */
    public void selectWorkspace(Workspace workspace) {
        publish(new PermissionPreviewState(
                SettingsLoadState.READY,
                state.workspaces(),
                Optional.ofNullable(workspace),
                state.candidates(),
                state.turnGrant(),
                state.toolDeclaration(),
                Optional.empty(),
                "",
                state.epoch() + 1));
    }

    /**
     * 选择可选 Turn grant；空值表示该层未提供。
     *
     * @param profile 权限版本或空值
     */
    public void selectTurnGrant(PermissionProfile profile) {
        publishSelection(Optional.ofNullable(profile), state.toolDeclaration());
    }

    /**
     * 选择可选工具声明；空值表示该层未提供。
     *
     * @param profile 权限版本或空值
     */
    public void selectToolDeclaration(PermissionProfile profile) {
        publishSelection(state.turnGrant(), Optional.ofNullable(profile));
    }

    /**
     * 请求服务端计算五层有效权限。
     *
     * @param profile 当前权威 PermissionProfile
     */
    public void preview(PermissionProfile profile) {
        Workspace workspace = state.selectedWorkspace().orElseThrow(() -> new IllegalStateException("请先选择工作区"));
        PermissionProfile checked = Objects.requireNonNull(profile, "profile");
        long epoch = state.epoch() + 1;
        publish(new PermissionPreviewState(
                SettingsLoadState.LOADING,
                state.workspaces(),
                state.selectedWorkspace(),
                state.candidates(),
                state.turnGrant(),
                state.toolDeclaration(),
                state.preview(),
                "正在由服务端计算五层权限交集…",
                epoch));
        gateway.effectivePermissionPreview(
                        workspace.id(),
                        new PermissionProfileRef(checked.id(), checked.version()),
                        state.turnGrant(),
                        state.toolDeclaration())
                .whenComplete((preview, failure) -> {
                    if (epoch != state.epoch()) {
                        return;
                    }
                    if (failure != null) {
                        publish(new PermissionPreviewState(
                                SettingsLoadState.ERROR,
                                state.workspaces(),
                                state.selectedWorkspace(),
                                state.candidates(),
                                state.turnGrant(),
                                state.toolDeclaration(),
                                state.preview(),
                                SettingsFailures.message(failure),
                                epoch));
                    } else {
                        publish(new PermissionPreviewState(
                                SettingsLoadState.READY,
                                state.workspaces(),
                                state.selectedWorkspace(),
                                state.candidates(),
                                state.turnGrant(),
                                state.toolDeclaration(),
                                Optional.of(preview),
                                "权威预览已刷新",
                                epoch));
                    }
                });
    }

    private void completeCatalog(long epoch, PreviewCatalog response, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(new PermissionPreviewState(
                    SettingsLoadState.ERROR,
                    state.workspaces(),
                    state.selectedWorkspace(),
                    state.candidates(),
                    state.turnGrant(),
                    state.toolDeclaration(),
                    state.preview(),
                    SettingsFailures.message(failure),
                    epoch));
            return;
        }
        List<Workspace> workspaces = List.copyOf(response.workspaces());
        List<PermissionProfile> candidates = List.copyOf(response.candidates());
        publish(new PermissionPreviewState(
                SettingsLoadState.READY,
                workspaces,
                workspaces.stream().findFirst(),
                candidates,
                retain(state.turnGrant(), candidates),
                retain(state.toolDeclaration(), candidates),
                Optional.empty(),
                workspaces.isEmpty() ? "请先创建工作区，才能计算有效权限" : "",
                epoch));
    }

    private void publishSelection(Optional<PermissionProfile> turnGrant, Optional<PermissionProfile> toolDeclaration) {
        publish(new PermissionPreviewState(
                state.phase(),
                state.workspaces(),
                state.selectedWorkspace(),
                state.candidates(),
                turnGrant,
                toolDeclaration,
                Optional.empty(),
                "权限层已改变，请重新计算",
                state.epoch() + 1));
    }

    private static Optional<PermissionProfile> retain(
            Optional<PermissionProfile> selected, List<PermissionProfile> candidates) {
        return selected.flatMap(value -> candidates.stream()
                .filter(candidate -> candidate.id().equals(value.id()) && candidate.version() == value.version())
                .findFirst());
    }

    private void publish(PermissionPreviewState next) {
        state = next;
        listener.accept(next);
    }

    private record PreviewCatalog(List<Workspace> workspaces, List<PermissionProfile> candidates) {}
}
