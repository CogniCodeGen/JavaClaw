package com.javaclaw.client.extension;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BrowserGrantContracts;
import com.javaclaw.builtin.contracts.BrowserResult;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** 当前 Thread 的常驻浏览器 SDK；所有操作使用现有 Site 扩展领域通道。 */
public final class BrowserClient {
    private final ExtensionClient extensions;
    private final CanonicalJson json = new CanonicalJson();

    /** @param extensions 已初始化的扩展客户端 */
    public BrowserClient(ExtensionClient extensions) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
    }

    /** 人工发起的有限控制权操作；不接收网页正文或脚本。 */
    public enum Control {
        TAKE_OVER("browser.takeover"),
        RETURN("browser.return"),
        CLOSE("browser.close");
        private final String operation;

        Control(String operation) {
            this.operation = operation;
        }
    }

    /**
     * @param workspace 所属 Workspace
     * @param thread 当前 Thread
     * @return 权威可用性与会话状态，不获取页面正文
     */
    public BrowserCommands.Status status(WorkspaceId workspace, ThreadId thread) {
        return json.decode(
                extensions
                        .query(call(workspace, thread, "browser.status", Map.of()))
                        .payload(),
                BrowserCommands.Status.class);
    }

    /**
     * @param workspace 所属 Workspace
     * @param thread 当前 Thread
     * @return 浏览器专用授权列表，不扩大其他工具权限
     */
    public BrowserGrantContracts.GrantList grants(WorkspaceId workspace, ThreadId thread) {
        return json.decode(
                extensions
                        .query(call(workspace, thread, "browser.grants", Map.of()))
                        .payload(),
                BrowserGrantContracts.GrantList.class);
    }

    /**
     * @param workspace 所属 Workspace
     * @param thread 当前 Thread
     * @param origin 用户输入的精确 HTTPS 来源
     * @return 服务端生成的完整预览；未产生授权
     */
    public BrowserGrantContracts.Preview previewGrant(WorkspaceId workspace, ThreadId thread, URI origin) {
        return json.decode(
                extensions
                        .query(call(workspace, thread, "browser.grant.preview", Map.of("origin", origin)))
                        .payload(),
                BrowserGrantContracts.Preview.class);
    }

    /**
     * @param workspace 所属 Workspace
     * @param thread 当前 Thread
     * @param preview 用户明确确认且未修改的服务端预览
     * @param options 独立确认身份，期望版本为零
     * @return 已确认授权；当前 Turn 的冻结范围保持不变
     */
    public BrowserGrantContracts.Grant confirmGrant(
            WorkspaceId workspace, ThreadId thread, BrowserGrantContracts.Preview preview, CommandOptions options) {
        return json.decode(
                extensions
                        .command(call(workspace, thread, "browser.grant.confirm", preview), options)
                        .payload(),
                BrowserGrantContracts.Grant.class);
    }

    /**
     * @param workspace 所属 Workspace
     * @param thread 当前 Thread
     * @param grantId 要撤销的当前对话授权
     * @param options 用户看到的授权版本及独立幂等身份
     * @return 已撤销授权；后续动作和网络请求立即复核此版本
     */
    public BrowserGrantContracts.Grant revokeGrant(
            WorkspaceId workspace, ThreadId thread, String grantId, CommandOptions options) {
        return json.decode(
                extensions
                        .command(call(workspace, thread, "browser.grant.revoke", Map.of("id", grantId)), options)
                        .payload(),
                BrowserGrantContracts.Grant.class);
    }

    /**
     * @param workspace 所属 Workspace
     * @param thread 当前 Thread
     * @param control 明确人工动作
     * @param options 用户操作时看到的 lease generation 与独立幂等键
     * @return 操作后的权威状态
     */
    public BrowserCommands.Status control(
            WorkspaceId workspace, ThreadId thread, Control control, CommandOptions options) {
        return json.decode(
                extensions
                        .command(call(workspace, thread, control.operation, Map.of()), options)
                        .payload(),
                BrowserCommands.Status.class);
    }

    /**
     * @param workspace 所属 Workspace
     * @param thread 当前 Thread
     * @param request 已由用户确认的页面与可选账号
     * @param options 本次打开命令身份
     * @return 有界页面观察；宿主仍校验精确 Origin 授权
     */
    public BrowserResult open(
            WorkspaceId workspace, ThreadId thread, BrowserCommands.Open request, CommandOptions options) {
        return result(workspace, thread, "browser.open", request, options);
    }

    /**
     * @param workspace 所属 Workspace
     * @param thread 当前 Thread
     * @param request 有界浏览动作，不允许输入密码或宿主路径
     * @param options 当前会话版本与幂等键
     * @return 有界页面观察和可信附件引用
     */
    public BrowserResult act(
            WorkspaceId workspace, ThreadId thread, BrowserCommands.Act request, CommandOptions options) {
        return result(workspace, thread, "browser.act", request, options);
    }

    /**
     * @param workspace 所属 Workspace
     * @param thread 当前 Thread
     * @param options 人工租约代次与幂等键
     * @return 当前可供用户选择的脱敏表单
     */
    public BrowserCommands.LoginForms loginForms(WorkspaceId workspace, ThreadId thread, CommandOptions options) {
        return json.decode(
                extensions
                        .command(call(workspace, thread, "browser.login.forms", Map.of()), options)
                        .payload(),
                BrowserCommands.LoginForms.class);
    }

    /**
     * @param workspace 所属 Workspace
     * @param thread 当前 Thread
     * @param target 用户明确选择的表单引用
     * @param options 人工租约代次与幂等键
     * @return 已保存账号的脱敏投影，秘密始终在宿主和 Worker 的私有通道
     */
    public SiteAccountContracts.AccountProjection capture(
            WorkspaceId workspace, ThreadId thread, BrowserContracts.CredentialsTarget target, CommandOptions options) {
        return json.decode(
                extensions
                        .command(call(workspace, thread, "browser.capture", target), options)
                        .payload(),
                SiteAccountContracts.AccountProjection.class);
    }

    /**
     * @param workspace 所属 Workspace
     * @param thread 当前 Thread
     * @param options 人工租约代次与幂等键
     * @return 选择保持登录后的脱敏账号状态
     */
    public SiteAccountContracts.AccountProjection saveLogin(
            WorkspaceId workspace, ThreadId thread, CommandOptions options) {
        return json.decode(
                extensions
                        .command(call(workspace, thread, "browser.save", Map.of()), options)
                        .payload(),
                SiteAccountContracts.AccountProjection.class);
    }

    private BrowserResult result(
            WorkspaceId workspace, ThreadId thread, String operation, Object payload, CommandOptions options) {
        return json.decode(
                extensions
                        .command(call(workspace, thread, operation, payload), options)
                        .payload(),
                BrowserResult.class);
    }

    private ExtensionRpcContracts.CallPayload call(
            WorkspaceId workspace, ThreadId thread, String operation, Object payload) {
        return new ExtensionRpcContracts.CallPayload(
                BuiltinExtensionIds.SITE,
                workspace,
                Optional.of(thread),
                Optional.empty(),
                operation,
                json.encode(payload));
    }
}
