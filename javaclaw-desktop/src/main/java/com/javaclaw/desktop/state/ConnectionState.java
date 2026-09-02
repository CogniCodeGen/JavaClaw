package com.javaclaw.desktop.state;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Desktop 与本地 App Server 的连接快照。
 *
 * @param status 当前连接阶段
 * @param detail 面向用户的状态摘要，不含敏感信息
 * @param connectedAt 已连接时的建立时间；其他状态为空
 */
public record ConnectionState(Status status, String detail, Optional<Instant> connectedAt) {
    /** 校验状态与时间。 */
    public ConnectionState {
        Objects.requireNonNull(status, "status");
        detail = Objects.requireNonNull(detail, "detail").strip();
        connectedAt = Objects.requireNonNull(connectedAt, "connectedAt");
        if ((status == Status.CONNECTED) != connectedAt.isPresent()) {
            throw new IllegalArgumentException("only connected state has connectedAt");
        }
    }

    /** @return 尚未连接的初始状态 */
    public static ConnectionState disconnected() {
        return new ConnectionState(Status.DISCONNECTED, "尚未连接 App Server", Optional.empty());
    }

    /** @return 正在连接的状态 */
    public static ConnectionState connecting() {
        return new ConnectionState(Status.CONNECTING, "正在连接 App Server…", Optional.empty());
    }

    /**
     * 创建连接成功状态。
     *
     * @param detail 服务端版本摘要
     * @param now 连接时间
     * @return 已连接状态
     */
    public static ConnectionState connected(String detail, Instant now) {
        return new ConnectionState(Status.CONNECTED, detail, Optional.of(Objects.requireNonNull(now, "now")));
    }

    /**
     * 创建连接失败状态。
     *
     * @param detail 已脱敏错误
     * @return 失败状态
     */
    public static ConnectionState failed(String detail) {
        return new ConnectionState(Status.FAILED, detail, Optional.empty());
    }

    /** 连接生命周期状态。 */
    public enum Status {
        /** 尚未建立连接。 */
        DISCONNECTED,
        /** 连接与协议协商进行中。 */
        CONNECTING,
        /** Protocol v2 已协商。 */
        CONNECTED,
        /** 最近一次连接失败。 */
        FAILED
    }
}
