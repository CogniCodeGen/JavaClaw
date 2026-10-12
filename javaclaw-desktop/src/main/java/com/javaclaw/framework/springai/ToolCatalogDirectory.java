package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.springai.ToolCatalogSession.ToolGroupSummary;
import com.javaclaw.framework.springai.ToolCatalogSession.ToolCandidate;
import org.springframework.ai.tool.ToolCallback;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 授权工具目录的纯筛选、激活参数验证和有界结果编码，不改变授权。 */
final class ToolCatalogDirectory {
    private ToolCatalogDirectory() { }

    static List<ToolGroupSummary> groupDirectory(List<ToolCallback> allowed) {
        Map<String, Integer> counts = new TreeMap<>();
        for (ToolCallback callback : allowed) {
            if (callback instanceof HarnessDecisionToolCallback) continue;
            counts.merge(group(callback), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> new ToolGroupSummary(entry.getKey(), entry.getValue())).toList();
    }

    static List<ToolCandidate> searchDirectory(List<ToolCallback> allowed,
            String query, List<String> groups, int limit) {
        Objects.requireNonNull(allowed, "allowed");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(groups, "groups");
        if (limit < 0 || query.length() > 128) {
            throw new IllegalArgumentException("candidate limit must be nonnegative and query at most 128 characters");
        }
        if (limit == 0) return List.of();
        String term = query.strip().toLowerCase(Locale.ROOT);
        List<String> terms = List.of(term.split("[\\s\\p{P}\\p{S}]+"));
        Set<String> selectedGroups = new LinkedHashSet<>(groups);
        if (term.isEmpty() && selectedGroups.isEmpty()) return List.of();
        List<ScoredTool> matching = new ArrayList<>();
        List<ScoredTool> groupBackfill = new ArrayList<>();
        Set<String> groupsWithHits = new HashSet<>();
        int ordinal = 0;
        for (ToolCallback callback : allowed) {
            if (callback instanceof HarnessDecisionToolCallback) continue;
            String group = group(callback);
            int position = ordinal++;
            if (!selectedGroups.isEmpty() && !selectedGroups.contains(group)) continue;
            int score = relevance(callback, term, terms);
            ScoredTool candidate = new ScoredTool(callback, score, position);
            if (term.isEmpty() || score > 0) {
                matching.add(candidate);
                if (score > 0) groupsWithHits.add(group);
            }
            else if (!selectedGroups.isEmpty()) groupBackfill.add(candidate);
        }
        matching.sort(Comparator.comparingInt(ScoredTool::score).reversed()
                .thenComparingInt(ScoredTool::position));
        // 先为每个显式选择的组保留一个候选，避免大组或高分组占满目录。
        Set<ScoredTool> chosen = new LinkedHashSet<>();
        for (String group : selectedGroups) {
            matching.stream().filter(tool -> group(tool.callback()).equals(group))
                    .findFirst().or(() -> groupBackfill.stream()
                            .filter(tool -> group(tool.callback()).equals(group)).findFirst())
                    .ifPresent(chosen::add);
            if (chosen.size() == limit) break;
        }
        for (ScoredTool tool : matching) {
            if (chosen.size() == limit) break;
            chosen.add(tool);
        }
        for (ScoredTool tool : groupBackfill) {
            if (chosen.size() == limit) break;
            if (!groupsWithHits.contains(group(tool.callback()))) chosen.add(tool);
        }
        List<ScoredTool> selected = new ArrayList<>(chosen);
        selected.sort(Comparator.comparingInt(ScoredTool::score).reversed()
                .thenComparingInt(ScoredTool::position));
        return selected.stream().map(value -> {
            ToolCallback callback = value.callback();
            var definition = callback.getToolDefinition();
            return new ToolCandidate(definition.name(), group(callback),
                    definition.description(), fingerprint(callback),
                    SpringAiToolCatalog.schemaCharacters(callback));
        }).toList();
    }

    /** 多词查询仅在唯一明确匹配时扩展该授权组，供工具发现使用。 */
    static List<ToolCatalogPages.Entry> listDirectory(
            List<ToolCallback> allowed, String query, String selectedGroup) {
        Objects.requireNonNull(allowed, "allowed");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(selectedGroup, "selectedGroup");
        String term = query.strip().toLowerCase(Locale.ROOT);
        List<String> terms = List.of(term.split("[\\s\\p{P}\\p{S}]+"));
        List<ScoredTool> scoped = new ArrayList<>();
        int position = 0;
        for (ToolCallback callback : allowed) {
            if (callback instanceof HarnessDecisionToolCallback) continue;
            String group = group(callback);
            if (!selectedGroup.isEmpty() && !selectedGroup.equals(group)) continue;
            scoped.add(new ScoredTool(callback, relevance(callback, term, terms), position++));
        }
        if (!term.isEmpty()) {
            boolean expanded = !selectedGroup.isEmpty();
            if (selectedGroup.isEmpty()) {
                boolean exactName = scoped.stream().anyMatch(tool ->
                        tool.callback().getToolDefinition().name().equalsIgnoreCase(term));
                if (exactName) {
                    scoped.removeIf(tool -> !tool.callback().getToolDefinition()
                            .name().equalsIgnoreCase(term));
                } else {
                    String dominantGroup = terms.size() > 1 ? dominantGroup(scoped) : null;
                    if (dominantGroup != null) {
                        scoped.removeIf(tool -> !dominantGroup.equals(group(tool.callback())));
                        expanded = true;
                    }
                }
            }
            if (!expanded) scoped.removeIf(tool -> tool.score() == 0);
            scoped.sort(Comparator.comparingInt(ScoredTool::score).reversed()
                    .thenComparingInt(ScoredTool::position));
        }
        return scoped.stream().map(tool -> {
            var definition = tool.callback().getToolDefinition();
            return new ToolCatalogPages.Entry(definition.name(), group(tool.callback()),
                    definition.description());
        }).toList();
    }

    private static String dominantGroup(List<ScoredTool> tools) {
        Map<String, Integer> best = new HashMap<>();
        for (ScoredTool tool : tools) {
            if (tool.score() > 0) {
                best.merge(group(tool.callback()), tool.score(), Math::max);
            }
        }
        if (best.isEmpty()) return null;
        int maximum = best.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> winners = best.entrySet().stream()
                .filter(entry -> entry.getValue() == maximum)
                .map(Map.Entry::getKey).toList();
        return winners.size() == 1 ? winners.getFirst() : null;
    }

    private static int relevance(ToolCallback callback, String query, List<String> terms) {
        if (query.isEmpty()) return 0;
        var definition = callback.getToolDefinition();
        String name = definition.name().toLowerCase(Locale.ROOT);
        String group = group(callback).toLowerCase(Locale.ROOT);
        String description = definition.description().toLowerCase(Locale.ROOT);
        int score = name.equals(query) ? 1_000 : name.contains(query) ? 500
                : group.equals(query) ? 400 : description.contains(query) ? 300 : 0;
        for (String term : terms) {
            if (term.isEmpty()) continue;
            if (name.contains(term)) score += 80;
            else if (group.contains(term)) score += 60;
            else if (description.contains(term)) score += 20;
        }
        return score;
    }

    static String fingerprint(ToolCallback callback) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            var definition = callback.getToolDefinition();
            updateFingerprint(digest, definition.name());
            updateFingerprint(digest, group(callback));
            updateFingerprint(digest, definition.description());
            updateFingerprint(digest, definition.inputSchema());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void updateFingerprint(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (bytes.length >>> shift));
        digest.update(bytes);
    }

