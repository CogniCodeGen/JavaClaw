package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpHealth;
import com.javaclaw.api.McpOAuthAuthorization;
import com.javaclaw.api.McpOAuthState;
import com.javaclaw.client.CommandOptions;

/**
 * MCP OAuth 管理区的独立异步状态机。
 *
 * <p>Presenter 只处理服务端返回的脱敏状态；完整 authorization URI、state、challenge、code 与 token 从不进入 Desktop。隔离 Browser Worker 的打开、回调截获和
 * token 交换全部由 App Server 协调。
 */
public final class McpOAuthSettingsPresenter {
    private final McpSettingsGateway gateway;
    private final BiConsumer<McpEndpoint, McpHealth> authorized;
    private Consumer<McpOAuthSettingsState> listener = ignored -> {};
    private Optional<McpEndpoint> endpoint = Optional.empty();
    private McpOAuthSettingsState state = McpOAuthSettingsState.initial();

    /**
     * 创建 OAuth Presenter。
     *
     * @param gateway 强类型 SDK 边界
     * @param authorized 授权成功后提交 Endpoint 与健康快照的回调
     */
    public McpOAuthSettingsPresenter(McpSettingsGateway gateway, BiConsumer<McpEndpoint, McpHealth> authorized) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.authorized = Objects.requireNonNull(authorized, "authorized");
    }

    /** @param subscriber 完整状态订阅者；注册后立即收到当前快照 */
    public void subscribe(Consumer<McpOAuthSettingsState> subscriber) {
        listener = Objects.requireNonNull(subscriber, "subscriber");
        listener.accept(state);
    }

    /**
     * 切换 Endpoint 并读取最近授权状态。
     *
     * @param selected 当前 Endpoint；没有选择时为空
     */
    public void select(Optional<McpEndpoint> selected) {
        endpoint = Objects.requireNonNull(selected, "selected");
        long epoch = nextEpoch();
        if (selected.isEmpty() || selected.orElseThrow().spec().authType() != McpAuthType.OAUTH_2_1_PKCE) {
            publish(new McpOAuthSettingsState(
                    SettingsLoadState.READY,
                    selected.map(McpEndpoint::id),
                    Optional.empty(),
                    selected.isEmpty() ? "请选择 OAuth 连接" : "当前连接未使用 OAuth 2.1 + PKCE",
                    epoch));
            return;
        }
        McpEndpoint value = selected.orElseThrow();
        publish(loading(value.id(), "正在读取 OAuth 状态…", epoch));
        gateway.latestMcpOAuth(value.id())
                .whenComplete((authorization, failure) -> completeRead(epoch, authorization, failure));
    }

    /** 启动隔离 OAuth；该操作不会向 Desktop 返回或打开 authorization URI。 */
    public void start() {
        McpEndpoint selected = requireEndpoint();
        if (selected.spec().authType() != McpAuthType.OAUTH_2_1_PKCE) {
            failLocal("当前连接未选择 OAuth 2.1 + PKCE");
            return;
        }
        if (selected.state() != McpEndpointState.ENABLED) {
            failLocal("请先启用 MCP 连接");
            return;
        }
        long epoch = nextEpoch();
        publish(loading(selected.id(), "正在启动受控隔离浏览器…", epoch));
        gateway.startMcpOAuth(selected, CommandOptions.create(0))
                .whenComplete((authorization, failure) -> completeAuthorization(epoch, authorization, failure));
    }

    /** 显式重新读取当前 Endpoint 最近一次授权状态。 */
    public void refresh() {
        McpEndpoint selected = requireEndpoint();
        long epoch = nextEpoch();
        publish(loading(selected.id(), "正在刷新 OAuth 状态…", epoch));
        gateway.latestMcpOAuth(selected.id())
                .whenComplete((authorization, failure) -> completeRead(epoch, authorization, failure));
    }

    /** 取消当前待处理流程；服务端会同步关闭 Worker、释放 lease 并清理 PKCE。 */
    public void cancel() {
        McpOAuthAuthorization pending = state.authorization()
                .filter(value -> value.state() == McpOAuthState.PENDING)
                .orElseThrow(() -> new IllegalStateException("当前没有等待中的 OAuth 授权"));
        long epoch = nextEpoch();
        publish(loading(pending.endpointId(), "正在取消 OAuth 授权…", epoch));
        gateway.cancelMcpOAuth(pending, CommandOptions.create(0))
                .whenComplete((authorization, failure) -> completeAuthorization(epoch, authorization, failure));
    }

    /** @return 当前不可变状态 */
    public McpOAuthSettingsState state() {
        return state;
    }

    private void completeRead(long epoch, Optional<McpOAuthAuthorization> authorization, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        if (authorization.isEmpty()) {
            publish(ready(Optional.empty(), "尚未启动 OAuth 授权", epoch));
            return;
        }
        completeAuthorization(epoch, authorization.orElseThrow(), null);
    }

    private void completeAuthorization(long epoch, McpOAuthAuthorization authorization, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        McpOAuthAuthorization checked = Objects.requireNonNull(authorization, "authorization");
        publish(ready(Optional.of(checked), message(checked), epoch));
        if (checked.state() == McpOAuthState.AUTHORIZED) {
            refreshAuthoritativeEndpoint(epoch, checked.endpointId());
        }
    }

    private void refreshAuthoritativeEndpoint(long epoch, String endpointId) {
        CompletionStage<McpEndpoint> endpointRequest = gateway.mcpEndpoint(endpointId);
        endpointRequest
                .thenCombine(gateway.mcpHealth(endpointId), AuthoritativeResult::new)
                .whenComplete((result, failure) -> completeAuthoritativeRefresh(epoch, result, failure));
    }

    private void completeAuthoritativeRefresh(long epoch, AuthoritativeResult result, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        endpoint = Optional.of(result.endpoint());
        publish(ready(state.authorization(), "OAuth 已完成，连接凭据与健康状态已刷新", epoch));
        authorized.accept(result.endpoint(), result.health());
    }

    private void failLocal(String message) {
        publish(new McpOAuthSettingsState(
                SettingsLoadState.ERROR, endpoint.map(McpEndpoint::id), state.authorization(), message, nextEpoch()));
    }

    private void fail(long epoch, Throwable failure) {
        publish(new McpOAuthSettingsState(
                SettingsLoadState.ERROR,
                endpoint.map(McpEndpoint::id),
                state.authorization(),
                SettingsFailures.message(failure),
                epoch));
    }

    private McpOAuthSettingsState loading(String endpointId, String message, long epoch) {
        return new McpOAuthSettingsState(
                SettingsLoadState.LOADING, Optional.of(endpointId), state.authorization(), message, epoch);
    }

    private McpOAuthSettingsState ready(Optional<McpOAuthAuthorization> authorization, String message, long epoch) {
        return new McpOAuthSettingsState(
                SettingsLoadState.READY, endpoint.map(McpEndpoint::id), authorization, message, epoch);
    }

    private McpEndpoint requireEndpoint() {
        return endpoint.orElseThrow(() -> new IllegalStateException("请先选择 MCP 连接"));
    }

    private long nextEpoch() {
        return state.epoch() + 1;
    }

    private boolean stale(long epoch) {
        return epoch != state.epoch();
    }

    private void publish(McpOAuthSettingsState next) {
        state = Objects.requireNonNull(next, "next");
        listener.accept(state);
    }

    private static String message(McpOAuthAuthorization authorization) {
        return switch (authorization.state()) {
            case PENDING -> "请在受控隔离浏览器中完成 " + authorization.authorizationHost() + " 授权";
            case AUTHORIZED -> "OAuth 授权已完成";
            case CANCELLED -> "OAuth 授权已取消";
            case EXPIRED -> "OAuth 授权已过期，请重新启动";
            case FAILED -> "OAuth 授权失败：" + authorization.detail().orElse("UNKNOWN_FAILURE");
        };
    }

    private record AuthoritativeResult(McpEndpoint endpoint, McpHealth health) {}
}
