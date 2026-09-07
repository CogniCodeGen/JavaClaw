package com.javaclaw.server.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.TurnId;

/**
 * 服务端为一次已批准依赖准备签发的固定网络范围；不接受模型自行声明安装用途。
 *
 * @param id 唯一租约标识
 * @param turnId 所属 Turn
 * @param operationId 已持久化的执行操作
 * @param commandDigest 命令、环境和权限身份的 SHA-256
 * @param destinations 精确公共 HTTPS 主机和端口
 * @param limits 总传输与连接限制
 * @param expiresAt 不可续期的截止时间
 */
public record CommandNetworkGrant(
        String id,
        TurnId turnId,
        String operationId,
        String commandDigest,
        NetworkPermission destinations,
        Limits limits,
        Instant expiresAt) {
    /** 拒绝通配目标、IP 字面量与不明确的授权。 */
    public CommandNetworkGrant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(destinations, "destinations");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!commandDigest.matches("[a-f0-9]{64}")
                || !destinations.tlsOnly()
                || destinations.hosts().isEmpty()
                || destinations.ports().isEmpty()
                || destinations.hosts().contains(NetworkPermission.ANY_HOST)
                || destinations.ports().contains(NetworkPermission.ANY_PORT)
                || destinations.hosts().stream()
                        .anyMatch(host -> !host.matches("[a-z0-9.-]+") || host.matches("[0-9.]+"))) {
            throw new IllegalArgumentException("命令网络租约必须绑定精确公共 HTTPS 主机");
        }
    }

    /**
     * 租约资源上限。
     *
     * @param maximumBytes 双向总字节上限，正数
     * @param maximumConnections 同时活动连接上限，1 到 64
     * @param idleTimeout 无数据时限，正数
     */
    public record Limits(long maximumBytes, int maximumConnections, Duration idleTimeout) {
        /** 校验不会产生无限资源授权。 */
        public Limits {
            if (maximumBytes < 1
                    || maximumConnections < 1
                    || maximumConnections > 64
                    || idleTimeout == null
                    || idleTimeout.isNegative()
                    || idleTimeout.isZero()) {
                throw new IllegalArgumentException("命令代理资源限制无效");
            }
        }
    }
}
