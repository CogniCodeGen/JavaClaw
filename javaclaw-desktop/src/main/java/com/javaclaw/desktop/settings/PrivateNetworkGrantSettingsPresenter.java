package com.javaclaw.desktop.settings;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.javaclaw.api.PermissionDecisionTrace;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantPreview;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.SecurityGrantKind;
import com.javaclaw.api.Workspace;
import com.javaclaw.client.CommandOptions;

/** 协调私网授权的 preview-confirm、列表、审计与实时撤销。 */
public final class PrivateNetworkGrantSettingsPresenter {
    private final CoreSettingsGateway gateway;
    private Consumer<PrivateNetworkGrantSettingsState> listener = ignored -> {};
    private PrivateNetworkGrantSettingsState state = PrivateNetworkGrantSettingsState.initial();

    /** @param gateway 强类型 SDK 设置边界 */
    public PrivateNetworkGrantSettingsPresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /** @param value 页面状态监听器 */
    public void subscribe(Consumer<PrivateNetworkGrantSettingsState> value) {
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
        publish(new PrivateNetworkGrantSettingsState(
                SettingsLoadState.LOADING,
                state.workspaces(),
                Optional.of(checked),
                List.of(),
                Optional.empty(),
                state.purpose(),
                "",
                "",
                "1",
                Optional.empty(),
                List.of(),
                "正在读取私网授权…",
                epoch));
        gateway.privateNetworkGrants(checked.id())
                .thenCombine(
                        gateway.permissionDecisions(
                                checked.id(), SecurityGrantKind.PRIVATE_NETWORK, Optional.empty(), 100),
                        GrantCatalog::new)
                .whenComplete((catalog, failure) -> applyGrantCatalog(epoch, checked, catalog, failure));
    }

    /** @param grant 列表中选中的授权 */
    public void selectGrant(PrivateNetworkGrant grant) {
        publish(new PrivateNetworkGrantSettingsState(
                state.phase(),
                state.workspaces(),
                state.workspace(),
                state.grants(),
                Optional.ofNullable(grant),
                state.purpose(),
                state.origin(),
                state.dnsAddresses(),
                state.validityHours(),
                state.preview(),
                state.decisions(),
                state.message(),
                state.epoch()));
    }

    /** 更新草稿；任何修改都会使旧预览失效。 */
    public void edit(PrivateNetworkPurpose purpose, String origin, String addresses, String validityHours) {
        publish(new PrivateNetworkGrantSettingsState(
                state.phase(),
                state.workspaces(),
                state.workspace(),
                state.grants(),
                state.selected(),
                Objects.requireNonNull(purpose, "purpose"),
                Objects.requireNonNullElse(origin, ""),
                Objects.requireNonNullElse(addresses, ""),
                Objects.requireNonNullElse(validityHours, ""),
                Optional.empty(),
                state.decisions(),
                "草稿已改变，请重新生成预览",
                state.epoch()));
    }

