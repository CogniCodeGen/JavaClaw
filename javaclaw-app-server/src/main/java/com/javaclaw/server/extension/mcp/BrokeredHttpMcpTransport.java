package com.javaclaw.server.extension.mcp;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.sandbox.api.BrokerExchange;
import com.javaclaw.sandbox.api.BrokerRequest;
import com.javaclaw.sandbox.api.BrokerResponseHead;
import com.javaclaw.sandbox.api.NetworkBroker;
import com.javaclaw.sandbox.api.NetworkPolicy;

/** Stateless MCP Streamable HTTP transport; every call is a brokered, cancellable POST. */
public final class BrokeredHttpMcpTransport implements McpTransport {
    private final URI endpoint;
    private final NetworkBroker broker;
    private final NetworkPolicy policy;
    private final McpHttpAuthorization authorization;
    private final ObjectMapper json;
    private final McpCodec codec;
    private final ConcurrentHashMap<String, BrokerExchange> active = new ConcurrentHashMap<>();

    /** 绑定 HTTPS 端点、Broker 策略和认证头提供者；所有 HTTP/SSE 请求经过受控 Exchange，不继承系统代理。 */
    public BrokeredHttpMcpTransport(
            URI endpoint,
            NetworkBroker broker,
            NetworkPolicy policy,
            McpHttpAuthorization authorization,
            ObjectMapper json,
            McpCodec codec) {
        this.endpoint = requireEndpoint(endpoint);
        this.broker = Objects.requireNonNull(broker, "broker");
        this.policy = Objects.requireNonNull(policy, "policy");
        if (policy.mode() != NetworkPolicy.Mode.ALLOWLIST) {
            throw new IllegalArgumentException("HTTP MCP requires an ALLOWLIST network policy");
        }
        this.authorization = authorization == null ? McpHttpAuthorization.NONE : authorization;
        this.json = Objects.requireNonNull(json, "json");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public JsonNode exchange(
            ObjectNode request,
            Map<String, String> transportHeaders,
            Duration timeout,
            Consumer<JsonNode> notifications)
            throws Exception {
        Duration limit = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (limit.isZero() || limit.isNegative() || limit.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("HTTP MCP timeout must be between 1ns and 5m");
        }
        byte[] body = codec.encode(request).getBytes(StandardCharsets.UTF_8);
        if (body.length > McpProtocol.MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("MCP request exceeds 16 MiB");
        }
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json, text/event-stream");
        headers.put("MCP-Protocol-Version", McpProtocol.VERSION);
        mergeRouting(headers, request, transportHeaders);
        merge(headers, authorization.headers(), true);
        BrokerRequest brokerRequest =
                new BrokerRequest("POST", endpoint, headers, body, limit, McpProtocol.MAX_MESSAGE_BYTES, 5, true);
        String requestId = key(request.get("id"));
        BrokerExchange exchange = broker.openExchange(brokerRequest, policy);
        BrokerExchange previous = active.putIfAbsent(requestId, exchange);
        if (previous != null) {
            exchange.close();
            throw new IllegalArgumentException("duplicate active MCP request id");
        }
        try (exchange) {
            BrokerResponseHead head = exchange.head();
            if (head.statusCode() < 200 || head.statusCode() >= 300) {
                byte[] error = readAll(exchange);
                throw new IOException("HTTP MCP returned " + head.statusCode() + ": " + safeBody(error));
            }
            String contentType = firstHeader(head.headers(), "content-type");
            if (contentType == null) {
                throw new IOException("HTTP MCP omitted Content-Type");
            }
            String mediaType = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
            if (mediaType.equals("application/json")) {
                return requireResponse(json.readTree(readAll(exchange)), request);
            }
            if (mediaType.equals("text/event-stream")) {
                return readSse(exchange, request, notifications == null ? ignored -> {} : notifications);
            }
            throw new IOException("HTTP MCP returned unsupported Content-Type: " + mediaType);
        } finally {
            active.remove(requestId, exchange);
        }
    }

    @Override
    public void cancel(JsonNode requestId, String ignoredReason) {
        BrokerExchange exchange = active.get(key(requestId));
        if (exchange != null) {
            exchange.cancel();
        }
    }

    @Override
    public void close() {
        active.values().forEach(BrokerExchange::cancel);
        active.clear();
    }

    private JsonNode readSse(BrokerExchange exchange, ObjectNode request, Consumer<JsonNode> notifications)
            throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ExchangeInputStream(exchange), StandardCharsets.UTF_8), 8192)) {
            StringBuilder data = new StringBuilder();
            long characters = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                characters += line.length() + 1L;
                if (characters > McpProtocol.MAX_MESSAGE_BYTES) {
                    throw new IOException("MCP SSE stream exceeds its bound");
                }
                if (line.isEmpty()) {
                    if (data.isEmpty()) {
                        continue;
                    }
                    JsonNode message = json.readTree(data.toString());
                    data.setLength(0);
                    if (isResponse(message, request)) {
                        return message.deepCopy();
                    }
                    if (message.path("method").isTextual() && !message.has("id")) {
                        notifications.accept(message.deepCopy());
                    } else {
                        throw new IOException("MCP SSE emitted an unrelated message");
                    }
                } else if (line.startsWith(":")) {
                    continue;
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    String part = line.substring(5);
                    if (part.startsWith(" ")) {
                        part = part.substring(1);
                    }
                    data.append(part);
                }
            }
            throw new IOException("MCP SSE ended without a final response");
        }
    }

    private static void merge(Map<String, String> target, Map<String, String> source, boolean credential) {
        if (source == null) {
            return;
        }
        source.forEach((name, value) -> {
            String normalized = Objects.requireNonNull(name, "header name").strip();
            String lower = normalized.toLowerCase(Locale.ROOT);
            if (!normalized.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                    || value == null
                    || value.indexOf('\r') >= 0
                    || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("invalid MCP HTTP header");
            }
            if (SetLike.PROTECTED.contains(lower)
                    || (!credential && (lower.equals("authorization") || lower.equals("proxy-authorization")))) {
                throw new IllegalArgumentException("MCP header is controlled by the client: " + normalized);
            }
            if (credential
                    && !(lower.equals("authorization")
                            || lower.startsWith("x-api-key")
                            || lower.startsWith("api-key"))) {
                throw new IllegalArgumentException("unsupported MCP credential header");
            }
            target.put(normalized, value);
        });
    }

    private static void mergeRouting(Map<String, String> target, ObjectNode request, Map<String, String> source) {
        if (source == null) {
            return;
        }
        String expectedMethod = request.path("method").asText();
        source.forEach((name, value) -> {
            String normalized = Objects.requireNonNull(name, "header name").strip();
            String lower = normalized.toLowerCase(Locale.ROOT);
            if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("invalid MCP routing header");
            }
            if (lower.equals("mcp-method")) {
                if (!value.equals(expectedMethod)) {
                    throw new IllegalArgumentException("Mcp-Method does not match request");
                }
            } else if (!lower.equals("mcp-name") && !lower.startsWith("mcp-param-")) {
                throw new IllegalArgumentException("unsupported MCP routing header: " + name);
            }
            target.put(normalized, value);
        });
        if (!target.containsKey("Mcp-Method")) {
            throw new IllegalArgumentException("Mcp-Method header is required");
        }
    }

    private static JsonNode requireResponse(JsonNode response, ObjectNode request) throws IOException {
        if (!isResponse(response, request)) {
            throw new IOException("HTTP MCP returned an invalid response");
        }
        return response.deepCopy();
    }

    private static boolean isResponse(JsonNode response, ObjectNode request) {
        if (response == null
                || !response.isObject()
                || !"2.0".equals(response.path("jsonrpc").asText())
                || !(response.has("result") || response.has("error"))) {
            return false;
        }
        try {
            return Objects.equals(key(response.get("id")), key(request.get("id")));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static byte[] readAll(BrokerExchange exchange) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = exchange.read(buffer, 0, buffer.length)) >= 0) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String safeBody(byte[] value) {
        String text = new String(value, StandardCharsets.UTF_8)
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+", "$1[REDACTED]");
        return text.length() <= 2_000 ? text : text.substring(0, 2_000) + "…";
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static URI requireEndpoint(URI value) {
        URI result = Objects.requireNonNull(value, "endpoint").normalize();
        if (!"https".equalsIgnoreCase(result.getScheme())
                || result.isOpaque()
                || result.getHost() == null
                || result.getUserInfo() != null
                || result.getFragment() != null) {
            throw new IllegalArgumentException("HTTP MCP endpoint must be an HTTPS URL");
        }
        return result;
    }

    private static String key(JsonNode id) {
        if (id == null || id.isNull() || (!id.isTextual() && !id.isIntegralNumber())) {
            throw new IllegalArgumentException("invalid MCP request id");
        }
        return id.isTextual() ? "s:" + id.asText() : "n:" + id.asText();
    }

    private static final class ExchangeInputStream extends InputStream {
        private final BrokerExchange exchange;

        private ExchangeInputStream(BrokerExchange exchange) {
            this.exchange = exchange;
        }

        @Override
        public int read() throws IOException {
            byte[] value = new byte[1];
            return read(value, 0, 1) < 0 ? -1 : value[0] & 0xff;
        }

        @Override
        public int read(byte[] value, int offset, int length) throws IOException {
            return exchange.read(value, offset, length);
        }
    }

    private static final class SetLike {
        private static final java.util.Set<String> PROTECTED = java.util.Set.of(
                "host",
                "content-length",
                "connection",
                "transfer-encoding",
                "mcp-protocol-version",
                "mcp-method",
                "mcp-name",
                "accept",
                "content-type",
                "proxy-authorization",
                "proxy-connection");

        private SetLike() {}
    }
}
