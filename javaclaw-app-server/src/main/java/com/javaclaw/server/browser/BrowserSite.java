package com.javaclaw.server.browser;

import java.net.URI;
import java.time.Instant;
import java.util.Set;

/**
 * 站点配置及授权边界，Cookie 和凭据仅存在 SecretStore。
 *
 * @param id 稳定站点标识
 * @param workspaceId 唯一适用工作区
 * @param name 展示名称
 * @param origin 起始准确 HTTP(S) 源
 * @param allowedOrigins 明确允许的页面、登录和静态资源源集合，不允许通配符
 * @param enabled 是否启用；禁用立即撤销现存会话执行资格
 * @param revision 权限绑定版本
 * @param updatedAt 修改时间
 */
public record BrowserSite(
        String id,
        String workspaceId,
        String name,
        URI origin,
        Set<URI> allowedOrigins,
        boolean enabled,
        long revision,
        Instant updatedAt) {
    /** 复制来源集合，防止权限快照被外部修改。 */
    public BrowserSite {
        allowedOrigins = Set.copyOf(allowedOrigins);
    }
}
