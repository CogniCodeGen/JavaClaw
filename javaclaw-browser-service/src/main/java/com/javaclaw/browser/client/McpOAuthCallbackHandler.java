package com.javaclaw.browser.client;

import java.net.URI;

/** Worker 私有 callback 到 App Server OAuth 状态机的直接边界。 */
@FunctionalInterface
public interface McpOAuthCallbackHandler {
    /**
     * 处理完整 callback；实现不得记录、缓存或向 RPC 返回 URI。
     *
     * @param callbackUri 含 code/state 的固定 loopback URI
     * @throws Exception token 交换或状态提交失败
     */
    void handle(URI callbackUri) throws Exception;
}
