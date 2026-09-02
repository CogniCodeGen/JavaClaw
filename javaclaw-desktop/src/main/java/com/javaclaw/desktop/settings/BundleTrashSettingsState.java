package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.protocol.BundleRpcContracts;

/**
 * Bundle Trash 管理页不可变状态。
 *
 * @param phase 异步阶段
 * @param entries Trash 权威历史
 * @param selected 当前条目
 * @param message 状态说明
 * @param revisionConflict 是否发生乐观锁冲突
 * @param epoch 请求代次
 */
public record BundleTrashSettingsState(
        SettingsLoadState phase,
        List<BundleRpcContracts.TrashEntry> entries,
        Optional<BundleRpcContracts.TrashEntry> selected,
        String message,
        boolean revisionConflict,
        long epoch) {
    /** 固定集合并校验状态。 */
    public BundleTrashSettingsState {
        Objects.requireNonNull(phase, "phase");
        entries = List.copyOf(entries);
        selected = Objects.requireNonNull(selected, "selected");
        message = Objects.requireNonNullElse(message, "");
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch 不能为负数");
        }
    }

    /** @return 空初始状态 */
    public static BundleTrashSettingsState initial() {
        return new BundleTrashSettingsState(SettingsLoadState.INITIAL, List.of(), Optional.empty(), "", false, 0);
    }
}
