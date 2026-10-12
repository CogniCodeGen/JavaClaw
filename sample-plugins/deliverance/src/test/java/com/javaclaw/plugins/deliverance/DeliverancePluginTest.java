package com.javaclaw.plugins.deliverance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.plugin.protocol.v2.*;
import com.javaclaw.plugin.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class DeliverancePluginTest {
    @TempDir Path data;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final PluginIdentity identity = PluginIdentity.newBuilder().setPluginId("builtin-deliverance")
            .setWorkspaceId("test").setInstanceId("test-instance").setArtifactHash("a".repeat(64))
            .setConfigurationRevision(1).build();

    @Test void exposesOnlyTheModelContributionAndRoutesPrivateServicesAndCatalog() throws Exception {
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor(); var plugin = new DeliverancePlugin()) {
            assertEquals(List.of("inference"), plugin.contributions().stream().map(Contribution::getId).toList());
            assertEquals(CapabilityKind.MODEL, plugin.contributions().getFirst().getKind());
            plugin.start(new PluginContext(identity, "{}", data, tasks,
                    (context, service, operation, payload, events) -> "{}", () -> false));
            assertTrue(invoke(plugin, "list", "{}").isArray());
            assertEquals(0, invoke(plugin, "models.sync", "{\"profiles\":[],\"aliases\":{}}").path("profiles").asInt());
            assertTrue(invoke(plugin, "logging.configure", "{\"enabled\":true}").path("enabled").asBoolean());
            assertEquals("HEALTHY", invoke(plugin, "probe", "{}").path("state").asText());
            JsonNode recommendation = invoke(plugin, "recommend", """
                    {"model":{"modelType":"llama","architectures":[],"declaredContextLength":4096},
                     "artifact":{"format":"SAFETENSORS","quantizationType":"Q4","sourceSizeBytes":4096,
                     "quantizedSizeBytes":1024,"sourceUpdatedAt":"1970-01-01T00:00:00Z"}}
                    """);
            assertEquals("Q4", recommendation.path("loadParameters").path("workingQuantType").asText());
            Path model = Files.createDirectory(data.resolve("model"));
            Files.writeString(model.resolve("deliverance-quantization.json"),
                    "{\"schemaVersion\":1,\"targetType\":\"Q4\",\"sourceSizeBytes\":4096}");
            JsonNode metadata = invoke(plugin, "asset.inspect", json.writeValueAsString(Map.of(
                    "modelPath", model.toString(), "quantizedSizeBytes", 1024)));
            assertEquals("Q4", metadata.path("quantizationType").asText());
            assertEquals(1024, metadata.path("quantizedSizeBytes").asLong());
        }
    }

    @Test void privateHttpGatewayAuthenticatesHashedKeysAndStopsOnDisable() throws Exception {
        String raw = "jcl-test-prefix-secret-key-000000000000000000";
        byte[] salt = new byte[16]; Arrays.fill(salt, (byte) 7);
        PBEKeySpec specification = new PBEKeySpec(raw.toCharArray(), salt, 120_000, 256);
        String digest;
        try { digest = Base64.getUrlEncoder().withoutPadding().encodeToString(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded()); }
        finally { specification.clearPassword(); }
        int port;
        try (ServerSocket available = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) { port = available.getLocalPort(); }
        var settings = json.createObjectNode().put("enabled", true);
        settings.set("configuration", json.valueToTree(Map.of("bindAddress", "127.0.0.1", "port", port,
                "tlsEnabled", false, "maxRequestBytes", 1024, "requestTimeoutSeconds", 5)));
        settings.set("apiKeys", json.valueToTree(List.of(Map.ofEntries(
                Map.entry("id", UUID.randomUUID().toString()), Map.entry("prefix", "jcl-test-prefix"),
                Map.entry("salt", Base64.getUrlEncoder().withoutPadding().encodeToString(salt)),
                Map.entry("digest", digest), Map.entry("scopes", List.of("MODELS_READ")),
                Map.entry("modelAliases", List.of()), Map.entry("requestsPerMinute", 100),
                Map.entry("tokensPerMinute", 1000), Map.entry("maxConcurrent", 2)))));
        settings.set("catalog", json.readTree("{\"profiles\":[],\"aliases\":{}}"));
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor(); var plugin = new DeliverancePlugin()) {
            plugin.start(new PluginContext(identity, "{}", data, tasks,
                    (context, service, operation, payload, events) -> "{}", () -> false));
            assertTrue(invoke(plugin, "gateway.configure", settings.toString()).path("enabled").asBoolean());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            URI models = URI.create("http://127.0.0.1:" + port + "/v1/models");
            assertEquals(401, client.send(HttpRequest.newBuilder(models).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(200, client.send(HttpRequest.newBuilder(models).header("Authorization", "Bearer " + raw)
                    .GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(401, client.send(HttpRequest.newBuilder(models).header("Authorization", "Bearer " + raw + "wrong")
                    .GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertFalse(invoke(plugin, "gateway.configure", "{\"enabled\":false}").path("enabled").asBoolean());
            assertThrows(java.io.IOException.class, () -> client.send(HttpRequest.newBuilder(models).timeout(Duration.ofSeconds(2))
                    .GET().build(), HttpResponse.BodyHandlers.discarding()));
        }
    }

    @Test void drainingRefusesNewHttpRequestsAndFinishesAnExistingResponseBeforeClosing() throws Exception {
        String raw = "jcl-drain-key-000000000000000000000000000000000";
        byte[] salt = new byte[16]; Arrays.fill(salt, (byte) 3);
        PBEKeySpec specification = new PBEKeySpec(raw.toCharArray(), salt, 120_000, 256);
        String digest;
        try { digest = Base64.getUrlEncoder().withoutPadding().encodeToString(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded()); }
        finally { specification.clearPassword(); }
        String policies = json.writeValueAsString(List.of(Map.of("prefix", "jcl-drain-key",
                "salt", Base64.getUrlEncoder().withoutPadding().encodeToString(salt), "digest", digest)));
        int port;
        try (ServerSocket available = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) { port = available.getLocalPort(); }
        var settings = json.createObjectNode().put("bindAddress", "127.0.0.1").put("port", port)
                .put("maxRequestBytes", 1024).put("requestTimeoutSeconds", 10);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor();
             var gateway = new DeliveranceHttpGateway(new PluginContext(identity, "{}", data, tasks,
                     (context, service, operation, payload, events) -> "{}", () -> false), json, () -> settings, () -> policies)) {
            var endpoint = gateway.register(new com.javaclaw.plugins.deliverance.internal.ExternalEndpointDescriptor(
                    "test", null, "/", Set.of()), invocation -> {
                started.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放 HTTP 请求");
                invocation.response().send(200, "text/plain", Map.of(), "完成".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            });
            try {
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                        .header("Authorization", "Bearer " + raw).timeout(Duration.ofSeconds(10)).GET().build();
                var active = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                assertTrue(started.await(5, TimeUnit.SECONDS));
                gateway.beginDrain();
                assertEquals(503, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
                endpoint.close();
                assertFalse(active.isDone());
                release.countDown();
                var result = active.get(5, TimeUnit.SECONDS);
                assertEquals(200, result.statusCode());
                assertEquals("完成", result.body());
                assertThrows(IllegalStateException.class, () -> gateway.register(
                        new com.javaclaw.plugins.deliverance.internal.ExternalEndpointDescriptor("new", null, "/", Set.of()), ignored -> { }));
            } finally { release.countDown(); endpoint.close(); }
        }
    }

    private JsonNode invoke(DeliverancePlugin plugin, String operation, String payload) throws Exception {
        List<String> events = new ArrayList<>();
        plugin.execute(new PluginCall(CapabilityKind.MODEL, CallRequest.newBuilder()
                .setContext(CallContext.newBuilder().setIdentity(identity).setRequestId(UUID.randomUUID().toString())
                        .setDeadlineEpochMillis(System.currentTimeMillis() + 10_000))
                .setContributionId("inference").setOperation(operation).setPayloadJson(payload).build(), () -> false), events::add);
        return json.readTree(events.getLast());
    }
}
