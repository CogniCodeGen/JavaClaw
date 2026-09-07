package com.javaclaw.protocol;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** initialize 能力协商器。 */
public final class ProtocolNegotiator {
    private final Set<String> stableCapabilities;
    private final Set<String> experimentalCapabilities;

    /**
     * 创建服务端能力集。
     *
     * @param stableCapabilities stable 能力
     * @param experimentalCapabilities 可选实验能力
     */
    public ProtocolNegotiator(Set<String> stableCapabilities, Set<String> experimentalCapabilities) {
        this.stableCapabilities = Set.copyOf(stableCapabilities);
        this.experimentalCapabilities = Set.copyOf(experimentalCapabilities);
    }

    /**
     * 协商协议和能力。
     *
     * @param params initialize 输入
     * @return 会话能力
     */
    public NegotiatedCapabilities negotiate(InitializeParams params) {
        Objects.requireNonNull(params, "params");
        if (params.appProtocolVersion() != ProtocolVersion.CURRENT) {
            throw new ProtocolException(
                    ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
                    "UNSUPPORTED_PROTOCOL_VERSION: appProtocolVersion " + params.appProtocolVersion()
                            + " is not supported");
        }
        Set<String> stable =
                intersection(stableCapabilities, params.capabilities().stableCapabilities());
        Set<String> experimental =
                intersection(experimentalCapabilities, params.capabilities().requestedExperimentalCapabilities());
        return new NegotiatedCapabilities(stable, experimental);
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        HashSet<String> result = new HashSet<>(left);
        result.retainAll(right);
        return Set.copyOf(result);
    }
}
