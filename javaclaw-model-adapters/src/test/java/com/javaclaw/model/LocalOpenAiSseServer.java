package com.javaclaw.model;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 仅监听回环地址的 SSE 夹具，每个 HTTP 请求消耗一份独立脚本。
 *
 * <p>请求只记录协议形状，不保存请求正文或鉴权头；关闭服务时释放本夹具拥有的连接及接收线程。
 */
final class LocalOpenAiSseServer implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ServerSocket server;
    private final List<List<String>> responses;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Socket> activeConnection = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Thread worker;

    LocalOpenAiSseServer(List<List<String>> responses) throws IOException {
        this.responses = responses.stream().map(List::copyOf).toList();
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        worker = Thread.ofVirtual().start(this::serve);
    }

    URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getLocalPort() + "/v1");
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    static List<String> completed(String... chunks) {
        List<String> response = new ArrayList<>(List.of(chunks));
        response.add(finished("tool_calls"));
        response.add(usage());
        return List.copyOf(response);
    }

    static String tool(int index, String id, String name, String arguments) {
        ObjectNode call = JSON.createObjectNode().put("index", index);
        if (id != null) {
            call.put("id", id).put("type", "function");
        }
        ObjectNode function = call.putObject("function");
        if (name != null) {
            function.put("name", name);
        }
        if (arguments != null) {
            function.put("arguments", arguments);
        }
        ObjectNode delta = JSON.createObjectNode();
        delta.putArray("tool_calls").add(call);
        return chunk(delta, null).toString();
    }

    static String text(String text) {
        return chunk(JSON.createObjectNode().put("content", text), null).toString();
    }

    static String finished(String reason) {
        return chunk(JSON.createObjectNode(), reason).toString();
    }

    static String emptyTools() {
        ObjectNode delta = JSON.createObjectNode();
        delta.putArray("tool_calls");
        return chunk(delta, null).toString();
    }

    static String combinedTools(String... toolChunks) throws IOException {
        ObjectNode delta = JSON.createObjectNode();
        var calls = delta.putArray("tool_calls");
        for (String toolChunk : toolChunks) {
            JsonNode tools = JSON.readTree(toolChunk)
                    .path("choices")
                    .get(0)
                    .path("delta")
                    .path("tool_calls");
            tools.forEach(calls::add);
        }
        return chunk(delta, null).toString();
    }

    private static String usage() {
        ObjectNode response = envelope();
        response.putArray("choices");
        response.putObject("usage")
                .put("prompt_tokens", 8)
                .put("completion_tokens", 3)
                .put("total_tokens", 11);
        return response.toString();
    }

    private static ObjectNode chunk(ObjectNode delta, String finishReason) {
        ObjectNode response = envelope();
        ObjectNode choice = response.putArray("choices").addObject().put("index", 0);
        choice.set("delta", delta);
        choice.put("finish_reason", finishReason);
        return response;
    }

    private static ObjectNode envelope() {
        return JSON.createObjectNode()
                .put("id", "chatcmpl-local")
                .put("object", "chat.completion.chunk")
                .put("created", 1)
                .put("model", "provider-model");
    }

    private void serve() {
        try {
            while (!server.isClosed()) {
                respond();
            }
        } catch (IOException exception) {
            if (!server.isClosed()) {
                failure.compareAndSet(null, exception);
            }
        }
    }

    private void respond() throws IOException {
        try (Socket connection = server.accept()) {
            activeConnection.set(connection);
            connection.setSoTimeout(Math.toIntExact(TimeUnit.SECONDS.toMillis(5)));
            InputStream input = new BufferedInputStream(connection.getInputStream());
            String[] requestLine = line(input).split(" ", 3);
            JsonNode body = JSON.readTree(body(input));
            requests.add(new Request(
                    requestLine[0],
                    URI.create(requestLine[1]).getPath(),
                    body.path("stream").asBoolean(),
                    body.path("tools").size()));
            writeResponse(connection.getOutputStream(), requests.size() - 1);
        } finally {
            activeConnection.set(null);
        }
    }

    private static byte[] body(InputStream input) throws IOException {
        int length = -1;
        boolean chunked = false;
        String header;
        while (!(header = line(input)).isEmpty()) {
            int separator = header.indexOf(':');
            String name = header.substring(0, separator);
            String value = header.substring(separator + 1).strip();
            if (name.equalsIgnoreCase("Content-Length")) {
                length = Integer.parseInt(value);
            } else if (name.equalsIgnoreCase("Transfer-Encoding")) {
                chunked = value.equalsIgnoreCase("chunked");
            }
        }
        if (chunked) {
            return chunks(input);
        }
        if (length < 0) {
            throw new IOException("本地 SSE 请求缺少消息长度");
        }
        return input.readNBytes(length);
    }

    private static byte[] chunks(InputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int length = Integer.parseInt(line(input).split(";", 2)[0], 16);
        while (length > 0) {
            body.write(input.readNBytes(length));
            line(input);
            length = Integer.parseInt(line(input).split(";", 2)[0], 16);
        }
        line(input);
        return body.toByteArray();
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) >= 0 && value != '\n') {
            if (value != '\r') {
                bytes.write(value);
            }
        }
        if (value < 0) {
            throw new IOException("本地 SSE 请求提前结束");
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    private void writeResponse(OutputStream output, int index) throws IOException {
        boolean found = index < responses.size();
        StringBuilder body = new StringBuilder();
        if (found) {
            for (String chunk : responses.get(index)) {
                body.append("data: ").append(chunk).append("\n\n");
            }
            body.append("data: [DONE]\n\n");
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + (found ? "200 OK" : "500 Unexpected request")
                + "\r\nContent-Type: text/event-stream; charset=utf-8\r\nContent-Length: " + bytes.length
                + "\r\nConnection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.flush();
    }

    @Override
    public void close() throws Exception {
        server.close();
        Socket connection = activeConnection.get();
        if (connection != null) {
            connection.close();
        }
        worker.join(TimeUnit.SECONDS.toMillis(2));
        Throwable problem = failure.get();
        if (problem != null) {
            throw new AssertionError("本地 SSE 服务执行失败", problem);
        }
    }

    /**
     * 请求的非敏感协议形状。
     *
     * @param method HTTP 方法
     * @param path HTTP 路径，不含查询参数
     * @param streaming 是否请求 SSE
     * @param toolCount 提交的工具定义数量
     */
    record Request(String method, String path, boolean streaming, int toolCount) {}
}
