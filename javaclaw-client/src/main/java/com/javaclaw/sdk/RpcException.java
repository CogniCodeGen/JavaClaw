package com.javaclaw.sdk;

import com.fasterxml.jackson.databind.JsonNode;

import com.javaclaw.sdk.model.JsonDocument;

/** 服务端 JSON-RPC 错误的 SDK 表示；不向 SDK 使用者泄漏 Jackson 或 Wire 类型。 */
public final class RpcException extends RuntimeException {
    private static final int CONFLICT_CODE = -32009;
    private final int code;
    private final JsonDocument data;

    RpcException(int code, String message, JsonNode data) {
        super(message);
        this.code = code;
        this.data = new JsonDocument(data == null ? "null" : data.toString());
    }

    /** 返回稳定的服务端错误码，供调用方区分版本冲突、参数错误或执行失败。 */
    public int code() {
        return code;
    }

    /** 返回错误是否表示乐观锁或同一资源的版本冲突，调用方无需依赖 Wire 错误码常量。 */
    public boolean isConflict() {
        return code == CONFLICT_CODE;
    }

    /** 返回错误详情 JSON 文档；无详情时为 JSON null 文档，内容应已经由服务端脱敏。 */
    public JsonDocument data() {
        return data;
    }
}
