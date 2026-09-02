package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.protocol.BundleRpcContracts;

/**
 * 第三方 Bundle 管理页不可变状态。
 *
 * @param phase 异步阶段
 * @param bundles 已安装权威目录
 * @param selected 当前 Bundle
 * @param staging 尚未提交的权限审阅
 * @param message 状态说明
 * @param revisionConflict 是否发生乐观锁冲突
 * @param epoch 请求代次
 */
public record BundleSettingsState(
        SettingsLoadState phase,
        List<BundleRpcContracts.Bundle> bundles,
        Optional<BundleRpcContracts.Bundle> selected,
        Optional<BundleRpcContracts.StageResult> staging,
        String message,
        boolean revisionConflict,
        long epoch) {
    /** 固定集合并校验状态。 */
    public BundleSettingsState {
        Objects.requireNonNull(phase, "phase");
        bundles = List.copyOf(bundles);
        selected = Objects.requireNonNull(selected, "selected");
        staging = Objects.requireNonNull(staging, "staging");
        message = Objects.requireNonNullElse(message, "");
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch 不能为负数");
        }
    }

    /** @return staging 尚未安装或升级时为 true */
    public boolean dirty() {
        return staging.isPresent();
    }

    /** @return 空初始状态 */
    public static BundleSettingsState initial() {
        return new BundleSettingsState(
                SettingsLoadState.INITIAL, List.of(), Optional.empty(), Optional.empty(), "", false, 0);
    }
}
