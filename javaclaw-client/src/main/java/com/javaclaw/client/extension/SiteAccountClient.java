package com.javaclaw.client.extension;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteAccountContracts.AccountProjection;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** Site 多账号管理的强类型 SDK，秘密仅在进入 wire 前密封。 */
public final class SiteAccountClient {
    private final ExtensionClient extensions;
    private final CanonicalJson json = new CanonicalJson();

    /**
     * 构造账号 facade。
     *
     * @param extensions 已初始化的扩展客户端
     */
    public SiteAccountClient(ExtensionClient extensions) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
    }

    /**
     * 列出网站账号。
     *
     * @param workspace 所属 Workspace
     * @param siteId 网站 ID
     * @return 脱敏账号列表
     */
    public SiteAccountContracts.AccountList list(WorkspaceId workspace, String siteId) {
        var result = extensions.query(call(workspace, "account/list", new SiteAccountContracts.ListRequest(siteId)));
        return json.decode(result.payload(), SiteAccountContracts.AccountList.class);
    }

    /**
     * 创建空账号。
     *
     * @param workspace 所属 Workspace
     * @param request 网站和用户指定的展示名称
     * @param options 幂等身份，expected revision 为零
     * @return 已创建账号
     */
    public AccountProjection create(
            WorkspaceId workspace, SiteAccountContracts.CreateRequest request, CommandOptions options) {
        return command(workspace, "account/create", request, options);
    }

    /**
     * 更新账号名称与启用状态。
     *
     * @param workspace 所属 Workspace
     * @param request 账号元数据
     * @param options 幂等身份与当前账号版本
     * @return 已更新账号
     */
    public AccountProjection update(
            WorkspaceId workspace, SiteAccountContracts.UpdateRequest request, CommandOptions options) {
        return command(workspace, "account/update", request, options);
    }

    /**
     * 执行设置默认、注销或删除等明确账号操作。
     *
     * @param workspace 所属 Workspace
     * @param selection 账号选择
     * @param operation 仅 account/default、account/logout 或 account/delete
     * @param options 幂等身份与当前账号版本
     * @return 脱敏操作回执
     */
    public AccountProjection control(
            WorkspaceId workspace, SiteAccountContracts.Selection selection, String operation, CommandOptions options) {
        if (!java.util.Set.of("account/default", "account/logout", "account/delete")
                .contains(operation)) {
            throw new IllegalArgumentException("不支持的账号控制操作");
        }
        return command(workspace, operation, selection, options);
    }

    /**
     * 在 JSON-RPC 编码前密封用户名密码；调用方在返回后清零原始数组。
     *
     * @param workspace 所属 Workspace
     * @param request 用户确认的账号与安全版本
     * @param username 临时用户名字符
     * @param password 临时密码字符
     * @param options 幂等身份与当前账号版本
     * @return 不含用户名密码的账号状态
     */
    public AccountProjection setCredential(
            WorkspaceId workspace,
            SiteAccountContracts.CredentialRequest request,
            char[] username,
            char[] password,
            CommandOptions options) {
        char[] combined = combine(username, password);
        try {
            var result =
                    extensions.secretCommand(call(workspace, "account/credential/set", request), combined, options);
            return json.decode(result.payload(), AccountProjection.class);
        } finally {
            Arrays.fill(combined, '\0');
        }
    }

    private AccountProjection command(WorkspaceId workspace, String operation, Object request, CommandOptions options) {
        return json.decode(
                extensions.command(call(workspace, operation, request), options).payload(), AccountProjection.class);
    }

    private ExtensionRpcContracts.CallPayload call(WorkspaceId workspace, String operation, Object request) {
        return new ExtensionRpcContracts.CallPayload(
                BuiltinExtensionIds.SITE,
                workspace,
                Optional.empty(),
                Optional.empty(),
                operation,
                json.encode(request));
    }

    private static char[] combine(char[] username, char[] password) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        if (username.length == 0 || password.length == 0 || username.length + password.length > 16_000) {
            throw new IllegalArgumentException("用户名密码为空或超出长度限制");
        }
        char[] result = Arrays.copyOf(username, username.length + password.length + 1);
        System.arraycopy(password, 0, result, username.length + 1, password.length);
        for (int i = 0; i < result.length; i++) {
            if (i != username.length && result[i] == '\0') {
                Arrays.fill(result, '\0');
                throw new IllegalArgumentException("用户名或密码不能包含 NUL");
            }
        }
        return result;
    }
}
