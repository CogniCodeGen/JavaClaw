package com.javaclaw.server.network;

import java.net.URI;
import java.time.Instant;
import java.util.Set;

/**
 * 用户确认的私网端点授权，不是原始网络或通配网段许可。
 *
 * @param id 稳定授权标识
 * @param workspaceId 唯一适用工作区
 * @param purpose WEB、BROWSER、MCP 或 OAUTH，用途之间不互相继承
 * @param origin 包含协议与端口的准确源，不含路径和凭据
 * @param addresses 已确认的准确私网 IP 集合；DNS 结果必须逐个匹配
 * @param expiresAt 过期时间，创建时最多三十天
 * @param enabled 是否仍启用
 * @param revision 乐观锁版本
 * @param updatedAt 最后修改时间
 */
public record NetworkGrant(
        String id,
        String workspaceId,
        String purpose,
        URI origin,
        Set<String> addresses,
        Instant expiresAt,
        boolean enabled,
        long revision,
        Instant updatedAt) {
    /** 防御性复制明确的 IP 清单；持久化前还需验证工作区、地址类型和有效期。 */
    public NetworkGrant {
        addresses = Set.copyOf(addresses);
    }
}
