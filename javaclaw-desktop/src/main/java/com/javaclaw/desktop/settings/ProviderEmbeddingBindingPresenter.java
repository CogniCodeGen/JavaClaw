package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.api.EmbeddingBinding;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.client.CommandOptions;

/** 管理本地安装唯一的精确 Embedding ProviderRef 绑定。 */
final class ProviderEmbeddingBindingPresenter {
    private final CoreSettingsGateway gateway;
    private Consumer<ProviderEmbeddingBindingState> listener = ignored -> {};
    private ProviderEmbeddingBindingState state = ProviderEmbeddingBindingState.initial();

    ProviderEmbeddingBindingPresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    void subscribe(Consumer<ProviderEmbeddingBindingState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    void reload() {
        long epoch = state.epoch() + 1;
        publish(new ProviderEmbeddingBindingState(state.binding(), true, "正在读取默认向量模型…", epoch));
        gateway.embeddingBinding().whenComplete((binding, failure) -> completeReload(epoch, binding, failure));
    }

    void bind(ProviderEndpoint endpoint, ProviderModelSpec model) {
        ProviderEndpoint selected = Objects.requireNonNull(endpoint, "endpoint");
        ProviderModelSpec selectedModel = Objects.requireNonNull(model, "model");
        if (!selectedModel.supports(ProviderModelPurpose.EMBEDDING)) {
            throw new IllegalArgumentException("只能将 Embedding 用途的模型设为默认向量模型");
        }
        long expectedRevision = state.binding().map(EmbeddingBinding::revision).orElse(0L);
        long epoch = state.epoch() + 1;
        publish(new ProviderEmbeddingBindingState(state.binding(), true, "正在更新默认向量模型…", epoch));
        ProviderRef reference = new ProviderRef(selected.id(), selected.revision(), selectedModel.modelId());
        gateway.bindEmbedding(reference, CommandOptions.create(expectedRevision))
                .whenComplete((binding, failure) -> completeBind(epoch, binding, failure));
    }

    ProviderEmbeddingBindingState state() {
        return state;
    }

    private void completeReload(long epoch, Optional<EmbeddingBinding> binding, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(new ProviderEmbeddingBindingState(
                    Optional.empty(), false, "向量模型绑定读取失败：" + SettingsFailures.message(failure), epoch));
            return;
        }
        publish(new ProviderEmbeddingBindingState(binding, false, "", epoch));
    }

    private void completeBind(long epoch, EmbeddingBinding binding, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(new ProviderEmbeddingBindingState(
                    state.binding(), false, "默认向量模型更新失败：" + SettingsFailures.message(failure), epoch));
            return;
        }
        publish(new ProviderEmbeddingBindingState(Optional.of(binding), false, "默认向量模型已更新", epoch));
    }

    private void publish(ProviderEmbeddingBindingState next) {
        state = next;
        listener.accept(next);
    }
}
