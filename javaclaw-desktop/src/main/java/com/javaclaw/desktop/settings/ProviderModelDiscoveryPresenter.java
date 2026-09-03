package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderModelDiscoveryResult;

/** 只负责已保存 Provider 精确版本的非推理模型目录发现。 */
final class ProviderModelDiscoveryPresenter {
    private final CoreSettingsGateway gateway;
    private Consumer<ProviderModelDiscoveryState> listener = ignored -> {};
    private ProviderModelDiscoveryState state = ProviderModelDiscoveryState.initial();
    private CancellationSource active = new CancellationSource();

    ProviderModelDiscoveryPresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    void subscribe(Consumer<ProviderModelDiscoveryState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    void discover(ProviderEndpoint endpoint, boolean providerDraftDirty) {
        ProviderEndpoint selected = Objects.requireNonNull(endpoint, "endpoint");
        if (providerDraftDirty) {
            fail("请先保存或放弃模型服务草稿");
            return;
        }
        cancelActive("开始新的模型目录读取");
        active = new CancellationSource();
        CancellationSource operationCancellation = active;
        long epoch = state.epoch() + 1;
        publish(new ProviderModelDiscoveryState(state.result(), true, "正在联网读取模型元数据，不会执行推理…", epoch));
        gateway.discoverProviderModels(selected.id(), selected.revision(), operationCancellation)
                .whenComplete((result, failure) -> complete(epoch, result, failure));
    }

    void reset() {
        cancelActive("模型目录页面已离开或选择已变化");
        publish(new ProviderModelDiscoveryState(Optional.empty(), false, "", state.epoch() + 1));
    }

    ProviderModelDiscoveryState state() {
        return state;
    }

    private void complete(long epoch, ProviderModelDiscoveryResult result, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            fail("模型目录读取失败：" + SettingsFailures.message(failure));
            return;
        }
        String suffix = result.truncated() ? "，远程结果超过上限，已截断" : "";
        publish(new ProviderModelDiscoveryState(
                Optional.of(result), false, "已读取 " + result.candidates().size() + " 个候选" + suffix, epoch));
    }

    private void fail(String message) {
        publish(new ProviderModelDiscoveryState(state.result(), false, message, state.epoch()));
    }

    private void publish(ProviderModelDiscoveryState next) {
        state = next;
        listener.accept(next);
    }

    private void cancelActive(String reason) {
        active.cancel(reason);
    }
}
