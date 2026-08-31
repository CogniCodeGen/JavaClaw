package com.javaclaw.protocol;

import java.util.List;

/**
 * 显式私网授权的非敏感协议视图。
 *
 * @param id 授权标识
 * @param workspaceId 唯一适用工作区
 * @param purpose WEB/BROWSER/MCP/OAUTH
 * @param origin 准确端点源
 * @param addresses 明确批准的 IP 列表
 * @param expiresAt ISO-8601 到期时间
 * @param enabled 是否启用
 * @param revision 配置版本
 * @param updatedAt ISO-8601 修改时间
 */
public record WireNetworkGrant(
        String id,
        String workspaceId,
        String purpose,
        String origin,
        List<String> addresses,
        String expiresAt,
        boolean enabled,
        long revision,
        String updatedAt) {
    /** 固定准确 IP 列表。 */
    public WireNetworkGrant {
        addresses = List.copyOf(addresses);
    }
}
