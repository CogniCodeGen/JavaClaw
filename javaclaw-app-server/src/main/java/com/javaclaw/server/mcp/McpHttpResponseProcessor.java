package com.javaclaw.server.mcp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.BrokerResponse;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpProgress;
import com.javaclaw.extension.spi.McpClientInteractionPort;
import com.javaclaw.protocol.CanonicalJson;

/** 消费一次 HTTP 交换中的进度、反向请求与唯一最终响应。 */
final class McpHttpResponseProcessor {
    private static final int MAXIMUM_PROGRESS = 32;
    private static final int MAXIMUM_REVERSE_REQUESTS = 16;
    private static final Set<String> RESPONSE_FIELDS = Set.of("jsonrpc", "id", "result", "error");
    private static final Set<String> NOTIFICATION_FIELDS = Set.of("jsonrpc", "method", "params");
    private static final Set<String> REQUEST_FIELDS = Set.of("jsonrpc", "id", "method", "params");
    private static final Set<String> PROGRESS_FIELDS = Set.of("progressToken", "progress", "total", "message", "_meta");

    private final CanonicalJson json;
    private final McpWireResponseDecoder decoder;
    private final McpReverseRequestHandler reverse;

    McpHttpResponseProcessor(CanonicalJson json, McpWireResponseDecoder decoder, McpReverseRequestHandler reverse) {
        this.json = Objects.requireNonNull(json, "json");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.reverse = Objects.requireNonNull(reverse, "reverse");
    }

    ProcessedResponse process(
            BrokerResponse response,
            String expectedId,
            String progressToken,
            McpEndpoint endpoint,
            McpClientInteractionPort interactions,
            CancellationToken cancellation,
            ReverseSender sender)
            throws Exception {
        State state = new State(expectedId, progressToken);
        for (CanonicalPayload frame : decoder.frames(response)) {
            consume(frame, endpoint, interactions, cancellation, sender, state);
        }
        return state.finish();
    }

    private void consume(
            CanonicalPayload frame,
            McpEndpoint endpoint,
            McpClientInteractionPort interactions,
            CancellationToken cancellation,
            ReverseSender sender,
            State state)
            throws Exception {
        if (state.complete()) {
            throw new IllegalArgumentException("MCP response contains frames after the final result");
        }
        requireVersion(frame);
        Optional<String> method = json.textField(frame, "method");
        if (method.isPresent()) {
            Optional<String> id = json.textField(frame, "id");
            if (id.isPresent()) {
                if (!REQUEST_FIELDS.containsAll(json.fieldNames(frame))) {
                    throw new IllegalArgumentException("MCP reverse request contains unsupported fields");
                }
                state.reverseRequest();
                CanonicalPayload params = json.objectField(frame, "params").orElseGet(() -> json.parse("{}"));
                sendReverse(
                        endpoint, id.orElseThrow(), method.orElseThrow(), params, interactions, cancellation, sender);
                return;
            }
            progress(frame, method.orElseThrow(), state);
            return;
        }
        response(frame, state);
    }

    private void sendReverse(
            McpEndpoint endpoint,
            String id,
            String method,
            CanonicalPayload params,
            McpClientInteractionPort interactions,
            CancellationToken cancellation,
            ReverseSender sender)
            throws Exception {
        try {
            CanonicalPayload result = reverse.handle(endpoint, id, method, params, interactions, cancellation);
            sender.send(json.encode(Map.of("jsonrpc", "2.0", "id", id, "result", result)));
        } catch (UnsupportedOperationException failure) {
            sender.send(error(id, -32601, "reverse method is not allowed"));
        } catch (IllegalArgumentException failure) {
            sender.send(error(id, -32602, "reverse request is invalid"));
        }
    }

