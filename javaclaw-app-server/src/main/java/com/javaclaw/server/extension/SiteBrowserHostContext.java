package com.javaclaw.server.extension;

import java.time.Clock;
import java.util.Objects;

import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.InputRequestService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.persistence.ProviderService;
import com.javaclaw.server.site.account.SiteAccountService;

/**
 * 组合根为 Site 浏览器绑定的宿主依赖；Worker 不接触这些服务。
 *
 * @param database 唯一 H2 所有者
 * @param core Thread 与 Turn 权威查询
 * @param accounts 共享账号与登录态服务
 * @param attachments 现有附件服务
 * @param permissions 实时权限复核
 * @param providers 精确模型能力声明
 * @param inputs 现有聊天输入请求通道
 * @param json 共享规范 codec
 * @param clock 平台时钟
 */
public record SiteBrowserHostContext(
        H2Database database,
        CoreCommandService core,
        SiteAccountService accounts,
        AttachmentService attachments,
        PermissionProfileService permissions,
        ProviderService providers,
        InputRequestService inputs,
        CanonicalJson json,
        Clock clock) {
    /** 拒绝缺失的宿主边界。 */
    public SiteBrowserHostContext {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(core, "core");
        Objects.requireNonNull(accounts, "accounts");
        Objects.requireNonNull(attachments, "attachments");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(providers, "providers");
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(clock, "clock");
    }
}
