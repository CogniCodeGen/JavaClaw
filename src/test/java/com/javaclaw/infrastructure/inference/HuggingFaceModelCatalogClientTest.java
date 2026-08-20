package com.javaclaw.infrastructure.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.HuggingFaceModelCatalogPort;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HuggingFaceModelCatalogClientTest {
    private static final URI BASE = URI.create("https://huggingface.test/");
    private static final String SHA = "a".repeat(40);

    @Test
    void validatesManifestRuntimeTypeAndUsesActualDownloadSize() throws Exception {
        var client = client("Q4");

        var detail = client.detail("acme/qwen-small", Set.of("qwen3"), () -> false);

        assertEquals("acme/qwen-small", detail.summary().repository());
        assertEquals(SHA, detail.summary().commit());
        assertEquals("Q4", detail.summary().quantizationType());
        assertEquals(2_000, detail.summary().sourceSizeBytes());
        assertEquals(1_100, detail.summary().quantizedSizeBytes());
        assertEquals(4, detail.fileCount());
        assertEquals(8192 * 4, detail.declaredContextLength());
        assertEquals("apache-2.0", detail.license());
        assertTrue(detail.downloadable());
    }

    @Test
    void rejectsUnsupportedQuantizationAndHonorsCancellation() {
        IOException invalid = assertThrows(IOException.class, () ->
                client("I4").detail("acme/qwen-small", Set.of("qwen3"), () -> false));
        assertTrue(invalid.getMessage().contains("Q4/I8"));

        AtomicBoolean invoked = new AtomicBoolean();
        var cancelled = new HuggingFaceModelCatalogClient((request, handler) -> {
            invoked.set(true);
            return response(request, 200, "[]", Map.of());
        }, new ObjectMapper(), BASE);
        assertThrows(InterruptedException.class, () -> cancelled.search(
                new HuggingFaceModelCatalogPort.SearchRequest("qwen"), Set.of("qwen3"), () -> true));
        assertFalse(invoked.get());
    }

    @Test
    void rejectsAManifestOutputThatCannotContainItsWeights() {
        IOException invalid = assertThrows(IOException.class, () ->
                client("Q4", 999).detail("acme/qwen-small", Set.of("qwen3"), () -> false));
        assertTrue(invalid.getMessage().contains("小于 Safetensors"));
    }

    @Test
    void displaysValidatedGatedMetadataButKeepsAnonymousDownloadDisabled() throws Exception {
        String info = modelInfo().replace("\"gated\":false", "\"gated\":true")
                .replace("\"cardData\":{\"license\":\"apache-2.0\"}", """
                        "cardData":{"license":"apache-2.0","deliverance-quantization":
                        {"schemaVersion":1,"targetType":"I8","sourceSizeBytes":2000,
                         "outputSizeBytes":1050}}
                        """);
        var client = new HuggingFaceModelCatalogClient((request, handler) ->
                request.uri().getPath().startsWith("/api/models/")
                        ? response(request, 200, info, Map.of())
                        : response(request, 403, "{}", Map.of()), new ObjectMapper(), BASE);

        var detail = client.detail("acme/qwen-small", Set.of("qwen3"), () -> false);

        assertTrue(detail.summary().gated());
        assertEquals("I8", detail.summary().quantizationType());
        assertFalse(detail.downloadable());
    }

    @Test
    void encodesSearchRetriesRateLimitsAndUsesOpaqueSameOriginCursor() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<URI> requested = new java.util.ArrayList<>();
        var client = new HuggingFaceModelCatalogClient((request, handler) -> {
            requested.add(request.uri());
            if (calls.getAndIncrement() == 0) {
                return response(request, 429, "{}", Map.of("Retry-After", List.of("0")));
            }
            String link = "<https://huggingface.test/api/models?cursor=next%20page>; rel=\"next\"";
            return response(request, 200, "[]", Map.of("Link", List.of(link)));
        }, new ObjectMapper(), BASE);

        var first = client.search(new HuggingFaceModelCatalogPort.SearchRequest(
                "Qwen 3/0.6", "", 20), Set.of("qwen3"), () -> false);
        assertEquals(2, calls.get());
        assertTrue(requested.getFirst().getRawQuery().contains(
                "search=Qwen%203%2F0.6%20JQ4"));
        assertFalse(first.nextCursor().isBlank());

        client.search(new HuggingFaceModelCatalogPort.SearchRequest(
                "ignored", first.nextCursor(), 20), Set.of("qwen3"), () -> false);
        assertEquals("cursor=next%20page", requested.getLast().getRawQuery());
    }

    @Test
    void seedsTheCatalogWithJq4WithoutDuplicatingTheQualifier() throws Exception {
        List<URI> requested = new java.util.ArrayList<>();
        var client = new HuggingFaceModelCatalogClient((request, handler) -> {
            requested.add(request.uri());
            return response(request, 200, "[]", Map.of());
        }, new ObjectMapper(), BASE);

        client.search(new HuggingFaceModelCatalogPort.SearchRequest(
                "", "", 20), Set.of("qwen3"), () -> false);
        client.search(new HuggingFaceModelCatalogPort.SearchRequest(
                "qwen", "", 20), Set.of("qwen3"), () -> false);
        client.search(new HuggingFaceModelCatalogPort.SearchRequest(
                "qwen JQ4", "", 20), Set.of("qwen3"), () -> false);

        assertEquals("search=JQ4&sort=lastModified&direction=-1&limit=100&full=true&config=true",
                requested.get(0).getRawQuery());
        assertTrue(requested.get(1).getRawQuery().startsWith("search=qwen%20JQ4&"));
        assertTrue(requested.get(2).getRawQuery().startsWith("search=qwen%20JQ4&"));
    }

    @Test
    void publishesIncrementalVerificationAndReusesTheLastValidatedResultWhileRefreshing()
            throws Exception {
        var client = new HuggingFaceModelCatalogClient((request, handler) -> {
            String path = request.uri().getPath();
            if (path.equals("/api/models")) return response(request, 200, """
                    [{"id":"acme/qwen-small","private":false,
                      "config":{"model_type":"qwen3"}},
                     {"id":"acme/unsupported","private":false,
                      "config":{"model_type":"bert"}}]
                    """, Map.of());
            if (path.startsWith("/api/models/")) {
                return response(request, 200, modelInfo(), Map.of());
            }
            if (path.endsWith("/deliverance-quantization.json")) {
                return response(request, 200, """
                        {"schemaVersion":1,"targetType":"Q4",
                         "sourceSizeBytes":2000,"outputSizeBytes":1050}
                        """, Map.of());
            }
            throw new AssertionError("unexpected request " + request.uri());
        }, new ObjectMapper(), BASE);
        List<HuggingFaceModelCatalogPort.SearchProgress> first = new java.util.ArrayList<>();

        var page = client.searchIncrementally(
                new HuggingFaceModelCatalogPort.SearchRequest("", "", 20), Set.of("qwen3"),
                first::add, () -> false);

        assertEquals(1, page.models().size());
        assertEquals(HuggingFaceModelCatalogPort.SearchState.LOADING, first.getFirst().state());
        assertTrue(first.stream().anyMatch(value ->
                value.state() == HuggingFaceModelCatalogPort.SearchState.PARTIAL));
        assertEquals(HuggingFaceModelCatalogPort.SearchState.READY, first.getLast().state());
        assertEquals(2, first.getLast().scannedCount());
        assertEquals(1, first.getLast().rejectionReasons().get("模型类型不兼容"));

        List<HuggingFaceModelCatalogPort.SearchProgress> second = new java.util.ArrayList<>();
        client.searchIncrementally(new HuggingFaceModelCatalogPort.SearchRequest("", "", 20),
                Set.of("qwen3"), second::add, () -> false);
        assertEquals(HuggingFaceModelCatalogPort.SearchState.STALE, second.getFirst().state());
        assertEquals(1, second.getFirst().models().size());
    }

    private static HuggingFaceModelCatalogClient client(String target) {
        return client(target, 1050);
    }

    private static HuggingFaceModelCatalogClient client(String target, long outputSize) {
        return new HuggingFaceModelCatalogClient((request, handler) -> {
            if (request.uri().getPath().startsWith("/api/models/")) {
                return response(request, 200, modelInfo(), Map.of());
            }
            if (request.uri().getPath().endsWith("/deliverance-quantization.json")) {
                return response(request, 200, """
                        {"schemaVersion":1,"targetType":"%s",
                         "sourceSizeBytes":2000,"outputSizeBytes":%d}
                        """.formatted(target, outputSize), Map.of());
            }
            throw new AssertionError("unexpected request " + request.uri());
        }, new ObjectMapper(), BASE);
    }

    private static String modelInfo() {
        return """
                {
                  "id":"acme/qwen-small","sha":"%s","private":false,"gated":false,
                  "lastModified":"2026-08-18T12:00:00Z",
                  "config":{"model_type":"qwen3","architectures":["Qwen3ForCausalLM"],
                            "max_position_embeddings":32768},
                  "cardData":{"license":"apache-2.0"},
                  "siblings":[
                    {"rfilename":"config.json","size":20},
                    {"rfilename":"tokenizer.json","size":30},
                    {"rfilename":"model.safetensors","size":1000},
                    {"rfilename":"deliverance-quantization.json","size":50}
                  ]
                }
                """.formatted(SHA);
    }

    private static HttpResponse<byte[]> response(
            HttpRequest request, int status, String body, Map<String, List<String>> headers) {
        return new FakeResponse(status, request,
                HttpHeaders.of(headers, (name, value) -> true),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private record FakeResponse(int statusCode, HttpRequest request, HttpHeaders headers,
                                byte[] body) implements HttpResponse<byte[]> {
        @Override public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
