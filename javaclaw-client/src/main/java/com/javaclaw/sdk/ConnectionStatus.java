package com.javaclaw.sdk;

import java.time.Instant;

/**
 * Observable local connection lifecycle for Desktop/CLI status presentation.
 *
 * @param state 连接生命周期状态
 * @param reconnectAttempt 重连尝试次数，非负
 * @param detail 状态说明；null 归一为空字符串
 * @param at 状态时间；null 使用当前时间
 */
public record ConnectionStatus(State state, int reconnectAttempt, String detail, Instant at) {
    /** 校验重连次数并归一说明/时间，供桌面与 CLI 一致展示连接状态。 */
    public ConnectionStatus {
        if (reconnectAttempt < 0) {
            throw new IllegalArgumentException("attempt must be non-negative");
        }
        detail = detail == null ? "" : detail;
        at = at == null ? Instant.now() : at;
    }

    /** 本地连接状态；RESYNC_REQUIRED 表示需恢复游标，不等同于 Turn 执行失败。 */
    public enum State {
        CONNECTED,
        RECONNECTING,
        RESYNC_REQUIRED,
        CLOSED
    }
}
