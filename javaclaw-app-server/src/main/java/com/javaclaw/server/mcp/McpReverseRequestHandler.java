package com.javaclaw.server.mcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpElicitationRequest;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpSamplingMessage;
import com.javaclaw.api.McpSamplingRequest;
import com.javaclaw.api.McpSamplingRole;
import com.javaclaw.extension.spi.McpClientInteractionPort;
import com.javaclaw.protocol.CanonicalJson;

/** 将 HTTPS 与 stdio MCP 反向请求统一收窄到受治理 elicitation 和 sampling。 */
final class McpReverseRequestHandler {
    private static final Duration MAXIMUM_ELICITATION_LIFETIME = Duration.ofMinutes(10);
    private static final Set<String> ELICITATION_FIELDS = Set.of("message", "mode", "requestedSchema");
    private static final Set<String> SAMPLING_FIELDS = Set.of("messages", "maxTokens", "includeContext");

    private final CanonicalJson json;
    private final Clock clock;

    McpReverseRequestHandler(CanonicalJson json, Clock clock) {
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    CanonicalPayload handle(
            McpEndpoint endpoint,
            String remoteId,
            String method,
            CanonicalPayload params,
            McpClientInteractionPort interactions,
            CancellationToken cancellation)
            throws Exception {
        return switch (method) {
            case "elicitation/create" -> elicit(endpoint, remoteId, params, interactions, cancellation);
            case "sampling/createMessage" -> sample(endpoint, remoteId, params, interactions, cancellation);
            default -> throw new UnsupportedOperationException("MCP reverse method is not allowed");
        };
    }

    private CanonicalPayload elicit(
            McpEndpoint endpoint,
            String remoteId,
            CanonicalPayload params,
            McpClientInteractionPort interactions,
            CancellationToken cancellation)
            throws Exception {
        String mode = json.textField(params, "mode").orElse("form");
        if (!"form".equals(mode) || !ELICITATION_FIELDS.containsAll(json.fieldNames(params))) {
            throw new IllegalArgumentException("MCP URL elicitation is not allowed");
        }
        String prompt = requiredText(params, "message");
        CanonicalPayload schema = json.objectField(params, "requestedSchema")
                .orElseThrow(() -> new IllegalArgumentException("requestedSchema is required"));
        json.requireFlatPrimitiveObjectSchema(schema, 20);
        Instant now = clock.instant();
        Duration lifetime = minimum(endpoint.spec().requestTimeout(), MAXIMUM_ELICITATION_LIFETIME);
        McpElicitationRequest request = new McpElicitationRequest(
                interactionId(endpoint, "elicit", remoteId), endpoint.id(), prompt, schema, now.plus(lifetime));
        Optional<CanonicalPayload> response = interactions.elicit(request, cancellation);
        response.ifPresent(value -> json.requireFlatPrimitiveObjectValue(schema, value));
        return response.map(value -> json.encode(Map.of("action", "accept", "content", value)))
                .orElseGet(() -> json.encode(Map.of("action", "decline")));
    }

    private CanonicalPayload sample(
            McpEndpoint endpoint,
            String remoteId,
            CanonicalPayload params,
            McpClientInteractionPort interactions,
            CancellationToken cancellation)
            throws Exception {
        if (!SAMPLING_FIELDS.containsAll(json.fieldNames(params))) {
            throw new IllegalArgumentException("MCP sampling requested forbidden model controls");
        }
        String includeContext = json.textField(params, "includeContext").orElse("none");
        if (!"none".equals(includeContext)) {
            throw new IllegalArgumentException("MCP sampling context injection is not allowed");
        }
        List<McpSamplingMessage> messages = json.objectArrayField(params, "messages", 32).stream()
                .map(this::samplingMessage)
                .toList();
        int maximumTokens = Math.toIntExact(json.integerField(params, "maxTokens")
                .orElseThrow(() -> new IllegalArgumentException("maxTokens is required")));
        McpSamplingRequest request = new McpSamplingRequest(
                interactionId(endpoint, "sample", remoteId), endpoint.id(), messages, maximumTokens);
        Optional<CanonicalPayload> response = interactions.sample(request, cancellation);
        return response.orElseGet(() -> json.encode(Map.of("stopReason", "declined")));
    }

    private McpSamplingMessage samplingMessage(CanonicalPayload value) {
        String role = requiredText(value, "role").toLowerCase(Locale.ROOT);
        McpSamplingRole checkedRole =
                switch (role) {
                    case "user" -> McpSamplingRole.USER;
                    case "assistant" -> McpSamplingRole.ASSISTANT;
                    default -> throw new IllegalArgumentException("MCP sampling system role is forbidden");
                };
        CanonicalPayload content = json.objectField(value, "content")
                .orElseThrow(() -> new IllegalArgumentException("sampling content must be an object"));
        return new McpSamplingMessage(checkedRole, content);
    }

    private String requiredText(CanonicalPayload payload, String field) {
        return json.textField(payload, field)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException(field + " is required"));
    }

    private String interactionId(McpEndpoint endpoint, String kind, String remoteId) {
        String digest = json.encode(Map.of("endpoint", endpoint.id(), "remoteId", remoteId))
                .sha256();
        return kind + '-' + digest.substring(0, 32);
    }

    private static Duration minimum(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }
}
