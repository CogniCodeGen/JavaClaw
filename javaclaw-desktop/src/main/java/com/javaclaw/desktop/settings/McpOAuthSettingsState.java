package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.McpOAuthAuthorization;
import com.javaclaw.api.McpOAuthState;

/**
 * MCP OAuth 区域的独立不可变状态。
 *
 * @param phase 当前异步阶段
 * @param endpointId 当前 Endpoint 标识
 * @param authorization 最近一次脱敏授权状态
 * @param message 用户可读反馈
 * @param epoch 请求序号；旧响应不得覆盖新选择
 */
public record McpOAuthSettingsState(
        SettingsLoadState phase,
        Optional<String> endpointId,
        Optional<McpOAuthAuthorization> authorization,
        String message,
        long epoch) {
    /** 复制并校验状态。 */
    public McpOAuthSettingsState {
        Objects.requireNonNull(phase, "phase");
        endpointId = Objects.requireNonNull(endpointId, "endpointId");
        authorization = Objects.requireNonNull(authorization, "authorization");
        message = Objects.requireNonNullElse(message, "");
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must not be negative");
        }
    }

    /** @return 尚未选择 Endpoint 的初始状态 */
    public static McpOAuthSettingsState initial() {
        return new McpOAuthSettingsState(
                SettingsLoadState.READY, Optional.empty(), Optional.empty(), "请选择 OAuth 连接", 0);
    }

    /** @return 是否正在等待隔离 Browser Worker 完成 */
    public boolean pendingAuthorization() {
        return authorization
                .map(value -> value.state() == McpOAuthState.PENDING)
                .orElse(false);
    }
}
