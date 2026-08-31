package com.javaclaw.server.extension.mcp;

import java.net.URI;
import java.time.Instant;
import java.util.function.Consumer;

/** OAuth interaction boundary used by the RPC adapter. */
public interface McpAuthorizationService extends AutoCloseable {
    McpAuthorizationService UNAVAILABLE = new McpAuthorizationService() {
        @Override
        public Authorization start(McpConfiguration configuration) {
            throw new IllegalStateException("MCP OAuth capability is unavailable");
        }

        @Override
        public boolean cancel(String authorizationId) {
            return false;
        }

        @Override
        public void close() {}
    };

    /**
     * 开始一次 OAuth 授权，返回 URL 和过期时间；回调仅绑定 127.0.0.1，不承载 App Server RPC。
     *
     * @throws Exception 配置不支持 OAuth、元数据校验失败或无法建立回调
     */
    Authorization start(McpConfiguration configuration) throws Exception;

    /** 取消指定一次性授权并关闭回调资源；会话不存在时返回 false。 */
    boolean cancel(String authorizationId);

    /** 注册授权状态监听并返回取消订阅句柄；回调不得接收 token 或阻塞授权持久化。 */
    default AutoCloseable onStatus(Consumer<AuthorizationStatus> listener) {
        return () -> {};
    }

    @Override
    void close();

    /**
     * 一次 OAuth 授权的公开会话描述，不包含凭据。
     *
     * @param authorizationId 一次性 OAuth 授权会话标识
     * @param authorizationUrl 用户授权 URL，不包含访问或刷新 token
     * @param expiresAt 授权会话过期时间
     */
    record Authorization(String authorizationId, URI authorizationUrl, Instant expiresAt) {}

    /**
     * 可通知客户端的授权状态，不承载 Provider 原始错误体。
     *
     * @param authorizationId 一次性 OAuth 授权会话标识
     * @param mcpId MCP 配置标识
     * @param state 授权流程状态；不包含 OAuth state 随机值或 token
     * @param occurredAt 状态变更时间
     */
    record AuthorizationStatus(String authorizationId, String mcpId, String state, Instant occurredAt) {}
}
