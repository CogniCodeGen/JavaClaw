package com.javaclaw.framework.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.CapabilityRuntime;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.core.StepContextPolicy;
import com.javaclaw.framework.spi.ToolDescriptor;
import com.javaclaw.framework.spi.ToolExecutionContext;
import com.javaclaw.framework.springai.DesktopDiscoveryModelProjection;

/** Bounds model data while the gateway retains the original data and display in durable events. */
final class ToolResultEviction {
    private ToolResultEviction() { }

    static JsonNode process(JsonNode current, ToolDescriptor tool, ToolExecutionContext context, RunRequest request) {
        if (context.internalContextRead()) return current;
        int configured = CapabilityRuntime.configuration(request, "tool.result-eviction")
                .path("maxCharacters").asInt(16_000);
        JsonNode compaction = CapabilityRuntime.configuration(request, "context.compaction");
        int contextLimit = compaction.isMissingNode() ? Integer.MAX_VALUE
                : StepContextPolicy.from(compaction).maxToolResultCharacters();
        return bound(current, tool.name(), Math.max(1, Math.min(configured, contextLimit)));
    }

    static JsonNode bound(JsonNode current, String toolName, int limit) {
        if (DesktopDiscoveryModelProjection.recognizes(toolName, current))
            return DesktopDiscoveryModelProjection.data(toolName, current, limit);
        String rendered = current.isTextual() ? current.asText() : current.toString();
        if (current.toString().length() <= limit) return current;
        ObjectNode bounded = JsonNodeFactory.instance.objectNode();
        bounded.put("truncated", true).put("tool", toolName).put("originalCharacters", rendered.length());
        bounded.put("note", "Full output is retained in core.tool.completed");
        // Escaping inside preview also consumes the wire budget. Do not assume one character costs one byte of JSON.
        int low = 0;
        int high = Math.min(rendered.length(), limit);
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            bounded.put("preview", prefix(rendered, middle));
            if (bounded.toString().length() <= limit) low = middle;
            else high = middle - 1;
        }
        bounded.put("preview", prefix(rendered, low));
        return bounded;
    }

    private static String prefix(String text, int length) {
        if (length > 0 && length < text.length() && Character.isHighSurrogate(text.charAt(length - 1))) length--;
        return text.substring(0, length);
    }
}
