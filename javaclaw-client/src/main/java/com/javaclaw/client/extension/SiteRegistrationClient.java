package com.javaclaw.client.extension;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** 网站登记的强类型 SDK；调用只绑定 Workspace，秘密和浏览器状态始终留在 Worker 与宿主私有通道。 */
public final class SiteRegistrationClient {
    private final ExtensionClient extensions;
    private final CanonicalJson json = new CanonicalJson();

    /**
     * 创建网站登记边界。
     *
     * @param extensions 已初始化的扩展客户端
     */
    public SiteRegistrationClient(ExtensionClient extensions) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
    }

    /**
     * 打开可见隔离浏览器，尚不创建网站或账号。
     *
     * @param workspace 固定 Workspace
     * @param request 用户输入的 HTTPS 地址
     * @param options 本次启动的稳定幂等身份，expected revision 为零
     * @return 不含秘密的登记会话
     */
    public SiteRegistrationContracts.Session begin(
            WorkspaceId workspace, SiteRegistrationContracts.BeginRequest request, CommandOptions options) {
        return command(workspace, "registration.begin", request, options);
    }

    /**
     * 查询会话和已完成结果；页面地址不包含查询或片段，凭据候选不包含输入值。
     *
     * @param workspace 原始 Workspace
     * @param request 登记会话身份
     * @return 最新脱敏会话，供轮询和不确定提交后的恢复使用
     */
    public SiteRegistrationContracts.Session status(
            WorkspaceId workspace, SiteRegistrationContracts.SessionRequest request) {
        return json.decode(extensions.query(call(workspace, "registration.status", request)).payload(),
                SiteRegistrationContracts.Session.class);
    }

    /**
     * 将用户明确输入并确认的来源加入本登记会话，不授予聊天 Thread 或其他工具权限。
     *
     * @param workspace 原始 Workspace
     * @param request 会话、当前代次和精确 HTTPS 来源
     * @param options 本次确认的稳定幂等身份，expected revision 为零
     * @return 更新后的脱敏会话
     */
    public SiteRegistrationContracts.Session allowOrigin(
            WorkspaceId workspace, SiteRegistrationContracts.OriginRequest request, CommandOptions options) {
        return command(workspace, "registration.origin", request, options);
    }

    /**
     * 一次确认网站、默认账号、选定凭据及当前登录态；不接收任何明文秘密。
     *
     * @param workspace 原始 Workspace
     * @param request 固定页面版本、凭据候选身份和用户名称
     * @param options 本次完成的稳定幂等身份，expected revision 为零
     * @return 含已登记网站身份的会话；通信失败后应查询状态，不能以新身份自动重放
     */
    public SiteRegistrationContracts.Session complete(
            WorkspaceId workspace, SiteRegistrationContracts.CompleteRequest request, CommandOptions options) {
        return command(workspace, "registration.complete", request, options);
    }

    /**
     * 取消未完成登记并释放隔离浏览器和临时秘密，不删除已完成的网站。
     *
     * @param workspace 原始 Workspace
     * @param request 登记会话身份
     * @param options 本次取消的稳定幂等身份，expected revision 为零
     * @return 最终脱敏会话
     */
    public SiteRegistrationContracts.Session cancel(
            WorkspaceId workspace, SiteRegistrationContracts.SessionRequest request, CommandOptions options) {
        return command(workspace, "registration.cancel", request, options);
    }

    private SiteRegistrationContracts.Session command(
            WorkspaceId workspace, String operation, Object request, CommandOptions options) {
        CommandOptions checked = Objects.requireNonNull(options, "options");
        if (checked.expectedRevision() != 0) {
            throw new IllegalArgumentException("网站登记命令 expected revision 必须为零");
        }
        return json.decode(extensions.command(call(workspace, operation, request), checked).payload(),
                SiteRegistrationContracts.Session.class);
    }

    private ExtensionRpcContracts.CallPayload call(WorkspaceId workspace, String operation, Object request) {
        return new ExtensionRpcContracts.CallPayload(BuiltinExtensionIds.SITE,
                Objects.requireNonNull(workspace, "workspace"), Optional.empty(), Optional.empty(), operation,
                json.encode(Objects.requireNonNull(request, "request")));
    }
}
