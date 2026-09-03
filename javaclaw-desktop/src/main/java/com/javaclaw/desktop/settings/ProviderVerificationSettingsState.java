package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderVerificationResult;

/**
 * Provider 显式计费验证的一次完整不可变渲染状态。
 *
 * @param phase 异步阶段
 * @param provider 当前精确 Provider 与模型
 * @param purpose 此状态机负责的模型用途
 * @param available 当前是否满足 ACTIVE、凭据可用和无草稿条件
 * @param result 最近一次脱敏验证结果
 * @param message 可读状态或失败说明
 * @param epoch 丢弃旧异步响应的请求代次
 */
public record ProviderVerificationSettingsState(
        SettingsLoadState phase,
        Optional<ProviderRef> provider,
        ProviderModelPurpose purpose,
        boolean available,
        Optional<ProviderVerificationResult> result,
        String message,
        long epoch) {
    /** 校验状态。 */
    public ProviderVerificationSettingsState {
        Objects.requireNonNull(phase, "phase");
        provider = Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(purpose, "purpose");
        result = Objects.requireNonNull(result, "result");
        message = Objects.requireNonNullElse(message, "");
        if (available && provider.isEmpty()) {
            throw new IllegalArgumentException("available verification requires provider");
        }
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must not be negative");
        }
    }

    /** @return 尚未绑定 Provider 的初始状态 */
    public static ProviderVerificationSettingsState initial(ProviderModelPurpose purpose) {
        return new ProviderVerificationSettingsState(
                SettingsLoadState.INITIAL, Optional.empty(), purpose, false, Optional.empty(), "请选择已保存的模型服务", 0);
    }

    /** @return 验证命令是否正在后台执行 */
    public boolean pending() {
        return phase == SettingsLoadState.SAVING;
    }
}
