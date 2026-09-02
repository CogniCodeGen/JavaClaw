package com.javaclaw.protocol;

/** 可安全转换为 JSON-RPC error 的协议异常。 */
public final class ProtocolException extends RuntimeException {
    private final int code;

    /**
     * 创建协议异常。
     *
     * @param code 稳定错误码
     * @param message 不包含密钥的说明
     */
    public ProtocolException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 返回 JSON-RPC 错误码。
     *
     * @return 错误码
     */
    public int code() {
        return code;
    }
}
