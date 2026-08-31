package com.javaclaw.protocol;

import java.util.List;

/**
 * 站点 Wire 元数据，不含凭据、Cookie 或会话正文。
 *
 * @param id 站点标识
 * @param workspaceId 工作区标识
 * @param name 展示名称
 * @param origin 准确 HTTP(S) 源
 * @param allowedOrigins 明确允许的来源列表
 * @param enabled 是否启用
 * @param revision 配置与权限版本
 * @param updatedAt ISO-8601 修改时间
 */
public record WireBrowserSite(
        String id,
        String workspaceId,
        String name,
        String origin,
        List<String> allowedOrigins,
        boolean enabled,
        long revision,
        String updatedAt) {
    /** 固定来源快照，防止调用方修改权限描述。 */
    public WireBrowserSite {
        allowedOrigins = List.copyOf(allowedOrigins);
    }
}
