package com.javaclaw.desktop.settings;

import java.util.Objects;

/**
 * Extension Job 页面异步反馈。
 *
 * @param phase 当前阶段
 * @param message 脱敏说明
 * @param epoch 最新请求代次
 */
public record AutomationJobFeedback(SettingsLoadState phase, String message, long epoch) {
    /** 校验状态和 epoch。 */
    public AutomationJobFeedback {
        phase = Objects.requireNonNull(phase, "phase");
        message = Objects.requireNonNullElse(message, "").strip();
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch 不能为负数");
        }
    }

    /** @return 未开始读取的反馈 */
    public static AutomationJobFeedback initial() {
        return new AutomationJobFeedback(SettingsLoadState.INITIAL, "", 0);
    }
}
