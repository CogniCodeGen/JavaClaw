package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.CredentialClearReceipt;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.McpCatalogKind;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpHealth;
import com.javaclaw.api.McpOAuthAuthorization;
import com.javaclaw.api.McpPromptPage;
import com.javaclaw.api.McpPromptResult;
import com.javaclaw.api.McpResourcePage;
import com.javaclaw.api.McpResourceReadResult;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;

/** MCP 设置页所需的最小异步 SDK 能力边界。 */
public interface McpSettingsGateway {
    /** @return 当前 Workspace 目录 */
    CompletionStage<List<Workspace>> workspaces();

    /** @param workspaceId Workspace @return 该 Workspace 的有效及已撤销私网授权 */
    CompletionStage<List<PrivateNetworkGrant>> privateNetworkGrants(WorkspaceId workspaceId);

    /** @param workspaceId Workspace @return 该 Workspace 的 MCP Endpoint 最新版本 */
    CompletionStage<List<McpEndpoint>> mcpEndpoints(WorkspaceId workspaceId);

    /** @param endpointId Endpoint 标识 @return Endpoint 最新版本 */
    CompletionStage<McpEndpoint> mcpEndpoint(String endpointId);

    /** @param endpointId Endpoint 标识 @return revision 升序的不可变历史 */
    CompletionStage<List<McpEndpoint>> mcpEndpointHistory(String endpointId);

    /**
     * 创建用户 HTTPS MCP Endpoint。
     *
     * @param endpointId 稳定标识
     * @param spec 完整非敏感配置
     * @param options 幂等选项，expected revision 为零
     * @return 默认停用的 Endpoint
     */
    CompletionStage<McpEndpoint> createMcpEndpoint(String endpointId, McpEndpointSpec spec, CommandOptions options);

    /**
     * 更新用户 HTTPS MCP Endpoint。
     *
     * @param endpointId 稳定标识
     * @param spec 完整非敏感配置
     * @param options 当前 expected revision
     * @return 新版本 Endpoint
     */
    CompletionStage<McpEndpoint> updateMcpEndpoint(String endpointId, McpEndpointSpec spec, CommandOptions options);

    /** @param endpoint 当前版本 @param enabled 是否启用 @param options 当前 revision @return 新版本 */
    CompletionStage<McpEndpoint> setMcpEndpointEnabled(McpEndpoint endpoint, boolean enabled, CommandOptions options);

    /** @param endpointId Endpoint 标识 @return 脱敏健康检查结果 */
    CompletionStage<McpHealth> probeMcpEndpoint(String endpointId);

    /** @param endpointId Endpoint 标识 @return 最近一次脱敏健康状态 */
    CompletionStage<McpHealth> mcpHealth(String endpointId);

    /** @param endpoint 当前版本 @param options 当前 revision @return 携带新 Catalog revision 的 Endpoint */
    CompletionStage<McpEndpoint> refreshMcpCatalog(McpEndpoint endpoint, CommandOptions options);

    /**
     * 读取一页权威 Catalog。
     *
     * @param endpointId Endpoint 标识
     * @param kind 可选目录类型
     * @param cursor 可选 opaque cursor
     * @param limit 本页上限
     * @return Catalog 页
     */
    CompletionStage<McpCatalogPage> mcpCatalog(
            String endpointId, Optional<McpCatalogKind> kind, Optional<String> cursor, int limit);

    /** @param endpointId HTTPS Endpoint @param cursor 远端 cursor @return 外部 Resource 页 */
    CompletionStage<McpResourcePage> mcpResources(String endpointId, Optional<String> cursor);

    /** @param endpointId HTTPS Endpoint @param uri Resource URI @return 外部 Resource 内容 */
    CompletionStage<McpResourceReadResult> readMcpResource(String endpointId, String uri);

    /** @param endpointId HTTPS Endpoint @param cursor 远端 cursor @return 外部 Prompt 页 */
    CompletionStage<McpPromptPage> mcpPrompts(String endpointId, Optional<String> cursor);

    /**
     * @param endpointId HTTPS Endpoint
     * @param name Prompt 名称
     * @param arguments 用户显式提供的参数
     * @return 外部 Prompt 消息
     */
    CompletionStage<McpPromptResult> getMcpPrompt(String endpointId, String name, Map<String, String> arguments);

    /** @param endpoint 当前 Endpoint @param options 幂等选项 @return OAuth 脱敏状态 */
    CompletionStage<McpOAuthAuthorization> startMcpOAuth(McpEndpoint endpoint, CommandOptions options);

    /** @param endpointId Endpoint 标识 @return 最近一次脱敏 OAuth 状态 */
    CompletionStage<Optional<McpOAuthAuthorization>> latestMcpOAuth(String endpointId);

    /** @param authorization 当前待处理流程 @param options 幂等选项 @return 取消后的脱敏状态 */
    CompletionStage<McpOAuthAuthorization> cancelMcpOAuth(McpOAuthAuthorization authorization, CommandOptions options);

    /** @param reference Vault 引用 @return 脱敏元数据 */
    CompletionStage<Optional<CredentialMetadata>> credential(CredentialRef reference);

    /** @param namespace 命名空间 @param secret 临时字符 @param options 幂等选项 @return 脱敏元数据 */
    CompletionStage<CredentialMetadata> createCredential(String namespace, char[] secret, CommandOptions options);

    /** @param reference Vault 引用 @param secret 临时字符 @param options 当前 revision @return 新版本 */
    CompletionStage<CredentialMetadata> rotateCredential(
            CredentialRef reference, char[] secret, CommandOptions options);

    /** @param reference Vault 引用 @param options 当前 revision @return 清除回执 */
    CompletionStage<CredentialClearReceipt> clearCredential(CredentialRef reference, CommandOptions options);
}
