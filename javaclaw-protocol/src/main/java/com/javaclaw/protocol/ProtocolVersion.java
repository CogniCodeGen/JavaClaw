package com.javaclaw.protocol;

/** JavaClaw 自有协议版本支持表；不随外部 Codex 私有协议变化。 */
public final class ProtocolVersion {
    public static final int CURRENT = 1;
    public static final int MINIMUM = 1;

    private ProtocolVersion() {}

    /** 判断给定版本是否由当前服务端支持；不自动降级。 */
    public static boolean supported(int value) {
        return value >= MINIMUM && value <= CURRENT;
    }
}
