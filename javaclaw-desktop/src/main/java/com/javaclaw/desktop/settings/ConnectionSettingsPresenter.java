package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** App Server 当前 SDK 会话状态 Presenter。 */
public final class ConnectionSettingsPresenter {
    private final CoreSettingsGateway gateway;
    private Consumer<ConnectionSettingsState> listener = ignored -> {};
    private ConnectionSettingsState state = ConnectionSettingsState.initial();

    /**
     * 创建 Presenter。
     *
     * @param gateway SDK 异步边界
     */
    public ConnectionSettingsPresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /**
     * 订阅状态并立即收到当前快照。
     *
     * @param value 页面渲染回调
     */
    public void subscribe(Consumer<ConnectionSettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** 读取当前已经初始化的 SDK 会话摘要。 */
    public void reload() {
        request(false);
    }

    /** 关闭旧会话并重新执行本地 transport 连接与 Protocol v3 初始化。 */
    public void reconnect() {
        request(true);
    }

    private void request(boolean reconnect) {
        long epoch = state.epoch() + 1;
        String pending = reconnect ? "正在重新连接 JavaClaw 服务…" : "正在检查 JavaClaw 服务会话…";
        publish(new ConnectionSettingsState(SettingsLoadState.LOADING, state.summary(), pending, epoch));
        java.util.concurrent.CompletionStage<ConnectionSummary> operation =
                reconnect ? gateway.reconnect() : gateway.connection();
        operation.whenComplete((summary, failure) -> {
            if (epoch != state.epoch()) {
                return;
            }
            if (failure != null) {
                publish(new ConnectionSettingsState(
                        SettingsLoadState.ERROR, state.summary(), SettingsFailures.message(failure), epoch));
            } else {
                publish(new ConnectionSettingsState(SettingsLoadState.READY, Optional.of(summary), "", epoch));
            }
        });
    }

    private void publish(ConnectionSettingsState next) {
        state = next;
        listener.accept(next);
    }
}
