package com.javaclaw.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** 仅监听回环地址的 OpenAI Chat 假端点，保存请求并返回固定 SSE 或 HTTP 失败。 */
final class LocalVerificationHttpEndpoint implements AutoCloseable {
    private static final String SSE = """
        data: {"id":"chatcmpl-local","object":"chat.completion.chunk","created":1,"model":"local-chat-model","choices":[{"index":0,"delta":{"role":"assistant","content":"OK"},"finish_reason":null}]}

        data: {"id":"chatcmpl-local","object":"chat.completion.chunk","created":1,"model":"local-chat-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

        data: {"id":"chatcmpl-local","object":"chat.completion.chunk","created":1,"model":"local-chat-model","choices":[],"usage":{"prompt_tokens":4,"completion_tokens":1,"total_tokens":5}}

        data: [DONE]

        """;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final int status;

    LocalVerificationHttpEndpoint(int status) throws IOException {
        this.status = status;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.setExecutor(executor);
        server.start();
    }

    URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            requests.add(new Request(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    Optional.ofNullable(exchange.getRequestHeaders().getFirst("Authorization")),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            String body = status == 200
                    ? SSE
                    : "{\"error\":{\"message\":\"local"
                            + " rejection\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", status == 200 ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    /**
     * 本地端点实际收到的 HTTP 请求，不来自 Adapter 的内部模拟。
     *
     * @param method HTTP 方法
     * @param path 请求路径
     * @param authorization 可选鉴权头；本夹具使用无鉴权服务，应为空
     * @param body 完整 JSON 请求，仅包含测试数据
     */
    record Request(String method, String path, Optional<String> authorization, String body) {}
}
