package com.javaclaw.server.mcp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.javaclaw.api.BrokerResponse;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;

/** 严格拆分 MCP HTTP JSON 或多事件 SSE，不在 framing 层忽略任何 JSON-RPC frame。 */
final class McpWireResponseDecoder {
    private static final int MAXIMUM_FRAMES = 64;
    private final CanonicalJson json;

    McpWireResponseDecoder(CanonicalJson json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 将一次 HTTP 响应拆成有界 JSON-RPC frame。
     *
     * @param response Broker 返回值
     * @return 按线路顺序排列的 frame
     */
    List<CanonicalPayload> frames(BrokerResponse response) {
        String contentType = response.headers().getOrDefault("content-type", List.of()).stream()
                .findFirst()
                .orElse("")
                .toLowerCase(Locale.ROOT);
        String body = new String(response.body(), StandardCharsets.UTF_8);
        if (contentType.startsWith("application/json")) {
            return List.of(json.parse(body));
        }
        if (contentType.startsWith("text/event-stream")) {
            return sseFrames(body).stream().map(json::parse).toList();
        }
        throw new IllegalArgumentException("MCP response content type is unsupported");
    }

    private static List<String> sseFrames(String body) {
        List<String> frames = new ArrayList<>();
        SseEvent event = new SseEvent();
        for (String rawLine : body.split("\\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.isEmpty()) {
                event.finish(frames);
            } else {
                event.accept(line);
            }
            if (frames.size() > MAXIMUM_FRAMES) {
                throw new IllegalArgumentException("MCP SSE response exceeds the frame limit");
            }
        }
        event.finish(frames);
        if (frames.size() > MAXIMUM_FRAMES) {
            throw new IllegalArgumentException("MCP SSE response exceeds the frame limit");
        }
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("MCP SSE response contains no JSON-RPC frame");
        }
        return List.copyOf(frames);
    }

    private static final class SseEvent {
        private final StringBuilder data = new StringBuilder();
        private String eventName = "message";

        private void accept(String line) {
            if (line.startsWith(":")) {
                return;
            }
            if (line.startsWith("data:")) {
                appendData(line.substring(5).stripLeading());
                return;
            }
            if (line.startsWith("event:")) {
                String value = line.substring(6).strip();
                if (!"message".equals(value)) {
                    throw new IllegalArgumentException("MCP SSE event type is unsupported");
                }
                eventName = value;
                return;
            }
            throw new IllegalArgumentException("MCP SSE response contains unsupported fields");
        }

        private void appendData(String value) {
            if (!data.isEmpty()) {
                data.append('\n');
            }
            data.append(value);
        }

        private void finish(List<String> frames) {
            if (data.isEmpty()) {
                eventName = "message";
                return;
            }
            if (!"message".equals(eventName)) {
                throw new IllegalArgumentException("MCP SSE event type is unsupported");
            }
            frames.add(data.toString());
            data.setLength(0);
            eventName = "message";
        }
    }
}
