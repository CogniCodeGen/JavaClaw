package com.javaclaw.server.mcp;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.BrokerRequest;
import com.javaclaw.api.BrokerResponse;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpFrozenTool;
import com.javaclaw.api.McpInvocationRequest;
import com.javaclaw.api.McpProtocol;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.security.vault.SecretVaultService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokeredMcpRemotePortTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void 固定协议完成会话三类分页与Tool调用() throws Exception {
        Fixture fixture = fixture();
        McpEndpoint endpoint = endpoint(McpAuthType.NONE, Optional.empty(), Optional.empty());

        var session = fixture.remote().initialize(endpoint, McpProtocol.VERSION, new CancellationSource());
        assertEquals(McpProtocol.VERSION, session.protocolVersion());
        assertEquals(Set.of("tools"), session.capabilities());

        McpCatalogPage tools = fixture.remote().catalog(endpoint, Optional.empty(), new CancellationSource());
        McpCatalogPage prompts = fixture.remote().catalog(endpoint, tools.nextCursor(), new CancellationSource());
        McpCatalogPage resources = fixture.remote().catalog(endpoint, prompts.nextCursor(), new CancellationSource());
        assertEquals(1, tools.entries().size());
        assertTrue(prompts.entries().isEmpty());
        assertTrue(resources.nextCursor().isEmpty());

        McpFrozenTool tool = new McpFrozenTool(
                endpoint.id(),
                endpoint.revision(),
                endpoint.catalogRevision(),
                "search",
                tools.entries().getFirst().schemaHash().orElseThrow());
        var result = fixture.remote()
                .invoke(
                        endpoint,
                        new McpInvocationRequest(tool, fixture.json().parse("{\"q\":\"v5\"}"), "effect-1"),
                        rejectingInteractions(),
                        new CancellationSource());
        assertTrue(result.successful());
        assertEquals(6, fixture.exchange().requests.size());
        assertTrue(fixture.exchange().requests.stream()
                .allMatch(request -> request.uri().equals(URI.create("https://mcp.example/rpc"))));
        assertTrue(fixture.exchange().requests.stream()
                .allMatch(
                        request -> request.headers().get("mcp-protocol-version").equals(List.of(McpProtocol.VERSION))));
        CanonicalPayload invocation = fixture.json()
                .parse(new String(fixture.exchange().requests.getLast().body(), StandardCharsets.UTF_8));
        CanonicalPayload params =
                fixture.json().objectField(invocation, "params").orElseThrow();
        assertTrue(fixture.json().objectField(params, "_meta").isPresent());
    }

    @Test
    void Https显式读取Resource与Prompt且保留外部数据边界() throws Exception {
        Fixture fixture = fixture();
        McpEndpoint endpoint = endpoint(McpAuthType.NONE, Optional.empty(), Optional.empty());

        var resources = fixture.remote().resources(endpoint, Optional.empty(), new CancellationSource());
        var content = fixture.remote().readResource(endpoint, "docs://guide", new CancellationSource());
        var prompts = fixture.remote().prompts(endpoint, Optional.empty(), new CancellationSource());
        var prompt = fixture.remote().getPrompt(endpoint, "review", Map.of("topic", "v5"), new CancellationSource());

        assertTrue(resources.resources().isEmpty());
        assertEquals("hello", content.contents().getFirst().text().orElseThrow());
        assertTrue(prompts.prompts().isEmpty());
        assertEquals(
                "review",
                fixture.json()
                        .textField(prompt.messages().getFirst().content(), "text")
                        .orElseThrow());
        assertTrue(fixture.exchange().requests.stream()
                .map(request -> fixture.json().parse(new String(request.body(), StandardCharsets.UTF_8)))
                .filter(value -> fixture.json().textField(value, "method").isPresent())
                .filter(value -> !"initialize"
                        .equals(fixture.json().textField(value, "method").orElseThrow()))
                .filter(value -> !"notifications/initialized"
                        .equals(fixture.json().textField(value, "method").orElseThrow()))
                .allMatch(value -> fixture.json()
                        .objectField(value, "params")
                        .flatMap(params -> fixture.json().objectField(params, "_meta"))
                        .flatMap(meta -> fixture.json().textField(meta, "progressToken"))
                        .isPresent()));
    }

    @Test
    void 多帧Sse受控消费进度并通过Turn交互端口处理反向请求() throws Exception {
        Fixture fixture = fixture();
        fixture.exchange().interactiveSse = true;
        McpEndpoint endpoint = endpoint(McpAuthType.NONE, Optional.empty(), Optional.empty());
        McpFrozenTool tool = new McpFrozenTool(endpoint.id(), endpoint.revision(), 1, "search", "a".repeat(64));
        int[] calls = new int[2];
        var interactions = new com.javaclaw.extension.spi.McpClientInteractionPort() {
            @Override
            public Optional<CanonicalPayload> elicit(
                    com.javaclaw.api.McpElicitationRequest request, com.javaclaw.api.CancellationToken cancellation) {
                calls[0]++;
                return Optional.of(fixture.json().parse("{\"answer\":\"yes\"}"));
            }

            @Override
            public Optional<CanonicalPayload> sample(
                    com.javaclaw.api.McpSamplingRequest request, com.javaclaw.api.CancellationToken cancellation) {
                calls[1]++;
                return Optional.of(fixture.json().parse("""
                        {"role":"assistant","content":{"type":"text","text":"ok"},
                        "model":"governed","stopReason":"endTurn"}
                        """));
            }
        };

        var result = fixture.remote()
                .invoke(
                        endpoint,
                        new McpInvocationRequest(tool, fixture.json().parse("{}"), "effect-progress"),
                        interactions,
                        new CancellationSource());

        assertTrue(result.successful());
        assertEquals(List.of(1, 1), List.of(calls[0], calls[1]));
        assertEquals(2, result.progress().size());
        assertEquals(0, result.progress().getFirst().progress().compareTo(java.math.BigDecimal.ONE));
        assertEquals(2, fixture.exchange().reverseResponses.size());
    }

    @Test
    void Bearer只在Vault回调期间注入请求头() throws Exception {
        Fixture fixture = fixture();
        byte[] secret = "vault-token".getBytes(StandardCharsets.UTF_8);
        var metadata = fixture.vault().create(identity("credential/create", "bearer", fixture.json()), "mcp", secret);
        McpEndpoint endpoint = endpoint(McpAuthType.BEARER, Optional.of(metadata.reference()), Optional.empty());

        fixture.remote().initialize(endpoint, McpProtocol.VERSION, new CancellationSource());

        assertEquals(
                List.of("Bearer vault-token"),
                fixture.exchange().requests.getFirst().headers().get("authorization"));
        assertFalse(new String(fixture.exchange().requests.getFirst().body(), StandardCharsets.UTF_8)
                .contains("vault-token"));
    }

    @Test
    void Sse多事件响应拒绝歧义而不选择最后一条() {
        Fixture fixture = fixture();
        fixture.exchange().ambiguousSse = true;
        McpEndpoint endpoint = endpoint(McpAuthType.NONE, Optional.empty(), Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.remote().initialize(endpoint, McpProtocol.VERSION, new CancellationSource()));
        assertEquals(1, fixture.exchange().requests.size());
    }

    @Test
    void 固定协议与HttpsTransport在发出请求前校验() {
        Fixture fixture = fixture();
        McpEndpoint endpoint = endpoint(McpAuthType.NONE, Optional.empty(), Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.remote().initialize(endpoint, "2025-11-25", new CancellationSource()));
        assertThrows(IllegalArgumentException.class, () -> fixture.remote().authorize(stdioEndpoint()));
        assertEquals(0, fixture.exchange().requests.size());
    }

    @Test
    void 远端协议不匹配不建立Session且后续Catalog明确拒绝() throws Exception {
        Fixture fixture = fixture();
        fixture.exchange().protocol = "2025-11-25";
        McpEndpoint endpoint = endpoint(McpAuthType.NONE, Optional.empty(), Optional.empty());

        var session = fixture.remote().initialize(endpoint, McpProtocol.VERSION, new CancellationSource());
        assertEquals("2025-11-25", session.protocolVersion());
        assertTrue(session.capabilities().isEmpty());
        assertEquals(1, fixture.exchange().requests.size());

        assertThrows(
                IllegalStateException.class,
                () -> fixture.remote().catalog(endpoint, Optional.empty(), new CancellationSource()));
        assertEquals(2, fixture.exchange().requests.size());
    }

    @Test
    void Initialize缺字段与非法Session标识都被拒绝() {
        Fixture omitted = fixture();
        omitted.exchange().omitProtocol = true;
        assertThrows(
                IllegalArgumentException.class,
                () -> omitted.remote()
                        .initialize(
                                endpoint(McpAuthType.NONE, Optional.empty(), Optional.empty()),
                                McpProtocol.VERSION,
                                new CancellationSource()));

        for (String invalid : List.of(" ", "bad\nsession", "x".repeat(1_025))) {
            Fixture fixture = fixture();
            fixture.exchange().session = Optional.of(invalid);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.remote()
                            .initialize(
                                    endpoint(McpAuthType.NONE, Optional.empty(), Optional.empty()),
                                    McpProtocol.VERSION,
                                    new CancellationSource()));
        }
    }

    @Test
    void 无Capabilities与无SessionHeader仍可按固定协议调用() throws Exception {
        Fixture fixture = fixture();
        fixture.exchange().omitCapabilities = true;
        fixture.exchange().session = Optional.empty();
        McpEndpoint endpoint = endpoint(McpAuthType.NONE, Optional.empty(), Optional.empty());

        assertTrue(fixture.remote()
                .initialize(endpoint, McpProtocol.VERSION, new CancellationSource())
                .capabilities()
                .isEmpty());
        fixture.remote().catalog(endpoint, Optional.empty(), new CancellationSource());

        assertTrue(fixture.exchange().requests.stream()
                .noneMatch(request -> request.headers().containsKey("mcp-session-id")));
    }

    @Test
    void Http失败截断与缺失Credential都会FailClosed() {
        for (boolean truncated : List.of(false, true)) {
            Fixture fixture = fixture();
            fixture.exchange().statusCode = truncated ? 200 : 503;
            fixture.exchange().truncated = truncated;
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.remote()
                            .initialize(
                                    endpoint(McpAuthType.NONE, Optional.empty(), Optional.empty()),
                                    McpProtocol.VERSION,
                                    new CancellationSource()));
        }

        Fixture missing = fixture();
        CredentialRef reference = new CredentialRef("mcp", "missing-secret");
        assertThrows(
                RuntimeException.class,
                () -> missing.remote()
                        .initialize(
                                endpoint(McpAuthType.BEARER, Optional.of(reference), Optional.empty()),
                                McpProtocol.VERSION,
                                new CancellationSource()));
    }

    @Test
    void ApiKey注入指定Header且非法Utf8与超长凭据被拒绝() throws Exception {
        Fixture fixture = fixture();
        byte[] apiSecret = "api-secret".getBytes(StandardCharsets.UTF_8);
        var metadata =
                fixture.vault().create(identity("credential/create", "api-key", fixture.json()), "mcp", apiSecret);
        McpEndpoint apiKey = endpoint(McpAuthType.API_KEY, Optional.of(metadata.reference()), Optional.of("x-api-key"));

        fixture.remote().initialize(apiKey, McpProtocol.VERSION, new CancellationSource());

        assertEquals(
                List.of("api-secret"),
                fixture.exchange().requests.getFirst().headers().get("x-api-key"));

        assertCredentialRejected(fixture, new byte[] {(byte) 0xc3, (byte) 0x28}, "invalid-utf8");
        assertCredentialRejected(fixture, new byte[16_385], "too-large");
    }

    private Fixture fixture() {
        CanonicalJson json = new CanonicalJson();
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        SecretVaultService vault = new SecretVaultService(
                database, new MemoryProtector(), json, Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
        FakeExchange exchange = new FakeExchange(json);
        return new Fixture(json, vault, exchange, new BrokeredMcpRemotePort(exchange, vault, json));
    }

    private void assertCredentialRejected(Fixture fixture, byte[] secret, String key) {
        var metadata = fixture.vault().create(identity("credential/create", key, fixture.json()), "mcp", secret);
        McpEndpoint endpoint = endpoint(McpAuthType.BEARER, Optional.of(metadata.reference()), Optional.empty());
        assertThrows(
                Exception.class,
                () -> fixture.remote().initialize(endpoint, McpProtocol.VERSION, new CancellationSource()));
    }

    private static McpEndpoint endpoint(
            McpAuthType authType, Optional<com.javaclaw.api.CredentialRef> credential, Optional<String> apiKeyHeader) {
        Instant now = NOW;
        return new McpEndpoint(
                "docs",
                1,
                McpEndpointState.ENABLED,
                1,
                new McpEndpointSpec(
                        WorkspaceId.random(),
                        "Docs",
                        McpTransport.STREAMABLE_HTTPS,
                        Optional.of(URI.create("https://mcp.example/rpc")),
                        Optional.empty(),
                        authType,
                        credential,
                        apiKeyHeader,
                        Optional.empty(),
                        Duration.ofSeconds(10)),
                now,
                now);
    }

    private static McpEndpoint stdioEndpoint() {
        return new McpEndpoint(
                "stdio",
                1,
                McpEndpointState.ENABLED,
                1,
                new McpEndpointSpec(
                        WorkspaceId.random(),
                        "Stdio",
                        McpTransport.SIGNED_BUNDLE_STDIO,
                        Optional.empty(),
                        Optional.of("signed.bundle"),
                        McpAuthType.NONE,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Duration.ofSeconds(10)),
                NOW,
                NOW);
    }

    private static com.javaclaw.extension.spi.McpClientInteractionPort rejectingInteractions() {
        return new com.javaclaw.extension.spi.McpClientInteractionPort() {
            @Override
            public Optional<CanonicalPayload> elicit(
                    com.javaclaw.api.McpElicitationRequest request, com.javaclaw.api.CancellationToken cancellation) {
                return Optional.empty();
            }

            @Override
            public Optional<CanonicalPayload> sample(
                    com.javaclaw.api.McpSamplingRequest request, com.javaclaw.api.CancellationToken cancellation) {
                return Optional.empty();
            }
        };
    }

    private static CommandIdentity identity(String method, String key, CanonicalJson json) {
        return new CommandIdentity(method, key, 0, json.parse("{}").sha256());
    }

    private record Fixture(
            CanonicalJson json, SecretVaultService vault, FakeExchange exchange, BrokeredMcpRemotePort remote) {}

    private static final class FakeExchange implements BrokeredMcpRemotePort.Exchange {
        private final CanonicalJson json;
        private final List<BrokerRequest> requests = new ArrayList<>();
        private boolean ambiguousSse;
        private boolean interactiveSse;
        private final List<CanonicalPayload> reverseResponses = new ArrayList<>();
        private String protocol = McpProtocol.VERSION;
        private boolean omitProtocol;
        private boolean omitCapabilities;
        private int statusCode = 200;
        private boolean truncated;
        private Optional<String> session = Optional.of("session-1");

        private FakeExchange(CanonicalJson json) {
            this.json = json;
        }

        @Override
        public BrokerResponse exchange(
                McpEndpoint endpoint, BrokerRequest request, com.javaclaw.api.CancellationToken cancellation) {
            requests.add(request);
            CanonicalPayload envelope = json.parse(new String(request.body(), StandardCharsets.UTF_8));
            Optional<String> incomingMethod = json.textField(envelope, "method");
            if (incomingMethod.isEmpty()) {
                reverseResponses.add(envelope);
                return new BrokerResponse(202, Map.of(), new byte[0], false);
            }
            String method = incomingMethod.orElseThrow();
            if ("notifications/initialized".equals(method)) {
                return new BrokerResponse(202, Map.of(), new byte[0], truncated);
            }
            String id = json.textField(envelope, "id").orElseThrow();
            CanonicalPayload result = result(method);
            String response = json.encode(new WireResponse("2.0", id, result)).json();
            if (interactiveSse && "tools/call".equals(method)) {
                return response(interactiveResponse(envelope, id, response), "text/event-stream", Optional.empty());
            }
            if (ambiguousSse) {
                response = "data: " + response + "\n\ndata: " + response + "\n\n";
                return response(response, "text/event-stream", Optional.empty());
            }
            Optional<String> responseSession = "initialize".equals(method) ? session : Optional.empty();
            return response(response, "application/json", responseSession);
        }

        private String interactiveResponse(CanonicalPayload request, String id, String finalResponse) {
            CanonicalPayload params = json.objectField(request, "params").orElseThrow();
            CanonicalPayload metadata = json.objectField(params, "_meta").orElseThrow();
            String token = json.textField(metadata, "progressToken").orElseThrow();
            String progressOne = json.encode(Map.of(
                            "jsonrpc",
                            "2.0",
                            "method",
                            "notifications/progress",
                            "params",
                            Map.of("progressToken", token, "progress", 1, "total", 2)))
                    .json();
            String elicitation = """
                    {"jsonrpc":"2.0","id":"remote-elicit","method":"elicitation/create","params":{
                    "mode":"form","message":"Choose","requestedSchema":{"type":"object",
                    "properties":{"answer":{"type":"string"}},"required":["answer"],
                    "additionalProperties":false}}}
                    """;
            String sampling = """
                    {"jsonrpc":"2.0","id":"remote-sample","method":"sampling/createMessage","params":{
                    "messages":[{"role":"user","content":{"type":"text","text":"summarize"}}],
                    "maxTokens":32,"includeContext":"none"}}
                    """;
            String progressTwo = json.encode(Map.of(
                            "jsonrpc",
                            "2.0",
                            "method",
                            "notifications/progress",
                            "params",
                            Map.of("progressToken", token, "progress", 2, "total", 2)))
                    .json();
            return sse(progressOne) + sse(elicitation) + sse(sampling) + sse(progressTwo) + sse(finalResponse);
        }

        private static String sse(String value) {
            return "data: " + value.strip().replace("\n", "\ndata: ") + "\n\n";
        }

        private CanonicalPayload result(String method) {
            return switch (method) {
                case "initialize" -> initializeResult();
                case "tools/list" -> json.parse("""
                        {"tools":[{"name":"search","description":"Search docs",
                        "inputSchema":{"type":"object"},"outputSchema":{"type":"object"}}],
                        "cacheScope":"private","resultType":"complete","ttlMs":0}
                        """);
                case "prompts/list" ->
                    json.parse("{\"prompts\":[],\"cacheScope\":\"private\",\"resultType\":\"complete\",\"ttlMs\":0}");
                case "resources/list" ->
                    json.parse("{\"resources\":[],\"cacheScope\":\"private\",\"resultType\":\"complete\",\"ttlMs\":0}");
                case "tools/call" -> json.parse("{\"content\":[],\"isError\":false}");
                case "resources/read" -> json.parse("""
                        {"contents":[{"uri":"docs://guide","mimeType":"text/plain","text":"hello"}],
                        "cacheScope":"private","resultType":"complete","ttlMs":0}
                        """);
                case "prompts/get" -> json.parse("""
                        {"description":"External template","messages":[{"role":"user",
                        "content":{"type":"text","text":"review"}}],"resultType":"complete"}
                        """);
                default -> throw new AssertionError("unexpected MCP method " + method);
            };
        }

        private CanonicalPayload initializeResult() {
            Map<String, Object> result = new HashMap<>();
            if (!omitProtocol) {
                result.put("protocolVersion", protocol);
            }
            if (!omitCapabilities) {
                result.put("capabilities", Map.of("tools", Map.of()));
            }
            return json.encode(result);
        }

        private BrokerResponse response(String body, String contentType, Optional<String> session) {
            Map<String, List<String>> headers = new HashMap<>();
            headers.put("content-type", List.of(contentType));
            session.ifPresent(value -> headers.put("mcp-session-id", List.of(value)));
            return new BrokerResponse(statusCode, headers, body.getBytes(StandardCharsets.UTF_8), truncated);
        }
    }

    private record WireResponse(String jsonrpc, String id, CanonicalPayload result) {}

    private static final class MemoryProtector implements MasterKeyProtector {
        private final Map<String, byte[]> keys = new HashMap<>();

        @Override
        public Optional<byte[]> load(String keyId) {
            return Optional.ofNullable(keys.get(keyId)).map(byte[]::clone);
        }

        @Override
        public void store(String keyId, byte[] key) {
            keys.put(keyId, key.clone());
        }

        @Override
        public void delete(String keyId) {
            keys.remove(keyId);
        }
    }
}