    static ObjectNode activateSelection(JsonNode arguments, Map<String, ToolCallback> authorized,
            int maxTools, int maxSchemaCharacters, int maxResultCharacters) {
        JsonNode values = arguments.path("names");
        if (!values.isArray() || values.isEmpty()) {
            return error("activate", "names must be a nonempty array", maxResultCharacters);
        }
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || !names.add(value.asText())) {
                return error("activate", "names must contain unique tool names", maxResultCharacters);
            }
            if (!authorized.containsKey(value.asText())) {
                ObjectNode failure = error("activate",
                        "unknown or unauthorized tool name: " + value.asText(), maxResultCharacters);
                putIfFits(failure, "hint", "Use action=list to inspect exact names authorized for this Run",
                        maxResultCharacters);
                return failure;
            }
        }
        if (names.size() > maxTools) {
            ObjectNode failure = error("activate",
                    "activation exceeds tool count budget: " + names.size() + " > " + maxTools,
                    maxResultCharacters);
            putIfFits(failure, "requestedTools", names.size(), maxResultCharacters);
            putIfFits(failure, "maxTools", maxTools, maxResultCharacters);
            return failure;
        }
        int characters = 0;
        for (String name : names) {
            int schema = SpringAiToolCatalog.schemaCharacters(authorized.get(name));
            if (schema > maxSchemaCharacters) {
                ObjectNode failure = error("activate", "tool schema exceeds model context policy: " + name,
                        maxResultCharacters);
                putIfFits(failure, "requestedSchemaCharacters", schema, maxResultCharacters);
                putIfFits(failure, "maxSchemaCharacters", maxSchemaCharacters, maxResultCharacters);
                return failure;
            }
            characters += schema;
        }
        if (characters > maxSchemaCharacters) {
            ObjectNode failure = error("activate", "activated tool schemas exceed model context policy",
                    maxResultCharacters);
            putIfFits(failure, "requestedSchemaCharacters", characters, maxResultCharacters);
            putIfFits(failure, "maxSchemaCharacters", maxSchemaCharacters, maxResultCharacters);
            return failure;
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("action", "activate");
        result.put("success", true);
        ArrayNode activated = result.putArray("activated");
        names.forEach(activated::add);
        result.put("note", "These tools are available in the next model call.");
        if (result.toString().length() > maxResultCharacters) {
            return error("activate", "activation result exceeds the tool result character budget",
                    maxResultCharacters);
        }
        return result;
    }

    static String group(ToolCallback callback) {
        return callback instanceof SpringAiToolCatalog.GroupedCallback grouped ? grouped.group() : "";
    }

    static ObjectNode invalidGroupResult(List<ToolCallback> allowed, String group, int maxCharacters) {
        ObjectNode failure = error("list", "unknown or unauthorized group: " + group,
                maxCharacters);
        ArrayNode available = failure.putArray("availableGroups");
        for (ToolGroupSummary summary : groupDirectory(allowed)) {
            available.add(summary.name());
            if (failure.toString().length() > maxCharacters) {
                available.remove(available.size() - 1);
                break;
            }
        }
        if (available.isEmpty()) failure.remove("availableGroups");
        return failure;
    }

    static ObjectNode error(String action, String message, int maxCharacters) {
        ObjectNode failure = JsonNodeFactory.instance.objectNode();
        if (!action.isEmpty()) failure.put("action", action);
        failure.put("success", false);
        failure.put("error", message);
        if (failure.toString().length() > maxCharacters) {
            int lower = 0;
            int upper = Math.min(message.length(), maxCharacters);
            while (lower < upper) {
                int middle = (lower + upper + 1) / 2;
                failure.put("error", message.substring(0, middle));
                if (failure.toString().length() <= maxCharacters) lower = middle;
                else upper = middle - 1;
            }
            failure.put("error", message.substring(0, lower));
        }
        if (failure.toString().length() > maxCharacters) {
            throw new IllegalArgumentException("result budget is too small for a catalog error");
        }
        return failure;
    }

    static void putIfFits(ObjectNode result, String key, String value, int maxCharacters) {
        result.put(key, value);
        if (result.toString().length() > maxCharacters) result.remove(key);
    }

    private static void putIfFits(ObjectNode result, String key, int value, int maxCharacters) {
        result.put(key, value);
        if (result.toString().length() > maxCharacters) result.remove(key);
    }
    private record ScoredTool(ToolCallback callback, int score, int position) { }
}
