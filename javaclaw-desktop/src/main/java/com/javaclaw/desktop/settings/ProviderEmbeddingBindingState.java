package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.EmbeddingBinding;

/**
 * 本地安装 Embedding 绑定的不可变状态。
 *
 * @param binding 当前精确绑定
 * @param pending 是否正在读取或写入
 * @param message 状态或错误
 * @param epoch 请求代次
 */
record ProviderEmbeddingBindingState(Optional<EmbeddingBinding> binding, boolean pending, String message, long epoch) {
    ProviderEmbeddingBindingState {
        binding = Objects.requireNonNull(binding, "binding");
        message = Objects.requireNonNullElse(message, "");
    }

    static ProviderEmbeddingBindingState initial() {
        return new ProviderEmbeddingBindingState(Optional.empty(), false, "", 0);
    }
}
