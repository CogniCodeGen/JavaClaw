package com.javaclaw.memory.curation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.util.TextSimilarity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.function.Consumer;

/** Selects bounded, independently identifiable completed-tool evidence for distillation. */
final class ToolEvidenceSelector {
    private static final int MAX_FRAGMENT_CHARACTERS = 1_800;
    private static final int MAX_SELECTED_CHARACTERS = 8_000;
    private static final int MAX_SELECTED_UNITS = 6;
    private static final int MAX_CANDIDATES = 512;

    private ToolEvidenceSelector() { }

    static Selection select(Episode episode, ObjectMapper json) {
        if (episode.toolTraceJson == null || episode.toolTraceJson.isBlank()) {
            return new Selection(JsonNodeFactory.instance.arrayNode(), "", 0, 0);
        }
        JsonNode trace;
        try {
            trace = json.readTree(episode.toolTraceJson);
        } catch (Exception failure) {
            throw new IllegalStateException("记忆蒸馏的持久工具轨迹不是有效 JSON，保留待处理", failure);
        }
        String query = episode.userInput + "\n" + episode.assistantReply;
        CandidatePool candidates = new CandidatePool(query);
        if (trace.isArray()) {
            int eventIndex = 0;
            for (JsonNode event : trace) {
                appendCompleted(candidates, event, eventIndex++);
            }
        } else {
            appendCompleted(candidates, trace, 0);
        }
        List<Unit> ranked = candidates.values().stream()
                .sorted(Comparator.comparingDouble(Unit::score).reversed()
                        .thenComparingInt(Unit::ordinal))
                .toList();
        List<Unit> selected = new ArrayList<>();
        int characters = 0;
        for (Unit unit : ranked) {
            if (selected.size() == MAX_SELECTED_UNITS) break;
            if (characters + unit.text().length() > MAX_SELECTED_CHARACTERS) continue;
            selected.add(unit);
            characters += unit.text().length();
        }
        selected.sort(Comparator.comparingInt(Unit::ordinal));
        ArrayNode evidence = JsonNodeFactory.instance.arrayNode();
        StringBuilder plainText = new StringBuilder();
        for (Unit unit : selected) {
            evidence.addObject().put("id", unit.id()).put("tool", unit.tool())
                    .put("text", unit.text());
            plainText.append(unit.text()).append('\n');
        }
        return new Selection(evidence, plainText.toString(), candidates.scanned(), selected.size());
    }

    private static double relevance(String query, String text) {
        return TextSimilarity.bigramJaccard(query, text);
    }

    private static void appendCompleted(CandidatePool result, JsonNode event, int eventIndex) {
        if (!"core.tool.completed".equals(event.path("type").asText())
                || event.path("payload").path("waitingInput").asBoolean(false)) return;
        JsonNode payload = event.path("payload");
        JsonNode output = payload.path("output");
        if (output.isMissingNode() || output.isNull()) return;
        String tool = payload.path("tool").asText("");
        int[] fragmentIndex = {0};
        collectText(output, fragment -> {
            if (!fragment.isBlank()) result.offer(eventIndex, fragmentIndex[0]++, tool, fragment);
        });
    }

    private static void collectText(JsonNode node, Consumer<String> result) {
        if (node.isTextual()) {
            String text = node.asText().strip();
            for (int start = 0; start < text.length(); start += MAX_FRAGMENT_CHARACTERS) {
                result.accept(text.substring(start,
                        Math.min(text.length(), start + MAX_FRAGMENT_CHARACTERS)));
            }
        } else if (node.isArray()) {
            node.forEach(value -> collectText(value, result));
        } else if (node.isObject()) {
            node.properties().forEach(entry -> collectText(entry.getValue(), result));
        } else if (node.isNumber() || node.isBoolean()) {
            result.accept(node.asText());
        }
    }

    private static final class CandidatePool {
        private static final Comparator<Unit> WORST_FIRST = Comparator.comparingDouble(Unit::score)
                .thenComparing(Comparator.comparingInt(Unit::ordinal).reversed());
        private final String query;
        private final Map<String, Unit> byText = new HashMap<>();
        private final PriorityQueue<Unit> ranked = new PriorityQueue<>(WORST_FIRST);
        private int scanned;

        private CandidatePool(String query) { this.query = query; }

        void offer(int eventIndex, int fragmentIndex, String tool, String text) {
            int ordinal = scanned++;
            if (byText.containsKey(text)) return;
            double score = relevance(query, text);
            if (ranked.size() == MAX_CANDIDATES
                    && WORST_FIRST.compare(new Unit("", tool, text, ordinal, score), ranked.peek()) <= 0) {
                return;
            }
            String identity = eventIndex + ":" + fragmentIndex + ":" + tool + ":" + text;
            String id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
            Unit candidate = new Unit(id, tool, text, ordinal, score);
            if (ranked.size() == MAX_CANDIDATES) {
                Unit removed = ranked.remove();
                byText.remove(removed.text());
            }
            ranked.add(candidate);
            byText.put(text, candidate);
        }

        List<Unit> values() { return List.copyOf(ranked); }
        int scanned() { return scanned; }
    }

    record Selection(ArrayNode evidence, String plainText, int candidateCount, int selectedCount) {
        Selection {
            evidence = evidence.deepCopy();
        }

        @Override public ArrayNode evidence() { return evidence.deepCopy(); }
    }

    private record Unit(String id, String tool, String text, int ordinal, double score) { }
}
