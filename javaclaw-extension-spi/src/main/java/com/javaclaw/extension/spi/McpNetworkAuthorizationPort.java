package com.javaclaw.extension.spi;

import com.javaclaw.api.McpEndpoint;

/** MCP Transport 发起连接前的 Network Broker 授权边界。 */
@FunctionalInterface
public interface McpNetworkAuthorizationPort {
    /**
     * 重新检查精确 Origin、DNS 与实时私网授权。
     *
     * @param endpoint 当前端点版本
     * @throws Exception 未授权、已撤销或 DNS 变化
     */
    void authorize(McpEndpoint endpoint) throws Exception;
}
