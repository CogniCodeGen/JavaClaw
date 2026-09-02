package com.javaclaw.extension.spi;

import java.net.URI;

import com.javaclaw.api.McpEndpoint;

/** OAuth 2.1 metadata/authorization/token 交换的 Broker 边界。 */
public interface McpOAuthBrokerPort {
    /**
     * 构造带 S256 challenge 和 state 的授权 URL。
     *
     * @param endpoint MCP Endpoint
     * @param codeChallenge S256 challenge
     * @param state 随机 CSRF state
     * @param redirectUri 平台固定 loopback 回调 URL
     * @return 仅供 App Server 私有 Browser Worker 使用的完整授权请求
     * @throws Exception metadata 发现或策略失败
     */
    McpOAuthAuthorizationRequest authorizationRequest(
            McpEndpoint endpoint, String codeChallenge, String state, URI redirectUri) throws Exception;

    /**
     * 校验回调并交换 token；返回字节由调用方立即密封并清零。
     *
     * @param exchange 已冻结 Endpoint、client_id、回调、PKCE 与取消信号
     * @return token 响应的受限密封字节
     * @throws Exception state、回调或 token 交换失败
     */
    byte[] exchange(McpOAuthExchange exchange) throws Exception;
}
