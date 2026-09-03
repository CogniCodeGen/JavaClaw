package com.javaclaw.model;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelDiscoveryResult;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.TurnCancelledException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderModelDiscoveryAdapterTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");
    private static final CredentialRef CREDENTIAL = new CredentialRef("provider", "test-secret");

    @Test
    void 四类Provider都使用官方Sdk解析受限的本地目录() throws Exception {
        verifyCatalog(
                ProviderAdapter.OPENAI_COMPATIBLE,
                "{\"object\":\"list\",\"data\":[{\"id\":\"custom-chat\",\"object\":\"model\","
                        + "\"created\":0,\"owned_by\":\"test\"}]}",
                "custom-chat",
                Set.of());
        verifyCatalog(
                ProviderAdapter.OPENAI_RESPONSES,
                "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-response\",\"object\":\"model\","
                        + "\"created\":0,\"owned_by\":\"test\"}]}",
                "gpt-response",
                Set.of(ProviderModelPurpose.CHAT));
        verifyCatalog(
                ProviderAdapter.ANTHROPIC,
                "{\"data\":[{\"id\":\"claude-test\",\"created_at\":\"2026-01-01T00:00:00Z\","
                        + "\"display_name\":\"Claude Test\",\"type\":\"model\"}],\"has_more\":false,"
                        + "\"first_id\":\"claude-test\",\"last_id\":\"claude-test\"}",
                "claude-test",
                Set.of(ProviderModelPurpose.CHAT));
        verifyCatalog(
                ProviderAdapter.GOOGLE_GENAI,
                "{\"models\":[{\"name\":\"models/gemini-test\",\"displayName\":\"Gemini Test\","
                        + "\"supportedGenerationMethods\":[\"generateContent\",\"embedContent\"]}]}",
                "models/gemini-test",
                Set.of(ProviderModelPurpose.CHAT, ProviderModelPurpose.EMBEDDING));
    }

    @Test
    void 无鉴权OpenAi兼容端点不发送Authorization() throws Exception {
        try (LocalDiscoveryServer server =
                LocalDiscoveryServer.json("{\"object\":\"list\",\"data\":[{\"id\":\"local\",\"object\":\"model\","
                        + "\"created\":0,\"owned_by\":\"test\"}]}")) {
            ProviderEndpoint endpoint = endpoint(
                    ProviderAdapter.OPENAI_COMPATIBLE, server.baseUri(), ProviderAuthentication.NONE, Optional.empty());

            ProviderModelDiscoveryResult result = adapter().discover(endpoint, new CancellationSource());

            assertEquals("local", result.candidates().getFirst().modelId());
            assertFalse(server.authorizationPresent(0));
        }
    }

    @Test
    void 跨Origin重定向在请求目标前被拒绝() throws Exception {
        try (LocalDiscoveryServer target = LocalDiscoveryServer.json("{}");
                LocalDiscoveryServer source =
                        LocalDiscoveryServer.redirect(target.baseUri().resolve("/models"))) {
            ProviderEndpoint endpoint = endpoint(
                    ProviderAdapter.OPENAI_COMPATIBLE,
                    source.baseUri(),
                    ProviderAuthentication.API_KEY,
                    Optional.of(CREDENTIAL));

            assertThrows(RuntimeException.class, () -> adapter().discover(endpoint, new CancellationSource()));
            assertEquals(0, target.requests());
        }
    }

    @Test
    void 响应声明超过8MiB时在解析前拒绝() throws Exception {
        try (LocalDiscoveryServer server = LocalDiscoveryServer.advertisedOversize()) {
            ProviderEndpoint endpoint = endpoint(
                    ProviderAdapter.OPENAI_COMPATIBLE,
                    server.baseUri(),
                    ProviderAuthentication.API_KEY,
                    Optional.of(CREDENTIAL));

            assertThrows(RuntimeException.class, () -> adapter().discover(endpoint, new CancellationSource()));
        }
    }

    @Test
    void 压缩响应解码后超过8MiB仍会被拒绝() throws Exception {
        try (LocalDiscoveryServer server = LocalDiscoveryServer.compressedOversize()) {
            ProviderEndpoint endpoint = endpoint(
                    ProviderAdapter.OPENAI_COMPATIBLE,
                    server.baseUri(),
                    ProviderAuthentication.API_KEY,
                    Optional.of(CREDENTIAL));

            assertThrows(RuntimeException.class, () -> adapter().discover(endpoint, new CancellationSource()));
        }
    }

    @Test
    void Anthropic和Google分页沿用同一个受限发现会话() throws Exception {
        try (LocalDiscoveryServer anthropic = LocalDiscoveryServer.sequence(
                List.of(anthropicPage("claude-first", true), anthropicPage("claude-second", false)))) {
            ProviderModelDiscoveryResult result = adapter()
                    .discover(
                            endpoint(
                                    ProviderAdapter.ANTHROPIC,
                                    anthropic.baseUri(),
                                    ProviderAuthentication.API_KEY,
                                    Optional.of(CREDENTIAL)),
                            new CancellationSource());

            assertEquals(List.of("claude-first", "claude-second"), modelIds(result));
            assertEquals(2, anthropic.requests());
            assertTrue(anthropic.target(1).contains("after_id=claude-first"));
        }
        try (LocalDiscoveryServer google = LocalDiscoveryServer.sequence(List.of(
                googlePage("models/gemini-first", Optional.of("next")),
                googlePage("models/gemini-second", Optional.empty())))) {
            ProviderModelDiscoveryResult result = adapter()
                    .discover(
                            endpoint(
                                    ProviderAdapter.GOOGLE_GENAI,
                                    google.baseUri(),
                                    ProviderAuthentication.API_KEY,
                                    Optional.of(CREDENTIAL)),
                            new CancellationSource());

            assertEquals(List.of("models/gemini-first", "models/gemini-second"), modelIds(result));
            assertEquals(2, google.requests());
            assertTrue(google.target(1).contains("pageToken=next"));
        }
    }

    @Test
    void 目录最多返回1000条且只在确有后续条目时标记截断() throws Exception {
        try (LocalDiscoveryServer openAi = LocalDiscoveryServer.json(openAiPage(1_001))) {
            ProviderModelDiscoveryResult result = adapter()
                    .discover(
                            endpoint(
                                    ProviderAdapter.OPENAI_COMPATIBLE,
                                    openAi.baseUri(),
                                    ProviderAuthentication.API_KEY,
                                    Optional.of(CREDENTIAL)),
                            new CancellationSource());

            assertEquals(1_000, result.candidates().size());
            assertTrue(result.truncated());
        }
        try (LocalDiscoveryServer google = LocalDiscoveryServer.json(googlePage(1_000, Optional.empty()))) {
            ProviderModelDiscoveryResult result = adapter()
                    .discover(
                            endpoint(
                                    ProviderAdapter.GOOGLE_GENAI,
                                    google.baseUri(),
                                    ProviderAuthentication.API_KEY,
                                    Optional.of(CREDENTIAL)),
                            new CancellationSource());

            assertEquals(1_000, result.candidates().size());
            assertFalse(result.truncated());
        }
        try (LocalDiscoveryServer google = LocalDiscoveryServer.json(googlePage(1_000, Optional.of("more")))) {
            ProviderModelDiscoveryResult result = adapter()
                    .discover(
                            endpoint(
                                    ProviderAdapter.GOOGLE_GENAI,
                                    google.baseUri(),
                                    ProviderAuthentication.API_KEY,
                                    Optional.of(CREDENTIAL)),
                            new CancellationSource());

            assertEquals(1_000, result.candidates().size());
            assertTrue(result.truncated());
            assertEquals(1, google.requests());
        }
    }

    @Test
    void 取消在请求前和目录遍历期间都能停止发现() throws Exception {
        try (LocalDiscoveryServer server = LocalDiscoveryServer.json(openAiPage(2))) {
            ProviderEndpoint endpoint = endpoint(
                    ProviderAdapter.OPENAI_COMPATIBLE,
                    server.baseUri(),
                    ProviderAuthentication.API_KEY,
                    Optional.of(CREDENTIAL));
            CancellationSource cancelled = new CancellationSource();
            cancelled.cancel("测试取消");

            assertThrows(TurnCancelledException.class, () -> adapter().discover(endpoint, cancelled));
            assertEquals(0, server.requests());
        }
        try (LocalDiscoveryServer server = LocalDiscoveryServer.keepAliveJson(openAiPage(2))) {
            ProviderEndpoint endpoint = endpoint(
                    ProviderAdapter.OPENAI_COMPATIBLE,
                    server.baseUri(),
                    ProviderAuthentication.API_KEY,
                    Optional.of(CREDENTIAL));

            assertThrows(
                    TurnCancelledException.class, () -> adapter().discover(endpoint, new CancellationAfterChecks(2)));
            assertTrue(server.awaitPeerClosed());
        }
    }

    @Test
    void 取消会调用OkHttpCallCancel并立即关闭阻塞Socket() throws Exception {
        try (LocalDiscoveryServer server = LocalDiscoveryServer.blocking();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ProviderEndpoint endpoint = endpoint(
                    ProviderAdapter.OPENAI_COMPATIBLE,
                    server.baseUri(),
                    ProviderAuthentication.API_KEY,
                    Optional.of(CREDENTIAL));
            CancellationSource cancellation = new CancellationSource();
            var result = executor.submit(() -> adapter().discover(endpoint, cancellation));

            assertTrue(server.awaitRequest());
            cancellation.cancel("页面已离开");

            var failure =
                    assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof TurnCancelledException);
            assertTrue(server.awaitPeerClosed());
        }
    }

    @Test
    void 目录总时限固定上限为30秒() {
        ProviderEndpoint longTimeout = endpoint(
                ProviderAdapter.OPENAI_COMPATIBLE,
                URI.create("http://127.0.0.1:12345/v1"),
                ProviderAuthentication.NONE,
                Optional.empty(),
                Duration.ofMinutes(2));
        ProviderEndpoint shortTimeout = endpoint(
                ProviderAdapter.OPENAI_COMPATIBLE,
                URI.create("http://127.0.0.1:12345/v1"),
                ProviderAuthentication.NONE,
                Optional.empty(),
                Duration.ofSeconds(4));

        assertEquals(Duration.ofSeconds(30), ProviderModelDiscoveryAdapter.discoveryTimeout(longTimeout));
        assertEquals(Duration.ofSeconds(4), ProviderModelDiscoveryAdapter.discoveryTimeout(shortTimeout));
    }

    private void verifyCatalog(
            ProviderAdapter providerAdapter,
            String response,
            String expectedId,
            Set<ProviderModelPurpose> expectedPurposes)
            throws Exception {
        try (LocalDiscoveryServer server = LocalDiscoveryServer.keepAliveJson(response)) {
            ProviderEndpoint endpoint = endpoint(
                    providerAdapter, server.baseUri(), ProviderAuthentication.API_KEY, Optional.of(CREDENTIAL));

            ProviderModelDiscoveryResult result = adapter().discover(endpoint, new CancellationSource());

            assertEquals(expectedId, result.candidates().getFirst().modelId());
            assertEquals(expectedPurposes, result.candidates().getFirst().suggestedPurposes());
            assertFalse(result.truncated());
            assertEquals(1, server.requests());
            assertTrue(server.awaitPeerClosed());
        }
    }

    private static ProviderModelDiscoveryAdapter adapter() {
        return new ProviderModelDiscoveryAdapter(
                ignored -> Optional.of(new CredentialMaterial("test-key".toCharArray())),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ProviderEndpoint endpoint(
            ProviderAdapter adapter,
            URI baseUri,
            ProviderAuthentication authentication,
            Optional<CredentialRef> credential) {
        return endpoint(adapter, baseUri, authentication, credential, Duration.ofSeconds(5));
    }

    private static ProviderEndpoint endpoint(
            ProviderAdapter adapter,
            URI baseUri,
            ProviderAuthentication authentication,
            Optional<CredentialRef> credential,
            Duration timeout) {
        URI apiRoot = adapter == ProviderAdapter.GOOGLE_GENAI ? baseUri.resolve("/") : baseUri;
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                adapter.name(),
                adapter,
                Optional.of(apiRoot),
                authentication,
                List.of(),
                credential,
                timeout,
                0,
                ProviderAdapterOptions.defaults(adapter));
        return new ProviderEndpoint("provider", 1, ProviderLifecycle.DISABLED, spec, NOW, NOW);
    }

    private static List<String> modelIds(ProviderModelDiscoveryResult result) {
        return result.candidates().stream()
                .map(candidate -> candidate.modelId())
                .toList();
    }

    private static String anthropicPage(String id, boolean hasMore) {
        return "{\"data\":[{\"id\":\"" + id
                + "\",\"created_at\":\"2026-01-01T00:00:00Z\",\"display_name\":\""
                + id + "\",\"type\":\"model\"}],\"has_more\":" + hasMore
                + ",\"first_id\":\"" + id + "\",\"last_id\":\"" + id + "\"}";
    }

    private static String googlePage(String id, Optional<String> nextPageToken) {
        return "{\"models\":[{\"name\":\"" + id
                + "\",\"displayName\":\"" + id
                + "\",\"supportedGenerationMethods\":[\"generateContent\"]}]"
                + nextPageToken
                        .map(value -> ",\"nextPageToken\":\"" + value + "\"")
                        .orElse("")
                + "}";
    }

    private static String googlePage(int models, Optional<String> nextPageToken) {
        StringBuilder json = new StringBuilder("{\"models\":[");
        for (int index = 0; index < models; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"name\":\"models/gemini-")
                    .append(index)
                    .append("\",\"supportedGenerationMethods\":[\"generateContent\"]}");
        }
        json.append(']');
        nextPageToken.ifPresent(
                value -> json.append(",\"nextPageToken\":\"").append(value).append('\"'));
        return json.append('}').toString();
    }

    private static String openAiPage(int models) {
        StringBuilder json = new StringBuilder("{\"object\":\"list\",\"data\":[");
        for (int index = 0; index < models; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"model-")
                    .append(index)
                    .append("\",\"object\":\"model\",\"created\":0,\"owned_by\":\"test\"}");
        }
        return json.append("]}").toString();
    }

    private static final class CancellationAfterChecks implements CancellationToken {
        private final AtomicInteger checks = new AtomicInteger();
        private final int allowedChecks;

        private CancellationAfterChecks(int allowedChecks) {
            this.allowedChecks = allowedChecks;
        }

        @Override
        public boolean isCancelled() {
            if (Thread.currentThread().getName().startsWith("provider-discovery-cancellation")) {
                return false;
            }
            return checks.incrementAndGet() > allowedChecks;
        }

        @Override
        public Optional<String> reason() {
            return Optional.of("测试遍历取消");
        }
    }
}
