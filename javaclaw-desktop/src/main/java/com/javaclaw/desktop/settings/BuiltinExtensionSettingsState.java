package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.protocol.BuiltinExtensionRpcContracts;

/**
 * 内置能力管理页的不可变状态。
 *
 * @param phase 异步阶段
 * @param extensions 权威目录
 * @param selected 当前选择
 * @param message 状态或错误说明
 * @param revisionConflict 是否发生乐观锁冲突
 * @param epoch 请求代次
 */
public record BuiltinExtensionSettingsState(
        SettingsLoadState phase,
        List<BuiltinExtensionRpcContracts.Status> extensions,
        Optional<BuiltinExtensionRpcContracts.Status> selected,
        String message,
        boolean revisionConflict,
        long epoch) {
    /** 固定集合并校验状态。 */
    public BuiltinExtensionSettingsState {
        Objects.requireNonNull(phase, "phase");
        extensions = List.copyOf(extensions);
        selected = Objects.requireNonNull(selected, "selected");
        message = Objects.requireNonNullElse(message, "");
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch 不能为负数");
        }
    }

    /** @return 尚未加载的初始状态 */
    public static BuiltinExtensionSettingsState initial() {
        return new BuiltinExtensionSettingsState(SettingsLoadState.INITIAL, List.of(), Optional.empty(), "", false, 0);
    }

    /** @return 是否正在读取或写入 */
    public boolean pending() {
        return phase == SettingsLoadState.LOADING || phase == SettingsLoadState.SAVING;
    }
}
