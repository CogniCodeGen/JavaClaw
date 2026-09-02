package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderVerificationResult;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.protocol.ProviderVerificationRpcContracts;

/** Provider 显式计费验证的独立异步状态机。 */
public final class ProviderVerificationPresenter {
    private final CoreSettingsGateway gateway;
    private Consumer<ProviderVerificationSettingsState> listener = ignored -> {};
    private ProviderVerificationSettingsState state = ProviderVerificationSettingsState.initial();

    /**
     * 创建 Presenter。
     *
     * @param gateway SDK 异步边界
     */
    public ProviderVerificationPresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /**
     * 订阅不可变状态并立即收到当前值。
     *
     * @param value 页面渲染回调
     */
    public void subscribe(Consumer<ProviderVerificationSettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /**
     * 绑定当前已保存 Provider、精确模型和页面可用性。
     *
     * @param endpoint 当前 Provider；未选择时为空
     * @param model 当前模型；未选择时为空
     * @param credentialAvailable Vault 元数据是否可用
     * @param blocked 是否因草稿或其他设置命令阻止验证
     */
    public void bind(
            Optional<ProviderEndpoint> endpoint, Optional<String> model, boolean credentialAvailable, boolean blocked) {
        Optional<ProviderRef> reference = endpoint.flatMap(
                value -> model.map(selected -> new ProviderRef(value.id(), value.revision(), selected)));
        boolean available = endpoint.filter(value -> value.lifecycle() == ProviderLifecycle.ACTIVE)
                        .flatMap(value -> value.spec().credential())
                        .isPresent()
                && credentialAvailable
                && !blocked
                && reference.isPresent();
        boolean changed = !reference.equals(state.provider()) || available != state.available();
        String message = unavailableMessage(endpoint, credentialAvailable, blocked, reference);
        publish(new ProviderVerificationSettingsState(
                SettingsLoadState.READY,
                reference,
                available,
                changed ? Optional.empty() : state.result(),
                message,
                changed ? state.epoch() + 1 : state.epoch()));
    }

    /**
     * 提交双重危险确认后的显式计费验证。
     *
     * @param billingConfirmed UI 二次确认标记
     * @param confirmation 精确固定确认文本
     */
    public void verify(boolean billingConfirmed, String confirmation) {
        if (!state.available()) {
            publishFailure("Provider 当前不满足计费验证条件");
            return;
        }
        if (!billingConfirmed || !ProviderVerificationRpcContracts.BILLING_CONFIRMATION.equals(confirmation)) {
            publishFailure("危险确认文本不匹配，未发起模型调用");
            return;
        }
        ProviderRef provider = state.provider().orElseThrow();
        long epoch = state.epoch() + 1;
        publish(new ProviderVerificationSettingsState(
                SettingsLoadState.SAVING, state.provider(), true, state.result(), "正在执行可能计费的最小模型调用…", epoch));
        gateway.verifyProviderRoundTrip(
                        provider,
                        true,
                        ProviderVerificationRpcContracts.BILLING_CONFIRMATION,
                        CommandOptions.create(provider.endpointRevision()))
                .whenComplete((result, failure) -> complete(epoch, result, failure));
    }

    /** @return 当前不可变状态 */
    public ProviderVerificationSettingsState state() {
        return state;
    }

    private void complete(long epoch, ProviderVerificationResult result, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publishFailure(SettingsFailures.message(failure));
            return;
        }
        publish(new ProviderVerificationSettingsState(
                SettingsLoadState.READY,
                state.provider(),
                true,
                Optional.of(result),
                result.state() + " · " + result.latencyMillis() + " ms",
                epoch));
    }

    private void publishFailure(String message) {
        publish(new ProviderVerificationSettingsState(
                SettingsLoadState.ERROR, state.provider(), state.available(), state.result(), message, state.epoch()));
    }

    private static String unavailableMessage(
            Optional<ProviderEndpoint> endpoint,
            boolean credentialAvailable,
            boolean blocked,
            Optional<ProviderRef> reference) {
        if (endpoint.isEmpty()) {
            return "请选择已保存的 Provider";
        }
        if (endpoint.orElseThrow().lifecycle() != ProviderLifecycle.ACTIVE) {
            return "只有 ACTIVE Provider 可以执行计费验证";
        }
        if (endpoint.orElseThrow().spec().credential().isEmpty() || !credentialAvailable) {
            return "CredentialRef 不可用，计费验证已关闭";
        }
        if (blocked) {
            return "请先保存或放弃 Provider 草稿";
        }
        if (reference.isEmpty()) {
            return "请选择精确模型";
        }
        return "验证会发送最小无工具调用，可能产生真实模型费用";
    }

    private void publish(ProviderVerificationSettingsState next) {
        state = next;
        listener.accept(next);
    }
}
