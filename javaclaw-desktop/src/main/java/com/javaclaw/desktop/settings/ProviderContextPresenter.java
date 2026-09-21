package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;

import com.javaclaw.api.ModelContextLimits;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.client.CommandOptions;

/** 容量编辑独立于连接草稿；epoch 阻止旧模型的异步结果覆盖新选择。 */
final class ProviderContextPresenter {
    private final ProviderContextSettingsGateway gateway;
    private final Runnable saved;
    private Consumer<State> listener = ignored -> {};
    private State state = new State(Optional.empty(), Optional.empty(), false, false, "请在模型目录中选择对话模型", 0);

    ProviderContextPresenter(ProviderContextSettingsGateway gateway, Runnable saved) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.saved = Objects.requireNonNull(saved, "saved");
    }

    void subscribe(Consumer<State> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    void bind(Optional<ProviderRef> provider) {
        if (state.saving() || state.provider().equals(provider)) {
            return;
        }
        long epoch = state.epoch() + 1;
        publish(new State(
                provider,
                Optional.empty(),
                provider.isPresent(),
                false,
                provider.isPresent() ? "正在读取模型容量…" : "请先保存模型服务并选择对话模型",
                epoch));
        provider.ifPresent(reference -> gateway.modelContextLimits(reference).whenComplete((value, failure) -> {
            if (state.epoch() == epoch) {
                publish(new State(
                        provider,
                        Optional.ofNullable(value),
                        false,
                        false,
                        failure == null ? "容量以 token 为单位；留空表示未知" : SettingsFailures.message(failure),
                        epoch));
            }
        }));
    }

    void save(String window, String output) {
        if (state.pending() || state.limits().isEmpty()) {
            return;
        }
        try {
            ProviderRef reference = state.provider().orElseThrow();
            ModelContextLimits limits = new ModelContextLimits(reference, parse(window), parse(output));
            long epoch = state.epoch();
            publish(new State(state.provider(), state.limits(), true, true, "正在保存模型容量…", epoch));
            gateway.updateModelContextLimits(limits, CommandOptions.create(reference.endpointRevision()))
                    .whenComplete((value, failure) -> {
                        if (state.epoch() != epoch) {
                            return;
                        }
                        if (failure == null) {
                            publish(new State(
                                    Optional.of(value.provider()),
                                    Optional.of(value),
                                    false,
                                    false,
                                    "模型容量已保存；新任务使用版本 " + value.provider().endpointRevision(),
                                    epoch));
                            saved.run();
                        } else {
                            publish(new State(
                                    state.provider(),
                                    state.limits(),
                                    false,
                                    false,
                                    SettingsFailures.message(failure),
                                    epoch));
                        }
                    });
        } catch (IllegalArgumentException failure) {
            publish(new State(
                    state.provider(), state.limits(), false, false, "请填写有效的 token 容量，输出不能超过窗口", state.epoch()));
        }
    }

    State state() {
        return state;
    }

    private static OptionalLong parse(String value) {
        return value.isBlank() ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(value.strip()));
    }

    private void publish(State next) {
        state = next;
        listener.accept(next);
    }

    /**
     * 容量编辑快照；读取允许切换模型，未决写入必须保留原请求身份直到回执。
     *
     * @param provider 当前精确模型，可为空
     * @param limits 已读取的容量，可为空
     * @param pending 是否正在读取或保存
     * @param saving 是否存在尚未收到回执的容量写入
     * @param message 当前状态说明
     * @param epoch 请求代次，切换只读请求时使旧结果失效
     */
    record State(
            Optional<ProviderRef> provider,
            Optional<ModelContextLimits> limits,
            boolean pending,
            boolean saving,
            String message,
            long epoch) {}
}
