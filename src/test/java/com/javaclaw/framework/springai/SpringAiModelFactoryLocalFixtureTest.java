package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.framework.spi.ModelTier;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real HTTP acceptance for the OpenAI-compatible chat and embedding adapters. */
class SpringAiModelFactoryLocalFixtureTest {

    @TempDir
    Path temporaryDirectory;

    private AnnotationConfigApplicationContext context;
    private HttpServer server;
    private SpringAiModelFactory factory;
    private final AtomicInteger chatRequests = new AtomicInteger();
    private final AtomicInteger embeddingRequests = new AtomicInteger();
    private final AtomicReference<String> chatBody = new AtomicReference<>();
    private final AtomicReference<String> embeddingBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void startFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();

        context = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("data")));
        AgentConfig config = context.getBean(AgentConfig.class);
        config.setRagEnabled(true);
        config.setRagEmbeddingApiKey("fixture-key");
        config.setRagEmbeddingModelName("fixture-embedding");
        config.setRagEmbeddingDimensions(3);
        config.setModelRequestTimeoutSeconds(5);
    }

    @AfterEach
    void stopFixture() {
        if (factory != null) factory.close();
        if (context != null) context.close();
        if (server != null) server.stop(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1", "/v1/"})
    void chatAndEmbeddingCompleteAgainstTheLocalOpenAiCompatibleEndpoint(String basePath)
            throws Exception {
        AgentConfig config = context.getBean(AgentConfig.class);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + basePath;
        configureTier(config, baseUrl);
        config.setRagEmbeddingBaseUrl(baseUrl);
        factory = new SpringAiModelFactory(config, ObservationRegistry.NOOP);
        SpringAiModelRegistry registry = new SpringAiModelRegistry();
        factory.install("fixture-workspace", registry);

        var response = registry.require("fixture-workspace", ModelTier.HIGH)
                .call(new Prompt("E2E local model prompt"));
        double[] embedding = factory.createEmbeddingProvider()
                .embed("E2E local embedding input", Duration.ofSeconds(5));
        var chatRequest = context.getBean(ObjectMapper.class).readTree(chatBody.get());

        assertAll(
                () -> assertEquals("E2E local model response",
                        response.getResult().getOutput().getText()),
                () -> assertEquals(8, response.getMetadata().getUsage().getTotalTokens()),
                () -> assertArrayEquals(new double[] {0.1, 0.2, 0.3}, embedding, 0.000_001),
                () -> assertEquals(1, chatRequests.get()),
                () -> assertEquals(1, embeddingRequests.get()),
                () -> assertEquals("Bearer fixture-key", authorization.get()),
                () -> assertFalse(chatRequest.path("stream").asBoolean(false)),
                () -> assertFalse(chatRequest.has("stream_options")),
                () -> assertTrue(chatBody.get().contains("E2E local model prompt"), chatBody.get()),
                () -> assertTrue(embeddingBody.get().contains("E2E local embedding input"),
                        embeddingBody.get()));
    }

    private static void configureTier(AgentConfig config, String baseUrl) {
        config.setProviderType("OpenAI");
        config.setBaseUrl(baseUrl);
        config.setModelName("fixture-chat");
        config.setApiKey("fixture-key");
        config.setNormalProviderType("OpenAI");
        config.setNormalBaseUrl(baseUrl);
        config.setNormalModelName("fixture-chat");
        config.setNormalApiKey("fixture-key");
        config.setLightProviderType("OpenAI");
        config.setLightBaseUrl(baseUrl);
        config.setLightModelName("fixture-chat");
        config.setLightApiKey("fixture-key");
    }

    private void respond(HttpExchange exchange) throws IOException {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        boolean post = exchange.getRequestMethod().equals("POST");
        int status = 200;
        String response;
        if (post && path.equals("/v1/embeddings")) {
            embeddingRequests.incrementAndGet();
            embeddingBody.set(body);
            response = """
                    {"object":"list","data":[{"object":"embedding","index":0,
                    "embedding":[0.1,0.2,0.3]}],"model":"fixture-embedding",
                    "usage":{"prompt_tokens":1,"total_tokens":1}}
                    """;
        } else if (post && path.equals("/v1/chat/completions")) {
            chatRequests.incrementAndGet();
            chatBody.set(body);
            response = """
                    {"id":"chatcmpl-fixture","object":"chat.completion","created":1786614400,
                    "model":"fixture-chat","choices":[{"index":0,"message":{"role":"assistant",
                    "content":"E2E local model response"},"finish_reason":"stop"}],
                    "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
                    """;
        } else {
            status = 404;
            response = """
                    {"error":{"message":"Unexpected fixture route","type":"not_found"}}
                    """;
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
