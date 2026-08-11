package com.javaclaw.infrastructure.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.settings.EmbeddingRuntimeProbePort;
import com.javaclaw.application.settings.ModelSettingsApplicationService.EmbeddingSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ModelSettings;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.http.HttpGateway;
import com.javaclaw.platform.http.HttpResult;
import com.javaclaw.platform.json.JsonCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpModelSettingsProbeAdapterTest {

    private ManagedTaskExecutor tasks;
    private FakeRuntimeEmbedding runtime;
    private HttpModelSettingsProbeAdapter adapter;
    private AtomicReference<String> embeddingBody;
    private AtomicReference<String> embeddingAuthorization;
    private AtomicReference<String> modelAuthorization;
    private AtomicReference<String> modelMethod;
    private AtomicInteger embeddingRequests;

    @BeforeEach
    void setUp() {
        embeddingBody = new AtomicReference<>();
        embeddingAuthorization = new AtomicReference<>();
        modelAuthorization = new AtomicReference<>();
        modelMethod = new AtomicReference<>();
        embeddingRequests = new AtomicInteger();
        tasks = new ManagedTaskExecutor();
        runtime = new FakeRuntimeEmbedding();
        adapter = new HttpModelSettingsProbeAdapter(
                new HttpGateway(tasks, this::send),
                new JsonCodec(new ObjectMapper()), runtime);
    }

    @AfterEach
    void tearDown() {
        if (tasks != null) tasks.close();
    }

    @Test
    void modelProbeUsesTheConfiguredEndpointAndBearerCredential() throws Exception {
        var result = adapter.probeModel(model("model-key"));

        assertTrue(result.succeeded());
        assertTrue(result.message().contains("chat-model"));
        assertEquals("GET", modelMethod.get());
        assertEquals("Bearer model-key", modelAuthorization.get());
    }

    @Test
    void unchangedEmbeddingUsesRuntimeButChangedFormProbesTheExactForm() throws Exception {
        EmbeddingSettings persisted = embedding("persisted-key", 3);

        var runtimeResult = adapter.probeEmbedding(persisted, persisted);
        assertTrue(runtimeResult.succeeded());
        assertEquals(1, runtime.probes);
        assertEquals(0, embeddingRequests.get());

        EmbeddingSettings changed = embedding("new-key", 3);
        var formResult = adapter.probeEmbedding(changed, persisted);

        assertTrue(formResult.succeeded());
        assertTrue(formResult.message().contains("尚未保存"));
        assertEquals(1, embeddingRequests.get());
        assertEquals("Bearer new-key", embeddingAuthorization.get());
        assertTrue(embeddingBody.get().contains("\"model\":\"embedding-model\""));
    }

    @Test
    void dimensionMismatchIsReportedBeforeTheConfigurationCanBeSaved() throws Exception {
        EmbeddingSettings persisted = embedding("persisted-key", 1024);
        runtime.dimensions = 768;

        var result = adapter.probeEmbedding(persisted, persisted);

        assertFalse(result.succeeded());
        assertTrue(result.message().contains("实际维度 768"));
        assertTrue(result.message().contains("配置 1024"));
    }

    private ModelSettings model(String key) {
        return new ModelSettings("OpenAI", baseUrl(), "chat-model", key, false, 4096,
                "HTTP_2", 10, 120, 30, 30, 15, 15, 5, 0.92, 4.0, 2);
    }

    private EmbeddingSettings embedding(String key, int dimensions) {
        return new EmbeddingSettings(true, "OpenAI", baseUrl(), key,
                "embedding-model", dimensions, 5, 0.35);
    }

    private String baseUrl() {
        return "https://example.test/v1";
    }

    private HttpResult send(HttpRequest request) throws java.io.IOException {
        if (request.uri().getPath().endsWith("/models")) {
            modelMethod.set(request.method());
            modelAuthorization.set(request.headers().firstValue("Authorization").orElse(null));
            return response(request.uri(), 200, "{}");
        }
        embeddingRequests.incrementAndGet();
        embeddingAuthorization.set(
                request.headers().firstValue("Authorization").orElse(null));
        var subscriber = HttpRequest.BodyPublishers.ofString("");
        var publisher = request.bodyPublisher().orElse(subscriber);
        embeddingBody.set(readBody(publisher));
        return response(request.uri(), 200,
                "{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}");
    }

    private static String readBody(HttpRequest.BodyPublisher publisher)
            throws java.io.IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.util.concurrent.CompletableFuture<Void> completed = new java.util.concurrent.CompletableFuture<>();
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(java.nio.ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }
            @Override public void onError(Throwable failure) {
                completed.completeExceptionally(failure);
            }
            @Override public void onComplete() { completed.complete(null); }
        });
        try {
            completed.join();
        } catch (java.util.concurrent.CompletionException failure) {
            throw new java.io.IOException("读取请求正文失败", failure.getCause());
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static HttpResult response(URI uri, int status, String body) {
        return new HttpResult(uri, status,
                HttpHeaders.of(Map.of(), (name, value) -> true),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static final class FakeRuntimeEmbedding implements EmbeddingRuntimeProbePort {
        private int dimensions = 3;
        private int probes;

        @Override public boolean isReady() { return true; }
        @Override public Result probe() {
            probes++;
            return new Result(true, dimensions, "");
        }
    }
}
