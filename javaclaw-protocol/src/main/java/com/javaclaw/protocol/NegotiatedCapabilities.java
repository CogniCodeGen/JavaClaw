package com.javaclaw.protocol;

import java.util.Set;

/**
 * 双方确认的会话能力快照。
 *
 * @param stableCapabilities stable 能力交集
 * @param experimentalCapabilities 已同意的实验能力
 */
public record NegotiatedCapabilities(Set<String> stableCapabilities, Set<String> experimentalCapabilities) {
    /** 复制能力集合。 */
    public NegotiatedCapabilities {
        stableCapabilities = Set.copyOf(stableCapabilities);
        experimentalCapabilities = Set.copyOf(experimentalCapabilities);
    }

    /**
     * 检查会话是否允许能力。
     *
     * @param capability 能力名
     * @return stable 或 experimental 中存在时为 true
     */
    public boolean allows(String capability) {
        return stableCapabilities.contains(capability) || experimentalCapabilities.contains(capability);
    }

    /**
     * 要求能力已经协商。
     *
     * @param capability 能力名
     * @throws ProtocolException 未协商时抛出
     */
    public void require(String capability) {
        if (!allows(capability)) {
            throw new ProtocolException(
                    ProtocolErrorCode.CAPABILITY_NOT_NEGOTIATED, "capability was not negotiated: " + capability);
        }
    }
}
