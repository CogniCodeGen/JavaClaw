package com.javaclaw.desktop.settings;

import java.util.Objects;

/**
 * Prompt 优化面板不可变状态。
 *
 * @param phase 异步阶段
 * @param selection 目录与选择
 * @param message 用户可见状态
 * @param revisionConflict 最近一次采纳是否发生 Role revision 冲突
 * @param epoch 请求 epoch；旧响应必须丢弃
 */
public record PromptOptimizationSettingsState(
        SettingsLoadState phase,
        PromptOptimizationSelection selection,
        String message,
        boolean revisionConflict,
        long epoch) {
    /** 校验状态。 */
    public PromptOptimizationSettingsState {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(selection, "selection");
        message = Objects.requireNonNullElse(message, "");
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must not be negative");
        }
    }

    /** @return 初始状态 */
    public static PromptOptimizationSettingsState initial() {
        return new PromptOptimizationSettingsState(
                SettingsLoadState.INITIAL, PromptOptimizationSelection.initial(), "", false, 0);
    }

    /** @return 是否有异步请求尚未完成 */
    public boolean pending() {
        return phase == SettingsLoadState.LOADING || phase == SettingsLoadState.SAVING;
    }
}
