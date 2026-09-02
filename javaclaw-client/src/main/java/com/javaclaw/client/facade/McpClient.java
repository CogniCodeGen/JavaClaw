package com.javaclaw.client.facade;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.McpCatalogKind;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpCatalogRefresh;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpHealth;
import com.javaclaw.api.McpOAuthAuthorization;
import com.javaclaw.api.McpPromptPage;
import com.javaclaw.api.McpPromptResult;
import com.javaclaw.api.McpResourcePage;
import com.javaclaw.api.McpResourceReadResult;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.McpRpcContracts;

/** MCP Endpoint、健康与 Catalog 的强类型 SDK facade。 */
public final class McpClient {
    private final RpcClientConnection connection;

    /** @param connection 已初始化连接 */
    public McpClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /** @param workspaceId Workspace @return 最新 Endpoint */
    public List<McpEndpoint> list(WorkspaceId workspaceId) {
        return connection
                .query(
                        "mcp/endpoint/list",
                        new McpRpcContracts.WorkspaceQuery(workspaceId),
                        McpRpcContracts.EndpointListResult.class)
                .endpoints();
    }

    /** @param endpointId 标识 @return 最新 Endpoint */
    public McpEndpoint read(String endpointId) {
        return connection.query("mcp/endpoint/read", new McpRpcContracts.EndpointQuery(endpointId), McpEndpoint.class);
    }

    /** @param endpointId 标识 @return 不可变历史 */
    public List<McpEndpoint> history(String endpointId) {
        return connection
                .query(
                        "mcp/endpoint/history",
                        new McpRpcContracts.EndpointQuery(endpointId),
                        McpRpcContracts.EndpointListResult.class)
                .endpoints();
    }

    /**
     * 创建用户 HTTPS Endpoint。
     *
     * @param endpointId 标识
     * @param spec HTTPS 配置
     * @param options expected revision 必须为零
     * @return 默认停用的 Endpoint
     */
    public McpEndpoint create(String endpointId, McpEndpointSpec spec, CommandOptions options) {
        return connection.command(
                "mcp/endpoint/create",
                new McpRpcContracts.EndpointWritePayload(endpointId, spec),
                options,
                McpEndpoint.class);
    }

    /**
     * 更新用户 HTTPS Endpoint。
     *
     * @param endpointId 标识
     * @param spec 完整配置
     * @param options 当前 revision 与幂等键
     * @return 新版本
     */
    public McpEndpoint update(String endpointId, McpEndpointSpec spec, CommandOptions options) {
        return connection.command(
                "mcp/endpoint/update",
                new McpRpcContracts.EndpointWritePayload(endpointId, spec),
                options,
                McpEndpoint.class);
    }

    /**
     * 从已安装并启用的签名 Bundle 注册 stdio MCP；客户端不能提供可执行路径。
     *
     * @param request Bundle、Workspace 与非敏感显示配置
     * @param options expected revision 必须为零
     * @return 默认停用的 Endpoint
     */
    public McpEndpoint registerSignedBundle(
            McpRpcContracts.SignedBundleRegisterPayload request, CommandOptions options) {
        return connection.command("mcp/stdio/register", request, options, McpEndpoint.class);
    }

    /** @param endpointId 标识 @param options 当前 revision @return 启用版本 */
    public McpEndpoint enable(String endpointId, CommandOptions options) {
        return state("mcp/endpoint/enable", endpointId, options);
    }

    /** @param endpointId 标识 @param options 当前 revision @return 停用版本 */
    public McpEndpoint disable(String endpointId, CommandOptions options) {
        return state("mcp/endpoint/disable", endpointId, options);
    }

    /** @param endpointId 标识 @return 最新健康投影 */
    public McpHealth health(String endpointId) {
        return connection.query("mcp/health/read", new McpRpcContracts.EndpointQuery(endpointId), McpHealth.class);
    }

    /** @param endpointId 标识 @return 新健康检查结果 */
    public McpHealth probe(String endpointId) {
        return connection.query("mcp/health/probe", new McpRpcContracts.EndpointQuery(endpointId), McpHealth.class);
    }

    /**
     * 原子刷新 Catalog。
     *
     * @param endpointId 标识
     * @param options 当前 Endpoint revision
     * @return 携带新 Catalog revision 的 Endpoint
     */
    public McpEndpoint refreshCatalog(String endpointId, CommandOptions options) {
        return connection.command(
                "mcp/catalog/refresh", new McpRpcContracts.EndpointQuery(endpointId), options, McpEndpoint.class);
    }

    /** @param endpointId 标识 @return 最近一次分页刷新进度；从未刷新时为空 */
    public Optional<McpCatalogRefresh> catalogRefresh(String endpointId) {
        return connection
                .query(
                        "mcp/catalog/refresh/read",
                        new McpRpcContracts.EndpointQuery(endpointId),
                        McpRpcContracts.CatalogRefreshResult.class)
                .refresh();
    }

