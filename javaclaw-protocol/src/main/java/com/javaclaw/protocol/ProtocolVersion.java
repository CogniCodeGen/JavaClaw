package com.javaclaw.protocol;

/** App Protocol 版本常量。 */
public final class ProtocolVersion {
    /** JavaClaw 6.x 唯一支持的协议版本。 */
    public static final int CURRENT = 3;

    /** JSON-RPC 固定版本。 */
    public static final String JSON_RPC = "2.0";

    private ProtocolVersion() {}
}
