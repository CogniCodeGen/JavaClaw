package com.javaclaw.plugins.deliverance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.service.api.CancellationToken;
import com.javaclaw.service.api.DesktopServiceClient;
import com.javaclaw.service.api.ExternalInvocation;
import com.javaclaw.service.api.ExternalResponse;
import com.javaclaw.service.api.PluginConfig;
import com.javaclaw.service.api.PluginLogger;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalApiKeyPoliciesTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void enforcesScopeModelConcurrencyRpmAndTpmPerCredential() throws Exception {
        CapturingDesktop desktop = new CapturingDesktop();
        ExternalApiKeyPolicies policies = policies(desktop, 3, 10, 1);
        ExternalInvocation invocation = invocation("prefix-12345");

        assertEquals("insufficient_permissions", assertThrows(
                ExternalApiKeyPolicies.PolicyFailure.class,
                () -> policies.authorize(invocation, "EMBEDDINGS_INVOKE", "local-chat")).code);
        assertEquals("insufficient_permissions", assertThrows(
                ExternalApiKeyPolicies.PolicyFailure.class,
                () -> policies.authorize(invocation, "CHAT_INVOKE", "other-model")).code);

        ExternalApiKeyPolicies.Lease first = policies.authorize(
                invocation, "CHAT_INVOKE", "local-chat");
        assertEquals("concurrency_limit_exceeded", assertThrows(
                ExternalApiKeyPolicies.PolicyFailure.class,
                () -> policies.authorize(invocation, "CHAT_INVOKE", "local-chat")).code);
        first.success(new Protocol.Usage(3, 4));
        first.close();

        try (ExternalApiKeyPolicies.Lease second = policies.authorize(
                invocation, "CHAT_INVOKE", "local-chat")) {
            second.success(new Protocol.Usage(2, 2));
        }
        assertEquals("rate_limit_exceeded", assertThrows(
                ExternalApiKeyPolicies.PolicyFailure.class,
                () -> policies.authorize(invocation, "CHAT_INVOKE", "local-chat")).code);
        assertEquals(2, desktop.reports.size());
        assertEquals(7, desktop.reports.getFirst().path("promptTokens").asLong()
                + desktop.reports.getFirst().path("completionTokens").asLong());
    }

    @Test
    void startsFromPersistedMinuteUsage() throws Exception {
        CapturingDesktop desktop = new CapturingDesktop();
        long minute = Instant.now().getEpochSecond() / 60;
        String encoded = json.writeValueAsString(List.of(Map.ofEntries(
                Map.entry("keyId", "00000000-0000-0000-0000-000000000001"),
                Map.entry("prefix", "prefix-12345"),
                Map.entry("scopes", List.of("MODELS_READ")),
                Map.entry("modelAliases", List.of()),
                Map.entry("requestsPerMinute", 1),
                Map.entry("tokensPerMinute", 100),
                Map.entry("maxConcurrent", 1),
                Map.entry("initialMinute", minute),
                Map.entry("initialRequests", 1),
                Map.entry("initialTokens", 0))));
        ExternalApiKeyPolicies policies = new ExternalApiKeyPolicies(config(encoded), desktop,
                json, logger());

        assertEquals("rate_limit_exceeded", assertThrows(
                ExternalApiKeyPolicies.PolicyFailure.class,
                () -> policies.authorize(invocation("prefix-12345"),
                        "MODELS_READ", null)).code);
    }

    private ExternalApiKeyPolicies policies(
            CapturingDesktop desktop, int rpm, long tpm, int concurrency) throws Exception {
        long minute = Instant.now().getEpochSecond() / 60;
        String encoded = json.writeValueAsString(List.of(Map.ofEntries(
                Map.entry("keyId", "00000000-0000-0000-0000-000000000001"),
                Map.entry("prefix", "prefix-12345"),
                Map.entry("scopes", List.of("MODELS_READ", "CHAT_INVOKE")),
                Map.entry("modelAliases", List.of("local-chat")),
                Map.entry("requestsPerMinute", rpm),
                Map.entry("tokensPerMinute", tpm),
                Map.entry("maxConcurrent", concurrency),
                Map.entry("initialMinute", minute),
                Map.entry("initialRequests", 0),
                Map.entry("initialTokens", 0))));
        return new ExternalApiKeyPolicies(config(encoded), desktop, json, logger());
    }

    private static PluginConfig config(String encoded) {
        return new PluginConfig() {
            @Override public String get(String key) {
                return "external.apiKeyPolicies".equals(key) ? encoded : "";
            }
            @Override public Map<String, String> asMap() {
                return Map.of("external.apiKeyPolicies", encoded);
            }
        };
    }

    private static ExternalInvocation invocation(String credential) {
        return new ExternalInvocation() {
            @Override public String requestId() { return "request"; }
            @Override public String credentialId() { return credential; }
            @Override public String method() { return "POST"; }
            @Override public String path() { return "/v1/chat/completions"; }
            @Override public Map<String, List<String>> headers() { return Map.of(); }
            @Override public byte[] body() { return new byte[0]; }
            @Override public InetSocketAddress remoteAddress() {
                return new InetSocketAddress("127.0.0.1", 1);
            }
            @Override public CancellationToken cancellation() { return () -> false; }
            @Override public ExternalResponse response() { throw new UnsupportedOperationException(); }
        };
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override public void debug(String message) { }
            @Override public void info(String message) { }
            @Override public void warn(String message) { }
            @Override public void error(String message, Throwable failure) { }
        };
    }

    private final class CapturingDesktop implements DesktopServiceClient {
        private final List<com.fasterxml.jackson.databind.JsonNode> reports = new CopyOnWriteArrayList<>();

        @Override
        public Call invoke(String serviceId, String operation, String contentType, byte[] payload,
                           Duration timeout, java.util.function.Consumer<Event> events) {
            try { reports.add(json.readTree(payload)); }
            catch (Exception failure) { throw new AssertionError(failure); }
            CompletableFuture<Response> result = CompletableFuture.completedFuture(
                    new Response("application/json", new byte[0]));
            return new Call() {
                @Override public String requestId() { return "usage"; }
                @Override public CompletableFuture<Response> completion() { return result; }
                @Override public boolean cancel() { return false; }
            };
        }
    }
}