    /**
     * 读取一页权威 Catalog。
     *
     * @param endpointId 标识
     * @param kind 可选类型
     * @param cursor 可选 cursor
     * @param limit 本页上限
     * @return Catalog 页
     */
    public McpCatalogPage catalog(
            String endpointId, Optional<McpCatalogKind> kind, Optional<String> cursor, int limit) {
        return connection
                .query(
                        "mcp/catalog/list",
                        new McpRpcContracts.CatalogQuery(endpointId, kind, cursor, limit),
                        McpRpcContracts.CatalogResult.class)
                .page();
    }

    /**
     * 读取 HTTPS MCP 的一页外部 Resource 描述。
     *
     * @param endpointId Endpoint 标识
     * @param cursor 可选远端 cursor
     * @return 强类型外部 Resource 页
     */
    public McpResourcePage resources(String endpointId, Optional<String> cursor) {
        return connection
                .query(
                        "mcp/resource/list",
                        new McpRpcContracts.ExternalPageQuery(endpointId, cursor),
                        McpRpcContracts.ResourcePageResult.class)
                .page();
    }

    /**
     * 显式读取一个 HTTPS MCP Resource；正文不会自动进入任何 Turn。
     *
     * @param endpointId Endpoint 标识
     * @param uri 远端 Resource URI
     * @return 强类型外部内容
     */
    public McpResourceReadResult readResource(String endpointId, String uri) {
        return connection
                .query(
                        "mcp/resource/read",
                        new McpRpcContracts.ResourceReadQuery(endpointId, uri),
                        McpRpcContracts.ResourceReadResult.class)
                .resource();
    }

    /**
     * 读取 HTTPS MCP 的一页外部 Prompt 模板。
     *
     * @param endpointId Endpoint 标识
     * @param cursor 可选远端 cursor
     * @return 强类型外部 Prompt 页
     */
    public McpPromptPage prompts(String endpointId, Optional<String> cursor) {
        return connection
                .query(
                        "mcp/prompt/list",
                        new McpRpcContracts.ExternalPageQuery(endpointId, cursor),
                        McpRpcContracts.PromptPageResult.class)
                .page();
    }

    /**
     * 显式展开 HTTPS MCP Prompt；返回消息始终按外部数据处理。
     *
     * @param endpointId Endpoint 标识
     * @param name Prompt 名称
     * @param arguments 用户显式提供的参数
     * @return 强类型外部消息
     */
    public McpPromptResult getPrompt(String endpointId, String name, Map<String, String> arguments) {
        return connection
                .query(
                        "mcp/prompt/get",
                        new McpRpcContracts.PromptGetQuery(endpointId, name, arguments),
                        McpRpcContracts.PromptResult.class)
                .prompt();
    }

    /** @param authorizationId 流程标识 @return OAuth 状态 */
    public McpOAuthAuthorization oauth(String authorizationId) {
        return connection
                .query(
                        "mcp/oauth/read",
                        McpRpcContracts.OAuthQuery.authorization(authorizationId),
                        McpRpcContracts.OAuthResult.class)
                .authorization()
                .orElseThrow(() -> new IllegalArgumentException("OAuth authorization does not exist"));
    }

    /** @param endpointId Endpoint 标识 @return 最近一次授权流程 */
    public Optional<McpOAuthAuthorization> latestOAuth(String endpointId) {
        return connection
                .query(
                        "mcp/oauth/read",
                        McpRpcContracts.OAuthQuery.endpoint(endpointId),
                        McpRpcContracts.OAuthResult.class)
                .authorization();
    }

    /**
     * 启动 OAuth 2.1 + PKCE。
     *
     * @param endpointId Endpoint 标识
     * @param options 幂等命令；expected revision 为零
     * @return 浏览器授权状态
     */
    public McpOAuthAuthorization startOAuth(String endpointId, CommandOptions options) {
        return connection.command(
                "mcp/oauth/start",
                new McpRpcContracts.OAuthStartPayload(endpointId),
                options,
                McpOAuthAuthorization.class);
    }

    /** @param authorizationId 流程标识 @param options 幂等命令 @return 取消状态 */
    public McpOAuthAuthorization cancelOAuth(String authorizationId, CommandOptions options) {
        return connection.command(
                "mcp/oauth/cancel",
                McpRpcContracts.OAuthQuery.authorization(authorizationId),
                options,
                McpOAuthAuthorization.class);
    }

    private McpEndpoint state(String method, String endpointId, CommandOptions options) {
        return connection.command(method, new McpRpcContracts.EndpointQuery(endpointId), options, McpEndpoint.class);
    }
}
