package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.protocol.BundleRpcContracts;

/**
 * Trust Key 管理页不可变状态。
 *
 * @param phase 异步阶段
 * @param keys Trust Key 权威目录
 * @param selected 当前密钥
 * @param keyId 待导入 manifest 密钥标识
 * @param draft 待确认指纹草稿
 * @param message 状态说明
 * @param revisionConflict 是否发生乐观锁冲突
 * @param epoch 请求代次
 */
public record TrustKeySettingsState(
        SettingsLoadState phase,
        List<BundleRpcContracts.TrustKey> keys,
        Optional<BundleRpcContracts.TrustKey> selected,
        String keyId,
        Optional<TrustKeyImportDraft> draft,
        String message,
        boolean revisionConflict,
        long epoch) {
    /** 固定集合并校验状态。 */
    public TrustKeySettingsState {
        Objects.requireNonNull(phase, "phase");
        keys = List.copyOf(keys);
        selected = Objects.requireNonNull(selected, "selected");
        keyId = Objects.requireNonNullElse(keyId, "");
        draft = Objects.requireNonNull(draft, "draft");
        message = Objects.requireNonNullElse(message, "");
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch 不能为负数");
        }
    }

    /** @return 导入标识或指纹草稿尚未提交时为 true */
    public boolean dirty() {
        return !keyId.isBlank() || draft.isPresent();
    }

    /** @return 空初始状态 */
    public static TrustKeySettingsState initial() {
        return new TrustKeySettingsState(
                SettingsLoadState.INITIAL, List.of(), Optional.empty(), "", Optional.empty(), "", false, 0);
    }
}
