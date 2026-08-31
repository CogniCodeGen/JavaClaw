package com.javaclaw.sdk.model;

import java.net.URI;
import java.time.Instant;
import java.util.Set;

/**
 * SDK 站点元数据；新建时 id 可为空、revision 为 0，updatedAt 可为空。
 *
 * @param id 稳定标识
 * @param workspaceId 工作区
 * @param name 展示名称
 * @param origin 起始准确源
 * @param allowedOrigins 明确授权来源
 * @param enabled 是否启用
 * @param revision 权限版本
 * @param updatedAt 修改时间
 */
public record BrowserSiteInfo(
        String id,
        String workspaceId,
        String name,
        URI origin,
        Set<URI> allowedOrigins,
        boolean enabled,
        long revision,
        Instant updatedAt) {
    /** 固定来源快照，不接收凭据或 Cookie。 */
    public BrowserSiteInfo {
        allowedOrigins = Set.copyOf(allowedOrigins);
    }
}