    /** 请求服务端规范化预览；不写入授权。 */
    public void preview() {
        Workspace workspace = state.workspace().orElseThrow();
        try {
            URI origin = URI.create(state.origin().strip());
            Set<String> addresses = addressSet(state.dnsAddresses());
            Duration validity = Duration.ofHours(hours(state.validityHours()));
            executePreview(gateway.previewPrivateNetworkGrant(
                    workspace.id(), state.purpose(), origin, addresses, Optional.of(validity)));
        } catch (RuntimeException failure) {
            publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), state.epoch()));
        }
    }

    /** 提交当前服务端预览；调用方应先展示危险确认。 */
    public void confirmCreate() {
        PrivateNetworkGrantPreview preview = state.preview().orElseThrow();
        executeCommand(gateway.createPrivateNetworkGrant(preview, CommandOptions.create(0)), "私网授权已创建");
    }

    /** 不可逆撤销选中的活动授权。 */
    public void revoke() {
        PrivateNetworkGrant grant = state.selected().orElseThrow();
        executeCommand(gateway.revokePrivateNetworkGrant(grant, CommandOptions.create(grant.revision())), "私网授权已撤销");
    }

    /** 丢弃尚未确认的输入和服务端预览。 */
    public void discardDraft() {
        publish(new PrivateNetworkGrantSettingsState(
                state.phase(),
                state.workspaces(),
                state.workspace(),
                state.grants(),
                state.selected(),
                PrivateNetworkPurpose.MCP,
                "",
                "",
                "1",
                Optional.empty(),
                state.decisions(),
                "",
                state.epoch()));
    }

    private void executePreview(CompletionStage<PrivateNetworkGrantPreview> operation) {
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.LOADING, "正在生成权威预览…", epoch));
        operation.whenComplete((preview, failure) -> {
            if (epoch != state.epoch()) {
                return;
            }
            if (failure != null) {
                publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
                return;
            }
            publish(new PrivateNetworkGrantSettingsState(
                    SettingsLoadState.READY,
                    state.workspaces(),
                    state.workspace(),
                    state.grants(),
                    state.selected(),
                    state.purpose(),
                    state.origin(),
                    state.dnsAddresses(),
                    state.validityHours(),
                    Optional.of(preview),
                    state.decisions(),
                    "请核对 Origin、地址集合和期限后确认",
                    epoch));
        });
    }

    private void executeCommand(CompletionStage<?> operation, String success) {
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
        publish(new PrivateNetworkGrantSettingsState(
                SettingsLoadState.READY,
                sorted,
                Optional.empty(),
                List.of(),
                Optional.empty(),
                PrivateNetworkPurpose.MCP,
                "",
                "",
                "1",
                Optional.empty(),
                List.of(),
                sorted.isEmpty() ? "暂无 Workspace" : "",
                epoch));
        sorted.stream().findFirst().ifPresent(this::selectWorkspace);
    }

    private void applyGrantCatalog(long epoch, Workspace workspace, GrantCatalog catalog, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
            return;
        }
        List<PrivateNetworkGrant> grants = catalog.grants().stream()
                .sorted(Comparator.comparing(PrivateNetworkGrant::updatedAt).reversed())
                .toList();
        publish(new PrivateNetworkGrantSettingsState(
                SettingsLoadState.READY,
                state.workspaces(),
                Optional.of(workspace),
                grants,
                grants.stream().findFirst(),
                PrivateNetworkPurpose.MCP,
                "",
                "",
                "1",
                Optional.empty(),
                catalog.decisions(),
                "",
                epoch));
    }

    private PrivateNetworkGrantSettingsState copy(SettingsLoadState phase, String message, long epoch) {
        return new PrivateNetworkGrantSettingsState(
                phase,
                state.workspaces(),
                state.workspace(),
                state.grants(),
                state.selected(),
                state.purpose(),
                state.origin(),
                state.dnsAddresses(),
                state.validityHours(),
                state.preview(),
                state.decisions(),
                message,
                epoch);
    }

    private void publish(PrivateNetworkGrantSettingsState next) {
        state = Objects.requireNonNull(next, "next");
        listener.accept(next);
    }

    private long nextEpoch() {
        return state.epoch() + 1;
    }

    private static Set<String> addressSet(String value) {
        Set<String> addresses = Arrays.stream(
                        Objects.requireNonNullElse(value, "").split("[,\\s]+"))
                .map(String::strip)
                .filter(token -> !token.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("至少填写一个当前 DNS 数字地址");
        }
        return addresses;
    }

    private static long hours(String value) {
        long hours = Long.parseLong(Objects.requireNonNullElse(value, "").strip());
        if (hours < 1 || hours > 24) {
            throw new IllegalArgumentException("私网授权期限必须在 1 至 24 小时之间");
        }
        return hours;
    }

    private record GrantCatalog(List<PrivateNetworkGrant> grants, List<PermissionDecisionTrace> decisions) {
        private GrantCatalog {
            grants = List.copyOf(grants);
            decisions = List.copyOf(decisions);
        }
    }
}
