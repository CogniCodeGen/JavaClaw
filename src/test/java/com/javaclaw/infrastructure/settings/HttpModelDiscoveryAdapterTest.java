package com.javaclaw.infrastructure.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.settings.DefaultModelProviderCatalog;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService.DiscoveryRequest;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService.Usage;
import com.javaclaw.application.settings.ModelDiscoveryUseCase;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.http.HttpGateway;
import com.javaclaw.platform.http.HttpResult;
import com.javaclaw.platform.json.JsonCodec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpModelDiscoveryAdapterTest {
    private ManagedTaskExecutor tasks;
    private JsonCodec json;
    private HttpServer server;

    @BeforeEach
    void setUp() {
        tasks = new ManagedTaskExecutor();
        json = new JsonCodec(new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (tasks != null) tasks.close();
    }

    @Test
    void openAiCompatibleAndCustomDashscopeUseTheirConfiguredModelsPath() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        var discovery = discovery(request -> {
            requests.add(request);
            return response(request.uri(), 200,
                    "{\"data\":[{\"id\":\"gpt-a\"},{\"id\":\"gpt-b\",\"name\":\"Model B\"}]}");
        });

        var openAi = discovery.discover(form("OpenAI", "https://api.example/v1", "open-secret"));
        var customDashscope = discovery.discover(form("dashscope",
                "https://proxy.example/compatible-mode/v1", "dash-secret"));

        assertTrue(openAi.succeeded());
        assertEquals(List.of("gpt-a", "gpt-b"), openAi.models().stream().map(m -> m.id()).toList());
        assertEquals("Model B", openAi.models().get(1).displayName());
        assertTrue(customDashscope.succeeded());
        assertEquals("https://api.example/v1/models", requests.get(0).uri().toString());
        assertEquals("Bearer open-secret", header(requests.get(0), "Authorization"));
        assertEquals("https://proxy.example/compatible-mode/v1/models",
                requests.get(1).uri().toString());
        assertEquals("Bearer dash-secret", header(requests.get(1), "Authorization"));
    }

    @Test
    void officialDashscopeUsesNativeEndpointAndBoundedPages() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        var discovery = discovery(request -> {
            requests.add(request);
            String body = requests.size() == 1
                    ? "{\"code\":null,\"message\":null,\"success\":true,\"output\":{\"total\":101,\"models\":[{\"model\":\"qwen-a\",\"name\":\"Qwen A\"}]}}"
                    : "{\"code\":null,\"message\":null,\"success\":true,\"output\":{\"total\":101,\"models\":[{\"model\":\"qwen-b\",\"name\":\"Qwen B\"}]}}";
            return response(request.uri(), 200, body);
        });

        var result = discovery.discover(form("dashscope",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "dash-key"));

        assertTrue(result.succeeded());
        assertEquals(List.of("qwen-a", "qwen-b"), result.models().stream().map(m -> m.id()).toList());
        assertEquals("https://dashscope.aliyuncs.com/api/v1/models?page_no=1&page_size=100",
                requests.get(0).uri().toString());
        assertEquals("https://dashscope.aliyuncs.com/api/v1/models?page_no=2&page_size=100",
                requests.get(1).uri().toString());
        assertEquals("Bearer dash-key", header(requests.get(0), "Authorization"));
    }

    @Test
    void officialDashscopeKeepsLegacySuccessResponsesCompatible() throws Exception {
        for (String prefix : new String[] {"", "\"code\":\"Success\","}) {
            var discovery = discovery(request -> response(request.uri(), 200,
                    "{" + prefix + "\"output\":{\"models\":[{\"model\":\"qwen-a\"}]}}"));

            var result = discovery.discover(form("dashscope",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1", "dash-key"));

            assertTrue(result.succeeded());
            assertEquals(List.of("qwen-a"), result.models().stream().map(m -> m.id()).toList());
        }
    }

    @Test
    void officialDashscopeRejectsFailureFlagsAndErrorCodesEvenWithHttp200() throws Exception {
        for (String prefix : new String[] {
                "\"code\":null,\"success\":false,",
                "\"code\":\"InvalidApiKey\",\"success\":false,",
                "\"code\":\"InvalidApiKey\",",
                "\"error\":{\"message\":\"secret-response-marker\"},"}) {
            var discovery = discovery(request -> response(request.uri(), 200,
                    "{" + prefix + "\"output\":{\"models\":[{\"model\":\"qwen-a\"}]}}"));

            var result = discovery.discover(form("dashscope",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1", "secret-api-key"));

            assertFalse(result.succeeded());
            assertTrue(result.models().isEmpty());
            assertTrue(result.message().contains("模型接口返回错误"));
            assertFalse(result.message().contains("secret-response-marker"));
            assertFalse(result.message().contains("secret-api-key"));
        }
    }

    @Test
    void anthropicUsesNativeHeadersAndCursorPagination() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        var discovery = discovery(request -> {
            requests.add(request);
            String body = requests.size() == 1
                    ? "{\"data\":[{\"id\":\"claude-a\",\"display_name\":\"Claude A\"}],\"has_more\":true,\"last_id\":\"a/b+\"}"
                    : "{\"data\":[{\"id\":\"claude-b\"}],\"has_more\":false}";
            return response(request.uri(), 200, body);
        });

        var result = discovery.discover(form("anthropic", "https://api.anthropic.com", "ant-key"));

        assertTrue(result.succeeded());
        assertEquals(2, result.models().size());
        assertEquals("Claude A", result.models().get(0).displayName());
        assertEquals("https://api.anthropic.com/v1/models?limit=100", requests.get(0).uri().toString());
        assertTrue(requests.get(1).uri().toString().contains("after_id=a%2Fb%2B"));
        assertEquals("ant-key", header(requests.get(0), "x-api-key"));
        assertEquals("2023-06-01", header(requests.get(0), "anthropic-version"));
        assertNull(header(requests.get(0), "Authorization"));
    }

    @Test
    void geminiUsesNativeHeaderPaginatesAndFiltersChatModels() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        var discovery = discovery(request -> {
            requests.add(request);
            String body = requests.size() == 1
                    ? "{\"models\":[{\"name\":\"models/gemini-a\",\"displayName\":\"Gemini A\",\"supportedGenerationMethods\":[\"generateContent\"]},"
                    + "{\"name\":\"models/embed-a\",\"supportedGenerationMethods\":[\"embedContent\"]}],\"nextPageToken\":\"next+/\"}"
                    : "{\"models\":[{\"name\":\"models/gemini-b\",\"supportedGenerationMethods\":[\"generateContent\"]}]}";
            return response(request.uri(), 200, body);
        });

        var result = discovery.discover(form("gemini",
                "https://generativelanguage.googleapis.com", "gem-key"));

        assertTrue(result.succeeded());
        assertEquals(List.of("gemini-a", "gemini-b"),
                result.models().stream().map(m -> m.id()).toList());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100",
                requests.get(0).uri().toString());
        assertTrue(requests.get(1).uri().toString().contains("pageToken=next%2B%2F"));
        assertEquals("gem-key", header(requests.get(0), "x-goog-api-key"));
        assertNull(header(requests.get(0), "Authorization"));
    }

    @Test
    void ollamaUsesTagsAndNeedsNoCredential() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        var discovery = discovery(request -> {
            requests.add(request);
            return response(request.uri(), 200,
                    "{\"models\":[{\"name\":\"qwen3:8b\"},{\"model\":\"nomic-embed-text\"}]}");
        });

        var result = discovery.discover(form("ollama", "http://127.0.0.1:11434", "not-needed"));
        var compatible = discovery.discover(form("ollama", "http://127.0.0.1:11434/v1", "not-needed"));

        assertTrue(result.succeeded());
        assertTrue(compatible.succeeded());
        assertEquals(2, result.models().size());
        assertEquals("http://127.0.0.1:11434/api/tags", requests.get(0).uri().toString());
        assertEquals("http://127.0.0.1:11434/api/tags", requests.get(1).uri().toString());
        assertNull(header(requests.get(0), "Authorization"));
    }

    @Test
    void errorsAndResponseBodiesNeverExposeCredentials() throws Exception {
        String secret = "sensitive-key";
        for (int status : new int[] {302, 401, 404, 429, 500}) {
            var discovery = discovery(request -> response(request.uri(), status,
                    "{\"error\":\"" + secret + "\"}"));
            var result = discovery.discover(form("openai", "https://api.example/v1", secret));
            assertFalse(result.succeeded());
            assertTrue(result.message().contains("HTTP " + status));
            assertFalse(result.message().contains(secret));
        }
        var invalid = discovery(request -> response(request.uri(), 200,
                "<html>" + secret + "</html>"));
        var result = invalid.discover(form("openai", "https://api.example/v1", secret));
        assertFalse(result.succeeded());
        assertFalse(result.message().contains(secret));
    }

    @Test
    void noRedirectClientDoesNotSendKeyToRedirectDestination() throws Exception {
        AtomicInteger destinationHits = new AtomicInteger();
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (SocketException unavailable) {
            Assumptions.assumeTrue(false, "当前沙箱不允许监听本地端口");
            return;
        }
        server.createContext("/v1/models", exchange -> {
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/elsewhere", exchange -> {
            destinationHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var discovery = new ModelDiscoveryUseCase(new DefaultModelProviderCatalog(),
                new HttpModelDiscoveryAdapter(new HttpGateway(tasks, client), json));

        var result = discovery.discover(form("openai",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "secret"));

        assertFalse(result.succeeded());
        assertEquals(0, destinationHits.get());
    }

    @Test
    void mismatchedResponseUriIsRejectedEvenWithSuccessfulStatus() throws Exception {
        var discovery = discovery(request -> response(
                URI.create("https://other.example/models"), 200,
                "{\"data\":[{\"id\":\"unsafe\"}]}"));

        var result = discovery.discover(form("openai", "https://api.example/v1", "secret"));

        assertFalse(result.succeeded());
        assertTrue(result.message().contains("重定向"));
        assertFalse(result.message().contains("secret"));
    }

    @Test
    void invalidFormIsRejectedBeforeNetworkAndPagesAreLimited() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var discovery = discovery(request -> {
            int page = calls.incrementAndGet();
            return response(request.uri(), 200,
                    "{\"data\":[{\"id\":\"claude-" + page
                    + "\"}],\"has_more\":true,\"last_id\":\"cursor-" + page + "\"}");
        });

        assertFalse(discovery.discover(form("openai", "https://api.example/v1?key=secret", "k"))
                .succeeded());
        assertFalse(discovery.discover(form("anthropic", "https://api.example", "ENC(secret)"))
                .succeeded());
        assertFalse(discovery.discover(new DiscoveryRequest("anthropic", "https://api.example",
                "k", Usage.EMBEDDING)).succeeded());
        assertEquals(0, calls.get());

        var result = discovery.discover(form("anthropic", "https://api.example", "k"));
        assertTrue(result.succeeded());
        assertEquals(5, calls.get());
        assertEquals(5, result.models().size());
    }

    private ModelDiscoveryUseCase discovery(HttpGateway.Transport transport) {
        return new ModelDiscoveryUseCase(new DefaultModelProviderCatalog(),
                new HttpModelDiscoveryAdapter(new HttpGateway(tasks, transport), json));
    }

    private static DiscoveryRequest form(String provider, String baseUrl, String key) {
        return new DiscoveryRequest(provider, baseUrl, key, Usage.CHAT);
    }

    private static String header(HttpRequest request, String name) {
        return request.headers().firstValue(name).orElse(null);
    }

    private static HttpResult response(URI uri, int status, String body) {
        return new HttpResult(uri, status,
                HttpHeaders.of(Map.of(), (name, value) -> true),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
