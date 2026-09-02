package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.VaultManagementReceipt;
import com.javaclaw.api.VaultStatus;

/**
 * Secret Vault 管理页不可变状态。
 *
 * @param phase 异步阶段
 * @param status 最近脱敏状态
 * @param receipt 最近一次主密钥管理回执
 * @param message 读取结果或错误
 * @param epoch 请求代次
 */
public record VaultSettingsState(
        SettingsLoadState phase,
        Optional<VaultStatus> status,
        Optional<VaultManagementReceipt> receipt,
        String message,
        long epoch) {
    /** 校验状态。 */
    public VaultSettingsState {
        phase = Objects.requireNonNull(phase, "phase");
        status = Objects.requireNonNull(status, "status");
        receipt = Objects.requireNonNull(receipt, "receipt");
        message = Objects.requireNonNullElse(message, "");
    }

    /** @return 页面初始状态 */
    public static VaultSettingsState initial() {
        return new VaultSettingsState(SettingsLoadState.INITIAL, Optional.empty(), Optional.empty(), "", 0);
    }
}
