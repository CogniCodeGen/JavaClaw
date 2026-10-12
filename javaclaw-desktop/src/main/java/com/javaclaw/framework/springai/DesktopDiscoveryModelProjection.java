package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** A model view of discovery data, never a source of host receipts or input authority. */
public final class DesktopDiscoveryModelProjection {
    static final String DISPLAY = "桌面窗口发现结果；完整内容保存在执行日志。";

    private DesktopDiscoveryModelProjection() { }

    /** Recognition alone grants no exemption from the result budget. */
    public static boolean recognizes(String toolName, JsonNode value) {
        String rows = rowsField(toolName);
        if (rows == null || !(value instanceof ObjectNode data)
                || !data.path("schemaVersion").isIntegralNumber()
                || data.path("schemaVersion").asInt(-1) != 1
                || !"computer-use".equals(data.path("protocol").asText())
                || !expectedKind(toolName).equals(data.path("kind").asText())
                || !(data.path(rows) instanceof ArrayNode values)) return false;
        if (rows.equals("candidates") && (!identity(data, "sessionId")
                || !identity(data, "sourceTargetId"))) return false;
        for (JsonNode valueRow : values) {
            if (!(valueRow instanceof ObjectNode row) || !identity(row, "targetId")
                    || !identity(row, "providerId") || !row.path("processId").isIntegralNumber()
                    || row.path("processId").asLong() <= 0) return false;
        }
        return true;
    }

    /** Bounds the structured payload before generic tool-result eviction can turn it into a string. */
    public static JsonNode data(String toolName, JsonNode source, int limit) {
        if (!recognizes(toolName, source)) return source;
        return bound(toolName, (ObjectNode) source, limit,
                data -> data.toString().length() <= limit, data -> data.toString().length(), List.of());
    }

    /** Includes the final status, display text and every evidence reference in admission. */
    static ObjectNode envelope(String toolName, ObjectNode original, int limit) {
        if (!recognizes(toolName, original.path("data"))) return null;
        ObjectNode envelope = original.deepCopy();
        envelope.put("displayMessage", DISPLAY);
        List<String> refs = new ArrayList<>();
        envelope.path("evidenceRefs").forEach(ref -> { if (ref.isTextual()) refs.add(ref.asText()); });
        ObjectNode bounded = bound(toolName, (ObjectNode) envelope.path("data"), limit, data -> {
            envelope.set("data", data);
            return data.toString().length() <= limit
                    && (long) envelope.toString().length() <= (long) limit + 512;
        }, data -> {
            envelope.set("data", data);
            return envelope.toString().length();
        }, refs);
        envelope.set("data", bounded);
        return envelope;
    }

    private static ObjectNode bound(String toolName, ObjectNode original, int limit,
            Predicate<ObjectNode> fits, ToIntFunction<ObjectNode> characters, List<String> refs) {
        if (limit < 1) throw new IllegalArgumentException("result budget must be positive");
        ObjectNode data = original.deepCopy();
        if (fits.test(data)) return data;
        String rowsField = rowsField(toolName);
        ArrayNode originalRows = (ArrayNode) original.path(rowsField);
        ArrayNode rows = data.putArray(rowsField);
        update(data, original, rows.size(), originalRows.size());
        if (!fits.test(data)) throw irreducible(characters.applyAsInt(data), limit, refs);
        // Only a contiguous prefix is admitted: a subsequent page must not skip an omitted target.
        for (JsonNode row : originalRows) {
            rows.add(row.deepCopy());
            update(data, original, rows.size(), originalRows.size());
            if (!fits.test(data)) {
                rows.remove(rows.size() - 1);
                update(data, original, rows.size(), originalRows.size());
                break;
            }
        }
        if (!originalRows.isEmpty() && rows.isEmpty()) throw irreducible(characters.applyAsInt(original), limit, refs);
        return data;
    }

    private static void update(ObjectNode data, ObjectNode original, int kept, int sourceCount) {
        data.put("projectionTruncated", true).put("projectedTargetCount", kept);
        data.put("projectionOmittedTargetCount",
                original.path("projectionOmittedTargetCount").asLong(0) + sourceCount - kept);
        data.put("truncated", true);
        if (data.has("count")) data.put("count", kept);
        if (data.has("returnedCount")) data.put("returnedCount", kept);
        if (data.has("complete")) data.put("complete", false);
        // These fields describe the actual inventory page, not a fabricated paging protocol.
        if (original.path("offset").isIntegralNumber() && original.path("offset").canConvertToInt()
                && original.path("offset").asInt() >= 0
                && original.path("totalCount").isIntegralNumber()
                && original.path("totalCount").asLong() >= (long) original.path("offset").asInt() + sourceCount
                && original.path("hasMore").isBoolean()) {
            long next = (long) original.path("offset").asInt() + kept;
            boolean more = next < original.path("totalCount").asLong();
            data.put("hasMore", more);
            if (more) data.put("nextOffset", next);
            else data.remove("nextOffset");
        }
    }

    private static LocalContextBudgetExceededException irreducible(int characters, int limit, List<String> refs) {
        return new LocalContextBudgetExceededException("computer_use_discovery_identity", limit, characters,
                Map.of("discovery_identity", characters), refs);
    }

    private static boolean identity(ObjectNode data, String field) {
        return data.path(field).isTextual() && !data.path(field).asText().isBlank();
    }

    private static String rowsField(String toolName) {
        return switch (toolName) {
            case "desktop_session_targets" -> "targets";
            case "desktop_session_window_candidates" -> "candidates";
            default -> null;
        };
    }

    private static String expectedKind(String toolName) {
        return toolName.equals("desktop_session_targets") ? "desktop.targets" : "desktop.window_candidates";
    }
}
