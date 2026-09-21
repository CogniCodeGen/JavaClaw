package com.javaclaw.model;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.HttpMethod;
import com.anthropic.core.http.HttpRequest;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderModelDiscoveryRouteTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");
    private static final String KEY = "local-route-fixture-key";
    private static final String OPENAI_CATALOG = """
            {"object":"list","data":[{"id":"local-model","object":"model","created":0,"owned_by":"test"}]}
            """;

    @Test
    void OpenAi兼容目录保留根路径及尾斜杠语义并只发送Bearer鉴权() throws Exception {
        // 覆盖当前预设使用的路径形态；仅替换主机为本地服务，不依赖 Desktop 或真实厂商可用性。
        for (String prefix : List.of("", "/v1", "/api/v1", "/compatible-mode/v1")) {
            for (String trailing : List.of("", "/")) {
                try (LocalDiscoveryServer server = LocalDiscoveryServer.json(OPENAI_CATALOG)) {
                    ProviderEndpoint endpoint = endpoint(
                            ProviderAdapter.OPENAI_COMPATIBLE,
                            root(server, prefix + trailing),
                            ProviderAuthentication.API_KEY);

                    var result = adapter().discover(endpoint, new CancellationSource());

                    assertEquals("local-model", result.candidates().getFirst().modelId());
                    assertEquals(
                            prefix + "/models", URI.create(server.target(0)).getPath());
                    assertEquals(Optional.of("Bearer " + KEY), server.header(0, "Authorization"));
                    assertTrue(server.header(0, "x-api-key").isEmpty());
                    assertTrue(server.header(0, "x-goog-api-key").isEmpty());
                    assertEquals(1, server.requests());
                }
            }
        }
    }

    @Test
    void Anthropic根地址由适配器追加版本且使用独立密钥头() throws Exception {
        String catalog = """
                {"data":[{"id":"claude-local","created_at":"2026-01-01T00:00:00Z",
                "display_name":"Local Claude","type":"model"}],"has_more":false,
                "first_id":"claude-local","last_id":"claude-local"}
                """;
        try (LocalDiscoveryServer server = LocalDiscoveryServer.json(catalog)) {
            var result = adapter()
                    .discover(
                            endpoint(ProviderAdapter.ANTHROPIC, root(server, ""), ProviderAuthentication.API_KEY),
                            new CancellationSource());

            assertEquals("claude-local", result.candidates().getFirst().modelId());
            assertEquals("/v1/models", URI.create(server.target(0)).getPath());
            assertEquals(Optional.of(KEY), server.header(0, "x-api-key"));
            assertEquals(Optional.of("2023-06-01"), server.header(0, "anthropic-version"));
            assertTrue(server.header(0, "Authorization").isEmpty());
            assertTrue(server.header(0, "x-goog-api-key").isEmpty());
            assertEquals(1, server.requests());
        }
    }

    @Test
    void 受限Anthropic传输保留显式版本而不覆盖调用方选择() throws Exception {
        try (LocalDiscoveryServer server = LocalDiscoveryServer.json("{}");
                var client = DiscoveryHttpClients.anthropic(
                        Duration.ofSeconds(5), root(server, "").toString(), new CancellationSource())) {
            HttpRequest request = HttpRequest.builder()
                    .method(HttpMethod.GET)
                    .baseUrl(root(server, "").toString())
                    .addPathSegments("v1", "models")
                    .putHeader("anthropic-version", "2023-06-01-explicit-fixture")
                    .build();

            try (var response = client.execute(request, RequestOptions.none())) {
                assertEquals(200, response.statusCode());
                assertEquals(Optional.of("2023-06-01-explicit-fixture"), server.header(0, "anthropic-version"));
                assertEquals("/v1/models", URI.create(server.target(0)).getPath());
                assertEquals(1, server.requests());
            }
        }
    }

    @Test
    void Gemini根地址与独立版本只组合一次并使用Google密钥头() throws Exception {
        String catalog = """
                {"models":[{"name":"models/gemini-local","displayName":"Local Gemini",
                "supportedGenerationMethods":["generateContent"]}]}
                """;
        try (LocalDiscoveryServer server = LocalDiscoveryServer.json(catalog)) {
            var result = adapter()
                    .discover(
                            endpoint(ProviderAdapter.GOOGLE_GENAI, root(server, ""), ProviderAuthentication.API_KEY),
                            new CancellationSource());

            assertEquals("models/gemini-local", result.candidates().getFirst().modelId());
            assertEquals("/v1beta/models", URI.create(server.target(0)).getPath());
            assertEquals(Optional.of(KEY), server.header(0, "x-goog-api-key"));
            assertTrue(server.header(0, "Authorization").isEmpty());
            assertTrue(server.header(0, "x-api-key").isEmpty());
            assertEquals(1, server.requests());
        }
    }

    @Test
    void 本地无鉴权兼容目录保留v1且不读取凭据或发送任何密钥头() throws Exception {
        try (LocalDiscoveryServer server = LocalDiscoveryServer.json(OPENAI_CATALOG)) {
            var discovery = new ProviderModelDiscoveryAdapter(
                    ignored -> {
                        throw new AssertionError("无鉴权目录不得读取凭据");
                    },
                    Clock.fixed(NOW, ZoneOffset.UTC));
            var result = discovery.discover(
                    endpoint(ProviderAdapter.OPENAI_COMPATIBLE, root(server, "/v1"), ProviderAuthentication.NONE),
                    new CancellationSource());

            assertEquals("local-model", result.candidates().getFirst().modelId());
            assertEquals("/v1/models", URI.create(server.target(0)).getPath());
            assertTrue(server.header(0, "Authorization").isEmpty());
            assertTrue(server.header(0, "x-api-key").isEmpty());
            assertTrue(server.header(0, "x-goog-api-key").isEmpty());
            assertEquals(1, server.requests());
        }
    }

    private static ProviderModelDiscoveryAdapter adapter() {
        return new ProviderModelDiscoveryAdapter(
                ignored -> Optional.of(new CredentialMaterial(KEY.toCharArray())), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static URI root(LocalDiscoveryServer server, String path) {
        String origin = server.baseUri().resolve("/").toString();
        return URI.create(origin.substring(0, origin.length() - 1) + path);
    }

    private static ProviderEndpoint endpoint(ProviderAdapter adapter, URI root, ProviderAuthentication authentication) {
        ProviderAdapterOptions options = adapter == ProviderAdapter.GOOGLE_GENAI
                ? new ProviderAdapterOptions.GoogleGenAi(Optional.of("v1beta"))
                : ProviderAdapterOptions.defaults(adapter);
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                "本地路由夹具",
                adapter,
                Optional.of(root),
                authentication,
                List.of(),
                authentication == ProviderAuthentication.API_KEY
                        ? Optional.of(new CredentialRef("provider", "local-route-key"))
                        : Optional.empty(),
                Duration.ofSeconds(5),
                0,
                options);
        return new ProviderEndpoint("local-route", 1, ProviderLifecycle.DISABLED, spec, NOW, NOW);
    }
}
