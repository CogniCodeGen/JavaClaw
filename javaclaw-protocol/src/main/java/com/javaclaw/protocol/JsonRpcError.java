package com.javaclaw.protocol;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 错误对象；message/data 必须在构造前完成敏感信息过滤。
 *
 * @param code JSON-RPC 标准或应用错误代码
 * @param message 非空错误说明
 * @param data 可选结构化错误详情；可为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcError(int code, String message, JsonNode data) {
    /** 要求错误说明非空；不对敏感数据进行自动脱敏。 */
    public JsonRpcError {
        message = Objects.requireNonNull(message, "message");
    }

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int NOT_INITIALIZED = -32001;
    public static final int CONFLICT = -32009;
    public static final int NOT_FOUND = -32044;
    public static final int UNSUPPORTED_VERSION = -32060;
}
