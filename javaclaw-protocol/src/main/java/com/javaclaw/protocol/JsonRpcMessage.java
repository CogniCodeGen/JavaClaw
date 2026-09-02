package com.javaclaw.protocol;

/** JSON-RPC 2.0 wire message。 */
public sealed interface JsonRpcMessage permits JsonRpcRequest, JsonRpcNotification, JsonRpcResponse {
    /**
     * 返回固定 JSON-RPC 版本。
     *
     * @return {@code 2.0}
     */
    default String jsonrpc() {
        return ProtocolVersion.JSON_RPC;
    }
}