    private CanonicalPayload error(String id, int code, String message) {
        return json.encode(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", code, "message", message)));
    }

    private void progress(CanonicalPayload frame, String method, State state) {
        if (!NOTIFICATION_FIELDS.containsAll(json.fieldNames(frame)) || !"notifications/progress".equals(method)) {
            throw new IllegalArgumentException("MCP response contains an unsupported notification");
        }
        CanonicalPayload params = json.objectField(frame, "params")
                .orElseThrow(() -> new IllegalArgumentException("MCP progress params are required"));
        if (!PROGRESS_FIELDS.containsAll(json.fieldNames(params))) {
            throw new IllegalArgumentException("MCP progress params contain unsupported fields");
        }
        String token = json.textField(params, "progressToken")
                .orElseThrow(() -> new IllegalArgumentException("MCP progress token must be a string"));
        BigDecimal value = json.decimalField(params, "progress")
                .orElseThrow(() -> new IllegalArgumentException("MCP progress value is required"));
        Optional<BigDecimal> total = json.decimalField(params, "total");
        Optional<String> message = json.textField(params, "message");
        state.progress(new McpProgress(token, value, total, message));
    }

    private void response(CanonicalPayload frame, State state) {
        if (!RESPONSE_FIELDS.containsAll(json.fieldNames(frame))) {
            throw new IllegalArgumentException("MCP response envelope contains unsupported fields");
        }
        String id = json.textField(frame, "id")
                .orElseThrow(() -> new IllegalArgumentException("MCP response id is required"));
        if (!state.expectedId().equals(id)) {
            throw new IllegalArgumentException("MCP response id does not match request");
        }
        Optional<CanonicalPayload> result = json.objectField(frame, "result");
        Optional<CanonicalPayload> error = json.objectField(frame, "error");
        if (result.isPresent() == error.isPresent()) {
            throw new IllegalArgumentException("MCP response requires exactly one result or error");
        }
        if (error.isPresent()) {
            String message = json.textField(error.orElseThrow(), "message").orElse("remote error");
            throw new IllegalStateException("MCP remote error: " + truncate(message, 240));
        }
        state.result(result.orElseThrow());
    }

    private void requireVersion(CanonicalPayload frame) {
        if (!"2.0".equals(json.textField(frame, "jsonrpc").orElse(""))) {
            throw new IllegalArgumentException("MCP JSON-RPC version is invalid");
        }
    }

    private static String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    @FunctionalInterface
    interface ReverseSender {
        void send(CanonicalPayload response) throws Exception;
    }

    record ProcessedResponse(CanonicalPayload result, List<McpProgress> progress) {
        ProcessedResponse {
            Objects.requireNonNull(result, "result");
            progress = List.copyOf(progress);
        }
    }

    private static final class State {
        private final String expectedId;
        private final String progressToken;
        private final List<McpProgress> progress = new ArrayList<>();
        private CanonicalPayload result;
        private int reverseRequests;

        private State(String expectedId, String progressToken) {
            this.expectedId = Objects.requireNonNull(expectedId, "expectedId");
            this.progressToken = Objects.requireNonNull(progressToken, "progressToken");
        }

        private String expectedId() {
            return expectedId;
        }

        private boolean complete() {
            return result != null;
        }

        private void reverseRequest() {
            reverseRequests++;
            if (reverseRequests > MAXIMUM_REVERSE_REQUESTS) {
                throw new IllegalArgumentException("MCP reverse request limit exceeded");
            }
        }

        private void progress(McpProgress value) {
            if (!progressToken.equals(value.token()) || progress.size() == MAXIMUM_PROGRESS) {
                throw new IllegalArgumentException("MCP progress is not associated with this request");
            }
            if (!progress.isEmpty()
                    && value.progress().compareTo(progress.getLast().progress()) < 0) {
                throw new IllegalArgumentException("MCP progress must be monotonic");
            }
            progress.add(value);
        }

        private void result(CanonicalPayload value) {
            if (result != null) {
                throw new IllegalArgumentException("MCP response contains multiple final results");
            }
            result = Objects.requireNonNull(value, "value");
        }

        private ProcessedResponse finish() {
            if (result == null) {
                throw new IllegalArgumentException("MCP response omitted the final result");
            }
            return new ProcessedResponse(result, progress);
        }
    }
}
