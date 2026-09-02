package com.javaclaw.desktop.settings;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import com.javaclaw.api.PermissionDecisionTrace;
import com.javaclaw.api.SecurityGrantKind;
import com.javaclaw.api.UnattendedToolGrant;
import com.javaclaw.api.UnattendedToolGrantDraft;
import com.javaclaw.api.UnattendedToolGrantStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.protocol.CanonicalJson;

/** 协调 Schedule 无人值守授权的创建、余额、审计与实时撤销。 */
public final class UnattendedToolGrantSettingsPresenter {
    private final CoreSettingsGateway gateway;
    private final CanonicalJson json = new CanonicalJson();
    private Consumer<UnattendedToolGrantSettingsState> listener = ignored -> {};
    private UnattendedToolGrantSettingsState state = UnattendedToolGrantSettingsState.initial();

    /** @param gateway 强类型 SDK 设置边界 */
    public UnattendedToolGrantSettingsPresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /** @param value 页面状态监听器 */
    public void subscribe(Consumer<UnattendedToolGrantSettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** 重新读取 Workspace，并选择首个 Workspace。 */
    public void reload() {
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.LOADING, "正在读取 Workspace…", epoch));
        gateway.workspaces().whenComplete((workspaces, failure) -> applyWorkspaces(epoch, workspaces, failure));
    }

    /** @param workspace 要查看的 Workspace */
    public void selectWorkspace(Workspace workspace) {
        Workspace checked = Objects.requireNonNull(workspace, "workspace");
        long epoch = nextEpoch();
        publish(new UnattendedToolGrantSettingsState(
                SettingsLoadState.LOADING,
                state.workspaces(),
                Optional.of(checked),
                List.of(),
                Optional.empty(),
                UnattendedToolGrantForm.empty(),
                List.of(),
                "正在读取无人值守授权…",
                epoch));
        gateway.unattendedToolGrants(checked.id())
                .thenCombine(
                        gateway.permissionDecisions(
                                checked.id(), SecurityGrantKind.UNATTENDED_TOOL, Optional.empty(), 100),
                        GrantCatalog::new)
                .whenComplete((catalog, failure) -> applyCatalog(epoch, checked, catalog, failure));
    }

    /** @param selected 选中的授权与余额 */
    public void selectGrant(UnattendedToolGrantStatus selected) {
        publish(new UnattendedToolGrantSettingsState(
                state.phase(),
                state.workspaces(),
                state.workspace(),
                state.grants(),
                Optional.ofNullable(selected),
                state.draft(),
                state.decisions(),
                state.message(),
                state.epoch()));
    }

    /** @param draft 完整界面草稿 */
    public void edit(UnattendedToolGrantForm draft) {
        publish(new UnattendedToolGrantSettingsState(
                state.phase(),
                state.workspaces(),
                state.workspace(),
                state.grants(),
                state.selected(),
                Objects.requireNonNull(draft, "draft"),
                state.decisions(),
                "授权草稿尚未提交",
                state.epoch()));
    }

    /** 校验并创建严格绑定 Schedule、目录、Schema 与参数模板的授权。 */
    public void create() {
        Workspace workspace = state.workspace().orElseThrow();
        try {
            UnattendedToolGrantDraft draft = state.draft().toDraft(workspace.id(), json);
            execute(gateway.createUnattendedToolGrant(draft, CommandOptions.create(0)), "无人值守授权已创建");
        } catch (RuntimeException failure) {
            publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), state.epoch()));
        }
    }

    /** 不可逆撤销选中的授权。 */
    public void revoke() {
        UnattendedToolGrant grant = state.selected().orElseThrow().grant();
        execute(gateway.revokeUnattendedToolGrant(grant, CommandOptions.create(grant.revision())), "无人值守授权已撤销");
    }

    /** 丢弃尚未提交的授权草稿。 */
    public void discardDraft() {
        publish(new UnattendedToolGrantSettingsState(
                state.phase(),
                state.workspaces(),
                state.workspace(),
                state.grants(),
                state.selected(),
                UnattendedToolGrantForm.empty(),
                state.decisions(),
                "",
                state.epoch()));
    }

    private void execute(CompletionStage<?> operation, String success) {
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.SAVING, "正在提交…", epoch));
        operation.whenComplete((ignored, failure) -> {
            if (epoch != state.epoch()) {
                return;
            }
            if (failure != null) {
                publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
                return;
            }
            discardDraft();
            publish(copy(SettingsLoadState.READY, success, state.epoch()));
            state.workspace().ifPresent(this::selectWorkspace);
        });
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
        publish(new UnattendedToolGrantSettingsState(
                SettingsLoadState.READY,
                sorted,
                Optional.empty(),
                List.of(),
                Optional.empty(),
                UnattendedToolGrantForm.empty(),
                List.of(),
                sorted.isEmpty() ? "暂无 Workspace" : "",
                epoch));
        sorted.stream().findFirst().ifPresent(this::selectWorkspace);
    }

    private void applyCatalog(long epoch, Workspace workspace, GrantCatalog catalog, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
            return;
        }
        List<UnattendedToolGrantStatus> grants = catalog.grants().stream()
                .sorted(Comparator.comparing((UnattendedToolGrantStatus value) ->
                                value.grant().updatedAt())
                        .reversed())
                .toList();
        publish(new UnattendedToolGrantSettingsState(
                SettingsLoadState.READY,
                state.workspaces(),
                Optional.of(workspace),
                grants,
                grants.stream().findFirst(),
                UnattendedToolGrantForm.empty(),
                catalog.decisions(),
                "",
                epoch));
    }

    private UnattendedToolGrantSettingsState copy(SettingsLoadState phase, String message, long epoch) {
        return new UnattendedToolGrantSettingsState(
                phase,
                state.workspaces(),
                state.workspace(),
                state.grants(),
                state.selected(),
                state.draft(),
                state.decisions(),
                message,
                epoch);
    }

    private void publish(UnattendedToolGrantSettingsState next) {
        state = Objects.requireNonNull(next, "next");
        listener.accept(next);
    }

    private long nextEpoch() {
        return state.epoch() + 1;
    }

    private record GrantCatalog(List<UnattendedToolGrantStatus> grants, List<PermissionDecisionTrace> decisions) {
        private GrantCatalog {
            grants = List.copyOf(grants);
            decisions = List.copyOf(decisions);
        }
    }
}
