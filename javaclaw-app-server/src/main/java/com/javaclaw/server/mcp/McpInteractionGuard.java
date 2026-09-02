package com.javaclaw.server.mcp;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpElicitationRequest;
import com.javaclaw.api.McpSamplingRequest;
import com.javaclaw.extension.spi.McpClientInteractionPort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.PersistenceException;

/** 对 MCP 反向 elicitation/sampling 实施来源和数据形状硬限制。 */
final class McpInteractionGuard implements McpClientInteractionPort {
    private static final Duration MAXIMUM_ELICITATION_LIFETIME = Duration.ofMinutes(15);

    private final String endpointId;
    private final McpClientInteractionPort delegate;
    private final CanonicalJson json;
    private final Clock clock;

    McpInteractionGuard(String endpointId, McpClientInteractionPort delegate, CanonicalJson json, Clock clock) {
        this.endpointId = Objects.requireNonNull(endpointId, "endpointId");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<CanonicalPayload> elicit(McpElicitationRequest request, CancellationToken cancellation)
            throws Exception {
        Objects.requireNonNull(request, "request");
        requireSource(request.endpointId());
        Duration lifetime = Duration.between(clock.instant(), request.expiresAt());
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(MAXIMUM_ELICITATION_LIFETIME) > 0) {
            throw PersistenceException.invalidRequest("MCP elicitation 有效期无效");
        }
        json.requireFlatPrimitiveObjectSchema(request.responseSchema(), 20);
        return delegate.elicit(request, Objects.requireNonNull(cancellation, "cancellation"));
    }

    @Override
    public Optional<CanonicalPayload> sample(McpSamplingRequest request, CancellationToken cancellation)
            throws Exception {
        Objects.requireNonNull(request, "request");
        requireSource(request.endpointId());
        return delegate.sample(request, Objects.requireNonNull(cancellation, "cancellation"));
    }

    private void requireSource(String actualEndpointId) {
        if (!endpointId.equals(actualEndpointId)) {
            throw PersistenceException.invalidRequest("MCP 反向请求来源与当前 Endpoint 不一致");
        }
    }
}
