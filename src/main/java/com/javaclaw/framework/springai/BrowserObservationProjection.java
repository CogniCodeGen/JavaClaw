package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.util.ExternalContentGuard;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 只裁剪浏览器读取的模型视图；完整工具结果与宿主验收证明保持在执行日志中。 */
final class BrowserObservationProjection {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TOOLS = Set.of("web_snapshot", "web_get_text");
    private static final String START = "【外部不可信内容开始：";
    private static final String END = "【外部不可信内容结束】";
    private static final String DISPLAY = "浏览器观察摘录；完整结果保存在执行日志。";
    private static final String NOTICE = "\n[宿主投影：本次浏览器观察摘录已截断，未显示的内容仍保存在执行日志。"
            + "需要更多正文时请使用 web_get_text 精确定位元素；不要仅因摘录不完整而重复导航。]";

    private BrowserObservationProjection() { }

    static List<Message> project(List<Message> messages, int limit) {
        if (limit < 1) throw new IllegalArgumentException("result budget must be positive");
        List<Message> result = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage response)) {
                result.add(message);
                continue;
            }
            boolean changed = false;
            List<ToolResponseMessage.ToolResponse> values = new ArrayList<>();
            for (var value : response.getResponses()) {
                String bounded = project(value.name(), value.responseData(), limit);
                changed |= !java.util.Objects.equals(bounded, value.responseData());
                values.add(new ToolResponseMessage.ToolResponse(value.id(), value.name(), bounded));
            }
            result.add(changed ? ToolResponseMessage.builder().responses(values)
                    .metadata(response.getMetadata()).build() : response);
        }
        return List.copyOf(result);
    }

    private static String project(String tool, String raw, int limit) {
        if (!TOOLS.contains(tool) || raw == null) return raw;
        try {
            JsonNode parsed = JSON.readTree(raw);
            if (!(parsed instanceof ObjectNode source)
                    || !"SUCCEEDED".equals(source.path("status").asText())) return raw;
            JsonNode data = source.path("data");
            boolean truncated = truncatedPreview(data, tool);
            if (!data.isTextual() && !truncated) return raw;
            if (fits(source, limit)) return raw;

            ObjectNode envelope = source.deepCopy();
            // displayMessage 原本可能重复全部正文，不能在裁剪 data 后继续占用两份预算。
            envelope.put("displayMessage", DISPLAY);
            String text = truncated ? data.path("preview").asText() : data.asText();
            Excerpt excerpt = excerpt(text);
            ObjectNode preview = truncated ? ((ObjectNode) data).deepCopy() : null;
            write(envelope, preview, excerpt, 0);
            if (!fits(envelope, limit)) return raw;

            int low = 0;
            int high = excerpt.body().length();
            while (low < high) {
                int middle = low + (high - low + 1) / 2;
                write(envelope, preview, excerpt, middle);
                if (fits(envelope, limit)) low = middle;
                else high = middle - 1;
            }
            write(envelope, preview, excerpt, low);
            return envelope.toString();
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            // 非浏览器观察格式仍由现有投影器处理，不能扩大默认预算或改写执行状态。
            return raw;
        }
    }

    private static boolean truncatedPreview(JsonNode data, String tool) {
        return data instanceof ObjectNode && data.path("truncated").isBoolean()
                && data.path("truncated").asBoolean()
                && tool.equals(data.path("tool").asText())
                && data.path("originalCharacters").isIntegralNumber()
                && data.path("originalCharacters").asLong(-1) >= 0
                && data.path("preview").isTextual();
    }

    private static Excerpt excerpt(String text) {
        int start = text.indexOf(START);
        int sourceEnd = start < 0 ? -1 : text.indexOf('】', start + START.length());
        if (sourceEnd < 0) return new Excerpt("浏览器观察摘录", text);
        String source = prefix(text.substring(start + START.length(), sourceEnd), 256);
        String body = text.substring(sourceEnd + 1);
        int end = body.lastIndexOf(END);
        if (end >= 0) body = body.substring(0, end);
        return new Excerpt(source, body);
    }

    private static void write(ObjectNode envelope, ObjectNode preview, Excerpt excerpt, int count) {
        String text = ExternalContentGuard.wrap(excerpt.source(), prefix(excerpt.body(), count)) + NOTICE;
        if (preview == null) envelope.put("data", text);
        else {
            preview.put("preview", text);
            preview.put("projectionTruncated", true);
            envelope.set("data", preview);
        }
    }

    private static String prefix(String text, int count) {
        int end = Math.min(count, text.length());
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    private static boolean fits(ObjectNode envelope, int limit) {
        JsonNode data = envelope.path("data");
        int payload = data.isTextual() ? data.asText().length() : data.toString().length();
        return payload <= limit && (long) envelope.toString().length() <= (long) limit + 512;
    }

    private record Excerpt(String source, String body) { }
}
