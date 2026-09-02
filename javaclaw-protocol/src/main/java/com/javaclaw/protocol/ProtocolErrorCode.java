package com.javaclaw.protocol;

/** JSON-RPC 标准错误和 JavaClaw v2 平台错误。 */
public final class ProtocolErrorCode {
    /** JSON 语法无效。 */
    public static final int PARSE_ERROR = -32700;
    /** JSON-RPC 信封无效。 */
    public static final int INVALID_REQUEST = -32600;
    /** 方法不存在。 */
    public static final int METHOD_NOT_FOUND = -32601;
    /** 参数无效。 */
    public static final int INVALID_PARAMS = -32602;
    /** 服务端内部错误。 */
    public static final int INTERNAL_ERROR = -32603;
    /** App Protocol 版本不支持。 */
    public static final int UNSUPPORTED_PROTOCOL = -32020;
    /** stable 或 experimental capability 未协商。 */
    public static final int CAPABILITY_NOT_NEGOTIATED = -32021;
    /** expected revision 冲突。 */
    public static final int REVISION_CONFLICT = -32022;
    /** 幂等键缺失或冲突。 */
    public static final int IDEMPOTENCY_CONFLICT = -32023;
    /** 权限、审批或实时撤权拒绝。 */
    public static final int PERMISSION_DENIED = -32024;

    private ProtocolErrorCode() {}
}
