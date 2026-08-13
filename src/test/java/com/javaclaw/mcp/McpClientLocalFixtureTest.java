package com.javaclaw.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.json.JsonCodec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protocol acceptance for both MCP transports using only local deterministic fixtures. */
class McpClientLocalFixtureTest {

    private static final McpClient.TransportPolicy LOCAL_FIXTURE_POLICY =
            new McpClient.TransportPolicy() {
                @Override public void requireStdioAllowed() { }
                @Override public URI requireHttpEndpoint(String rawUrl) {
                    return URI.create(rawUrl);
                }
            };

    @Test
    void stdioTransportInitializesDiscoversInvokesAndStopsTheFixtureProcess() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        McpServerConfig config = new McpServerConfig(
                "local-stdio", java,
                List.of("-cp", System.getProperty("java.class.path"),
                        StdioFixtureMain.class.getName()),
                Map.of(), true);

        try (ManagedTaskExecutor executor = new ManagedTaskExecutor();
             TaskScope tasks = executor.openScope("mcp-stdio-fixture", 4)) {
            McpClient client = new McpClient(config, tasks,
                    new JsonCodec(new ObjectMapper()), LOCAL_FIXTURE_POLICY);
            try {
                client.start();
                ObjectNode arguments = new ObjectMapper().createObjectNode()
                        .put("value", "E2E stdio");
                String result = client.callTool("fixture_echo", arguments);

                assertAll(
                        () -> assertEquals(McpClient.ServerState.RUNNING, client.getState()),
                        () -> assertEquals("fixture-stdio", client.getServerName()),
                        () -> assertEquals("1.0.0", client.getServerVersion()),
                        () -> assertEquals(List.of("fixture_echo"), client.getTools().stream()
                                .map(McpClient.McpToolInfo::getName).toList()),
                        () -> assertEquals("echo:E2E stdio", result));
            } finally {
                client.stop();
            }
            assertEquals(McpClient.ServerState.STOPPED, client.getState());
        }
    }

    @Test
    void httpTransportReturnsTheSessionHeaderAndInvokesTheFixtureTool() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> sessionHeader = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> respondHttp(
                exchange, mapper, requests, sessionHeader, authorization));
        server.start();
        try (ManagedTaskExecutor executor = new ManagedTaskExecutor();
             TaskScope tasks = executor.openScope("mcp-http-fixture", 2)) {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
            McpServerConfig config = new McpServerConfig(
                    "local-http", url, Map.of("Authorization", "Bearer fixture"), true);
            McpClient client = new McpClient(
                    config, tasks, new JsonCodec(mapper), LOCAL_FIXTURE_POLICY);
            try {
                client.start();
                String result = client.callTool("fixture_echo",
                        mapper.createObjectNode().put("value", "E2E http"));

                assertAll(
                        () -> assertEquals(McpClient.ServerState.RUNNING, client.getState()),
                        () -> assertEquals("fixture-http", client.getServerName()),
                        () -> assertEquals("echo:E2E http", result),
                        () -> assertEquals("Bearer fixture", authorization.get()),
                        () -> assertEquals("fixture-session", sessionHeader.get()),
                        () -> assertTrue(requests.get() >= 4,
                                "HTTP MCP 请求数不足: " + requests.get()));
            } finally {
                client.stop();
            }
        } finally {
            server.stop(0);
        }
    }

    private static void respondHttp(
            HttpExchange exchange,
            ObjectMapper mapper,
            AtomicInteger requests,
            AtomicReference<String> sessionHeader,
            AtomicReference<String> authorization) throws java.io.IOException {
        requests.incrementAndGet();
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        String suppliedSession = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (suppliedSession != null) sessionHeader.set(suppliedSession);
        JsonNode request = mapper.readTree(exchange.getRequestBody());
        if (!request.has("id")) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        ObjectNode response = response(mapper, request, "fixture-http");
        byte[] bytes = mapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if ("initialize".equals(request.path("method").asText())) {
            exchange.getResponseHeaders().set("Mcp-Session-Id", "fixture-session");
        }
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static ObjectNode response(
            ObjectMapper mapper, JsonNode request, String serverName) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        ObjectNode result = response.putObject("result");
        switch (request.path("method").asText()) {
            case "initialize" -> {
                result.put("protocolVersion", "2024-11-05");
                result.putObject("capabilities");
                result.putObject("serverInfo").put("name", serverName)
                        .put("version", "1.0.0");
            }
            case "tools/list" -> result.putArray("tools").addObject()
                    .put("name", "fixture_echo")
                    .put("description", "Local fixture echo")
                    .putObject("inputSchema").put("type", "object");
            case "tools/call" -> result.putArray("content").addObject()
                    .put("type", "text")
                    .put("text", "echo:" + request.path("params")
                            .path("arguments").path("value").asText());
            default -> { }
        }
        return response;
    }

    /** Child process used by the stdio test; intentionally public for ProcessBuilder. */
    public static final class StdioFixtureMain {
        private StdioFixtureMain() { }

        public static void main(String[] args) throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            try (BufferedReader input = new BufferedReader(new InputStreamReader(
                    System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    JsonNode request = mapper.readTree(line);
                    if (!request.has("id")) continue;
                    ObjectNode output = response(mapper, request, "fixture-stdio");
                    System.out.println(mapper.writeValueAsString(output));
                    System.out.flush();
                }
            }
        }
    }
}
