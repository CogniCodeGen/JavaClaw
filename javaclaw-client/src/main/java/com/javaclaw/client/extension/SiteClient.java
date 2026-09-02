package com.javaclaw.client.extension;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.builtin.contracts.SiteManagementContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** Site 文档、受控检索与隔离人工登录的强类型 SDK facade。 */
public final class SiteClient {
    private final SearchableDocumentClient<
                    SiteContracts.Projection, SiteContracts.SearchRequest, SiteContracts.SearchResult>
            documents;
    private final ExtensionClient extensions;
    private final CanonicalJson json = new CanonicalJson();

    /**
     * 创建 Site facade。
     *
     * @param extensions 已初始化扩展客户端
     */
    public SiteClient(ExtensionClient extensions) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
        documents = new SearchableDocumentClient<>(
                extensions,
                com.javaclaw.builtin.contracts.BuiltinExtensionIds.SITE,
                SiteContracts.Projection.class,
                SiteContracts.SearchResult.class);
    }

    /**
     * 按标识读取 Site。
     *
     * @param workspaceId Workspace 标识
     * @param id Site 标识
     * @return 不含 CredentialRef 或 PrivateNetworkGrantRef 的当前 Site
     */
    public SiteContracts.Projection read(WorkspaceId workspaceId, String id) {
        return documents.read(workspaceId, id);
    }

    /**
     * 分页列出 Site。
     *
     * @param workspaceId Workspace 标识
     * @param afterKey 上一页游标，首页传空字符串
     * @param limit 本页上限
     * @return Site 分页结果
     */
    public TypedDocumentPage<SiteContracts.Projection> list(WorkspaceId workspaceId, String afterKey, int limit) {
        return documents.list(workspaceId, afterKey, limit);
    }

    /**
     * 创建一个不带凭据或私网授权的 Site。
     *
     * @param workspaceId Workspace 标识
     * @param request 强类型非 Secret 权限表面
     * @param options 幂等键；expected revision 必须为零
     * @return 已保存的脱敏投影
     */
    public SiteContracts.Projection create(
            WorkspaceId workspaceId, SiteManagementContracts.SaveRequest request, CommandOptions options) {
        return save(workspaceId, "site/create", request, options);
    }

    /**
     * 按当前 revision 编辑 Site 的非 Secret 权限表面。
     *
     * <p>Origin 或 allowlist 变化时，服务端会清除旧 CredentialRef 和 PrivateNetworkGrantRef，并使旧会话失效。
     *
     * @param workspaceId Workspace 标识
     * @param request 强类型非 Secret 权限表面
     * @param options 幂等键与当前 revision
     * @return 已保存的脱敏投影
     */
    public SiteContracts.Projection update(
            WorkspaceId workspaceId, SiteManagementContracts.SaveRequest request, CommandOptions options) {
        return save(workspaceId, "site/update", request, options);
    }

    /**
     * 把已有 Site namespace Secret 绑定为 HTTP 凭据。
     *
     * @param workspaceId Workspace 标识
     * @param request Site、authority revision 与 Vault opaque ID
     * @param options 幂等键与当前 Site revision
     * @return 更新后的脱敏投影
     */
    public SiteContracts.Projection bindCredential(
            WorkspaceId workspaceId, SiteManagementContracts.CredentialBindRequest request, CommandOptions options) {
        return mutateAuthority(workspaceId, "site/credential/bind", request, options);
    }

    /**
     * 清除 Site HTTP 凭据绑定。
     *
     * @param workspaceId Workspace 标识
     * @param request Site 与 authority revision
     * @param options 幂等键与当前 Site revision
     * @return 更新后的脱敏投影
     */
    public SiteContracts.Projection clearCredential(
            WorkspaceId workspaceId, SiteManagementContracts.AuthorityClearRequest request, CommandOptions options) {
        return mutateAuthority(workspaceId, "site/credential/clear", request, options);
    }

    /**
     * 绑定一个仍有效且 Origin 精确匹配的 Site 私网授权。
     *
     * @param workspaceId Workspace 标识
     * @param request Site、authority revision 与授权 ID
     * @param options 幂等键与当前 Site revision
     * @return 更新后的脱敏投影
     */
    public SiteContracts.Projection bindPrivateNetwork(
            WorkspaceId workspaceId,
            SiteManagementContracts.PrivateNetworkBindRequest request,
            CommandOptions options) {
        return mutateAuthority(workspaceId, "site/privateNetwork/bind", request, options);
    }

    /**
     * 清除 Site 私网授权绑定。
     *
     * @param workspaceId Workspace 标识
     * @param request Site 与 authority revision
     * @param options 幂等键与当前 Site revision
     * @return 更新后的脱敏投影
     */
    public SiteContracts.Projection clearPrivateNetwork(
            WorkspaceId workspaceId, SiteManagementContracts.AuthorityClearRequest request, CommandOptions options) {
        return mutateAuthority(workspaceId, "site/privateNetwork/clear", request, options);
    }

    /**
     * 条件删除 Site。
     *
     * @param workspaceId Workspace 标识
     * @param id Site 标识
     * @param options 幂等键与预期 revision
     * @return 删除确认
     */
    public DocumentContracts.Deleted delete(WorkspaceId workspaceId, String id, CommandOptions options) {
        return documents.delete(workspaceId, id, options);
    }

    /**
     * 检索启用 Site。
     *
     * @param workspaceId Workspace 标识
     * @param request 受控检索请求
     * @return 脱敏检索结果
     */
    public SiteContracts.SearchResult search(WorkspaceId workspaceId, SiteContracts.SearchRequest request) {
        return documents.search(workspaceId, request);
    }

    private SiteContracts.Projection save(
            WorkspaceId workspaceId,
            String operation,
            SiteManagementContracts.SaveRequest request,
            CommandOptions options) {
        ExtensionRpcContracts.CallResult result = extensions.command(
                call(workspaceId, operation, Objects.requireNonNull(request, "request")),
                Objects.requireNonNull(options, "options"));
        SiteContracts.Projection saved = json.decode(result.payload(), SiteContracts.Projection.class);
        if (result.revision() != saved.revision()) {
            throw new IllegalArgumentException("Site save revision does not match its projection");
        }
        return saved;
    }

    private SiteContracts.Projection mutateAuthority(
            WorkspaceId workspaceId, String operation, Object request, CommandOptions options) {
        ExtensionRpcContracts.CallResult result = extensions.command(
                call(workspaceId, operation, Objects.requireNonNull(request, "request")),
                Objects.requireNonNull(options, "options"));
        SiteContracts.Projection saved = json.decode(result.payload(), SiteContracts.Projection.class);
        if (result.revision() != saved.revision()) {
            throw new IllegalArgumentException("Site authority revision does not match its projection");
        }
        return saved;
    }

    /**
     * 启动一个最长十分钟的隔离登录窗口。
     *
     * @param workspaceId Workspace 标识
     * @param request Site 与 authority revision
     * @param options 幂等键；预期文档 revision 必须为零
     * @return 不含 URL、Cookie 或 storage state 的会话投影
     */
    public SiteContracts.LoginSession beginLogin(
            WorkspaceId workspaceId, SiteContracts.LoginBeginRequest request, CommandOptions options) {
        ExtensionRpcContracts.CallResult result = extensions.command(
                call(workspaceId, "login.begin", request), Objects.requireNonNull(options, "options"));
        return json.decode(result.payload(), SiteContracts.LoginSession.class);
    }

    /**
     * 查询一个登录会话的脱敏状态。
     *
     * @param workspaceId Workspace 标识
     * @param request 会话标识
     * @return 脱敏会话投影
     */
    public SiteContracts.LoginSession loginStatus(WorkspaceId workspaceId, SiteContracts.LoginControlRequest request) {
        return json.decode(
                extensions.query(call(workspaceId, "login.status", request)).payload(),
                SiteContracts.LoginSession.class);
    }

    /**
     * 列出当前 Workspace 的登录会话及原生能力状态。
     *
     * @param workspaceId Workspace 标识
     * @return 脱敏会话与可用性投影
     */
    public SiteContracts.LoginSessionList loginSessions(WorkspaceId workspaceId) {
        return json.decode(
                extensions.query(call(workspaceId, "login.list", Map.of())).payload(),
                SiteContracts.LoginSessionList.class);
    }

    /**
     * 把 Worker storage state 直接密封进 Vault，并切换 Site authority。
     *
     * @param workspaceId Workspace 标识
     * @param request 会话标识
     * @param options 幂等键；预期文档 revision 必须为零
     * @return 脱敏 Site 投影、已配置状态和会话；不返回 CredentialRef 或 Secret
     */
    public SiteContracts.LoginSaveResult saveLogin(
            WorkspaceId workspaceId, SiteContracts.LoginControlRequest request, CommandOptions options) {
        ExtensionRpcContracts.CallResult result = extensions.command(
                call(workspaceId, "login.save", request), Objects.requireNonNull(options, "options"));
        SiteContracts.LoginSaveResult saved = json.decode(result.payload(), SiteContracts.LoginSaveResult.class);
        if (result.revision() != saved.site().revision()) {
            throw new IllegalArgumentException("Site login save revision does not match its Site");
        }
        return saved;
    }

    /**
     * 取消登录并销毁隔离 Chromium 进程。
     *
     * @param workspaceId Workspace 标识
     * @param request 会话标识
     * @param options 幂等键；预期文档 revision 必须为零
     * @return 已取消的脱敏会话投影
     */
    public SiteContracts.LoginSession cancelLogin(
            WorkspaceId workspaceId, SiteContracts.LoginControlRequest request, CommandOptions options) {
        ExtensionRpcContracts.CallResult result = extensions.command(
                call(workspaceId, "login.cancel", request), Objects.requireNonNull(options, "options"));
        return json.decode(result.payload(), SiteContracts.LoginSession.class);
    }

    private ExtensionRpcContracts.CallPayload call(WorkspaceId workspaceId, String operation, Object payload) {
        return new ExtensionRpcContracts.CallPayload(
                com.javaclaw.builtin.contracts.BuiltinExtensionIds.SITE,
                Objects.requireNonNull(workspaceId, "workspaceId"),
                Optional.empty(),
                Optional.empty(),
                operation,
                json.encode(payload));
    }
}
