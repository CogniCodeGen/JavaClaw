package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

/**
 * App Server 连接页不可变状态。
 *
 * @param phase 异步阶段
 * @param summary 最近成功的会话摘要
 * @param message 读取结果或错误
 * @param epoch 请求代次
 */
public record ConnectionSettingsState(
        SettingsLoadState phase, Optional<ConnectionSummary> summary, String message, long epoch) {
    /** 校验状态。 */
    public ConnectionSettingsState {
        phase = Objects.requireNonNull(phase, "phase");
        summary = Objects.requireNonNull(summary, "summary");
        message = Objects.requireNonNullElse(message, "");
    }

    /** @return 页面初始状态 */
    public static ConnectionSettingsState initial() {
        return new ConnectionSettingsState(SettingsLoadState.INITIAL, Optional.empty(), "", 0);
    }
}
