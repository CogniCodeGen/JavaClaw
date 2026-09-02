package com.javaclaw.launcher.tray;

import java.util.Objects;
import java.util.Optional;

/**
 * 托盘可见状态的不可变投影。
 *
 * @param server App Server 状态
 * @param mainWindowOpen 主窗口进程是否仍存活
 * @param pending 是否正在执行命令
 * @param message 当前通俗状态
 * @param error 最近一次失败；没有失败时为空
 */
public record TrayState(
        ServerState server, boolean mainWindowOpen, boolean pending, String message, Optional<String> error) {
    /** 校验可见字段。 */
    public TrayState {
        Objects.requireNonNull(server, "server");
        message = text(message, "message");
        error = Objects.requireNonNull(error, "error").map(value -> text(value, "error"));
    }

    /** App Server 的脱敏可见状态。 */
    public enum ServerState {
        /** 尚未完成探测。 */
        UNKNOWN,
        /** 本地 transport 可连接。 */
        RUNNING,
        /** 本地 transport 不可连接。 */
        STOPPED
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
