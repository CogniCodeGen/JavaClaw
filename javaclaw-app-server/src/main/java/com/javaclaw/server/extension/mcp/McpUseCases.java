package com.javaclaw.server.extension.mcp;

import java.util.function.Consumer;

import com.javaclaw.server.security.SecretStore;

/** MCP management and authorization boundary consumed by protocol handlers. */
public interface McpUseCases {
    /** 读取或刷新指定 Server 的能力发现状态，不返回认证值。 */
    McpDiscoveryStatus discover(String id);

    /** 查询当前静态凭据的存在性和 revision；不解密，未配置返回空。 */
    java.util.Optional<SecretStore.SecretMetadata> credentialMetadata(String id);

    /** 加密保存 MCP 静态凭据并使旧发现结果失效；仅返回 metadata，调用方清空原数组。 */
    SecretStore.SecretMetadata setCredential(String id, char[] credential, String idempotencyKey);

    /** 按版本清除 MCP 凭据并失效相关缓存；返回删除结果。 */
    boolean clearCredential(String id, long expectedRevision, String idempotencyKey);

    /** 为指定 MCP 配置开启一次性 OAuth 流程，返回无 token 的授权 URL、id 和过期时间。 */
    McpAuthorizationService.Authorization startAuthorization(String id) throws Exception;

    /** 取消指定授权会话并关闭临时回调监听器；不存在返回 false。 */
    boolean cancelAuthorization(String authorizationId);

    /** 监听公开授权状态，返回可取消订阅句柄；不能在回调内泄露认证材料。 */
    AutoCloseable onAuthorizationStatus(Consumer<McpAuthorizationService.AuthorizationStatus> listener);
}
