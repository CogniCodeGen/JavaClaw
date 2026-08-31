package com.javaclaw.protocol;

/** JSON-RPC 请求、响应和通知的封闭信封，不包含领域执行逻辑。 */
public sealed interface JsonRpcFrame permits JsonRpcRequest, JsonRpcNotification, JsonRpcResponse {
    /** 返回固定的 JSON-RPC 协议标记 2.0。 */
    String jsonrpc();
}
