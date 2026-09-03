package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ProviderModelDiscoveryResult;

/**
 * Provider 模型目录发现的不可变状态。
 *
 * @param result 最近一次成功的有界候选目录
 * @param pending 是否正在联网读取
 * @param message 脱敏状态或错误
 * @param epoch 丢弃旧响应的请求代次
 */
record ProviderModelDiscoveryState(
        Optional<ProviderModelDiscoveryResult> result, boolean pending, String message, long epoch) {
    ProviderModelDiscoveryState {
        result = Objects.requireNonNull(result, "result");
        message = Objects.requireNonNullElse(message, "");
    }

    static ProviderModelDiscoveryState initial() {
        return new ProviderModelDiscoveryState(Optional.empty(), false, "", 0);
    }
}
