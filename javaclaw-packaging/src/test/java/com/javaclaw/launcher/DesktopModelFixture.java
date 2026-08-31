package com.javaclaw.launcher;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 仅测试源码使用的有界 Provider 协议服务；真实 SDK/Provider 链路不替换，且绝不连接外网。 */
final class DesktopModelFixture implements AutoCloseable {
    static final String ANSWER = """
            ## 已保留原桌面风格

            技术架构已切换为 **SDK → App Server → Agent Runtime**。当前消息由本机假模型通过真实流式链路生成。

            - 沿用原主题、布局与绿色视觉语言。
            - AGENTS.md 状态、记忆和技能均通过 SDK 查看或管理。
            - 未执行付费模型调用；原用户数据保持不变。

            ```java
            var thread = client.threads().read(threadId);
            // 持久记录用于断线恢复，token delta 仅供流式显示。
            ```

            | 验证项 | 结果 |
            | --- | --- |
            | 原 UI 样式 | 保留 |
            | 流式消息与代码块 | 已加载 |

            > 此内容是合成验收数据，不代表真实模型效果评估。
            """;

    private final ServerSocket listener;
    private final String provider;
    private final Thread worker;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    DesktopModelFixture() throws IOException {
        this("openai");
    }

    DesktopModelFixture(String provider) throws IOException {
        this.provider = provider;
        listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        worker = Thread.ofVirtual().name("desktop-model-fixture").start(this::serve);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + listener.getLocalPort() + (provider.equals("openai") ? "/v1" : "");
    }

    void verify() {
        if (failure.get() != null || requests.get() != 1) {
            throw new AssertionError("desktop fixture must receive exactly one bounded model request", failure.get());
        }
    }

    private void serve() {
        while (!listener.isClosed()) {
            try (var socket = listener.accept()) {
                socket.setSoTimeout(5_000);
                if (requests.incrementAndGet() > 1) {
                    throw new IOException("unexpected model retry");
                }
                var input = new BufferedInputStream(socket.getInputStream());
                String request = line(input);
                String expected =
                        switch (provider) {
                            case "openai" -> "POST /v1/responses HTTP/1.1";
                            case "anthropic" -> "POST /v1/messages HTTP/1.1";
                            case "google" ->
                                "POST /v1beta/models/desktop-fixture:streamGenerateContent?alt=sse HTTP/1.1";
                            default -> throw new IOException("unknown fixture provider");
                        };
                if (!expected.equals(request)) {
                    throw new IOException("unexpected model endpoint");
                }
                int length = -1;
                for (int headers = 0; ; headers++) {
                    String value = line(input);
                    if (value.isEmpty()) {
                        break;
                    }
                    if (headers == 32
                            || value.toLowerCase(java.util.Locale.ROOT).startsWith("transfer-encoding:")) {
                        throw new IOException("unsupported fixture request framing");
                    }
                    if (value.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                        length = Integer.parseInt(
                                value.substring(value.indexOf(':') + 1).strip());
                    }
                }
                if (length < 1 || length > 65_536 || input.readNBytes(length).length != length) {
                    throw new IOException("fixture request size is invalid");
                }
                String text = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(ANSWER);
                String data = response(text);
                byte[] body = data.getBytes(StandardCharsets.UTF_8);
                var output = socket.getOutputStream();
                String contentType = "text/event-stream";
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\nContent-Length: " + body.length
                                + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                output.write(body);
                output.flush();
            } catch (Exception invalid) {
                if (!listener.isClosed()) {
                    failure.compareAndSet(null, invalid);
                    System.err.println("DESKTOP_MODEL_FIXTURE "
                            + invalid.getClass().getSimpleName() + ": " + invalid.getMessage());
                }
            }
        }
    }

    private String response(String text) {
        return switch (provider) {
            case "openai" ->
                "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\","
                        + "\"item_id\":\"message-desktop\",\"output_index\":0,\"content_index\":0,"
                        + "\"delta\":" + text + ",\"logprobs\":[],\"sequence_number\":1}\n\n"
                        + "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":"
                        + "{\"id\":\"resp-desktop\",\"created_at\":1,\"object\":\"response\","
                        + "\"status\":\"completed\",\"model\":\"desktop-fixture\",\"output\":[{"
                        + "\"id\":\"message-desktop\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":"
                        + text + ",\"annotations\":[],\"logprobs\":[]}]}],\"usage\":{"
                        + "\"input_tokens\":100,\"input_tokens_details\":{\"cached_tokens\":0},"
                        + "\"output_tokens\":100,\"output_tokens_details\":{\"reasoning_tokens\":0},"
                        + "\"total_tokens\":200}},\"sequence_number\":2}\n\n";
            case "anthropic" ->
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{"
                        + "\"id\":\"msg_fixture\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],"
                        + "\"model\":\"desktop-fixture\",\"stop_reason\":null,\"stop_sequence\":null,"
                        + "\"usage\":{\"input_tokens\":100,\"output_tokens\":0}}}\n\n"
                        + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                        + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":" + text + "}}\n\n"
                        + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                        + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{"
                        + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":100}}\n\n"
                        + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
            case "google" ->
                "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":"
                        + text + "}]},\"finishReason\":\"STOP\",\"index\":0}],\"usageMetadata\":{"
                        + "\"promptTokenCount\":100,\"candidatesTokenCount\":100,\"totalTokenCount\":200},"
                        + "\"modelVersion\":\"desktop-fixture\",\"responseId\":\"fixture\"}\n\n";
            default -> throw new IllegalArgumentException("unknown fixture provider");
        };
    }

    private static String line(BufferedInputStream input) throws IOException {
        var value = new ByteArrayOutputStream();
        for (int index = 0; index < 4_096; index++) {
            int next = input.read();
            if (next < 0) {
                throw new IOException("fixture request is truncated");
            }
            if (next == '\n') {
                return value.toString(StandardCharsets.US_ASCII).stripTrailing();
            }
            value.write(next);
        }
        throw new IOException("fixture header exceeds limit");
    }

    @Override
    public void close() throws Exception {
        listener.close();
        worker.join(5_000);
        if (worker.isAlive()) {
            throw new IllegalStateException("desktop fixture did not stop");
        }
    }
}
