package com.javaclaw.sdk.model;

import java.net.URI;
import java.time.Instant;
import java.util.Set;

/**
 * SDK 私网授权；新建时 id、updatedAt 可为空，revision 为 0。
 *
 * @param id 授权标识
 * @param workspaceId 唯一工作区
 * @param purpose WEB/BROWSER/MCP/OAUTH
 * @param origin 准确源
 * @param addresses 准确私网 IP 列表，不接受通配网段
 * @param expiresAt 到期时间
 * @param enabled 是否启用
 * @param revision 乐观锁版本
 * @param updatedAt 修改时间
 */
public record NetworkGrantInfo(
        String id,
        String workspaceId,
        String purpose,
        URI origin,
        Set<String> addresses,
        Instant expiresAt,
        boolean enabled,
        long revision,
        Instant updatedAt) {
    /** 固定准确地址清单。 */
    public NetworkGrantInfo {
        addresses = Set.copyOf(addresses);
    }
}
