package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.StepContextPolicy;
import com.javaclaw.framework.core.ToolRecoveryRequiredException;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.ToolDescriptor;
import com.javaclaw.framework.spi.ToolExecutionContext;
import com.javaclaw.framework.spi.ToolPolicyDecision;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Run-scoped, durable discovery and activation of the tools already allowed by a plan. */
final class ToolCatalogSession implements FrameworkTool {
    static final String NAME = "framework_tool_catalog";

    private final ReasoningRequest request;
    private final RunStore runs;
    private final StepContextPolicy policy;
    private final ToolDescriptor descriptor;
    private final List<String> authorizedNames;
    private List<ToolCallback> callbacks = List.of();
    private Map<String, ToolCallback> authorized = Map.of();
    private ToolCallback catalogCallback;
    private boolean directFit;
    private Boolean catalogAllowed;
    private boolean businessToolCompleted;
    private long lastEventSequence;
    private List<String> activeNames = List.of();
    private final Set<String> activeModelSteps = new LinkedHashSet<>();
    private final Map<String, StartedTool> startedTools = new HashMap<>();

    ToolCatalogSession(ReasoningRequest request, RunStore runs, ObjectMapper json,
                       StepContextPolicy policy, List<FrameworkTool> runTools) {
        this.request = Objects.requireNonNull(request, "request");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.policy = Objects.requireNonNull(policy, "policy");
        if (runTools.isEmpty()) throw new IllegalArgumentException("catalog requires run tools");
        if (runTools.stream().anyMatch(tool -> NAME.equals(tool.descriptor().name()))) {
            throw new IllegalStateException("reserved tool name is already used: " + NAME);
        }
        List<FrameworkTool> allowedTools = runTools.stream()
                .filter(this::allowedByToolPolicy).toList();
        authorizedNames = allowedTools.stream().map(tool -> tool.descriptor().name()).toList();
        try {
            JsonNode schema = json.readTree("""
                    {"type":"object","properties":{
                      "action":{"type":"string","enum":["list","activate"]},
                      "query":{"type":"string"},"group":{"type":"string"},
                      "page":{"type":"integer","minimum":1},
                      "names":{"type":"array","minItems":1,"items":{"type":"string"}}
                    },"required":["action"],"additionalProperties":false}
                    """);
            // Use a group with an allowed tool so group-scoped policies can admit discovery.
            String group = (allowedTools.isEmpty() ? runTools : allowedTools)
                    .getFirst().descriptor().group();
            descriptor = new ToolDescriptor(NAME,
                    "List authorized tools by page and activate tool names for the next model call. "
                            + "Use action=list with optional query, group and page; use action=activate with names.",
                    schema, group, PermissionSet.NONE, true);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot construct tool catalog schema", failure);
        }
    }

    private boolean allowedByToolPolicy(FrameworkTool tool) {
        for (var toolPolicy : request.plan().toolPolicies()) {
            ToolPolicyDecision decision = Objects.requireNonNull(toolPolicy.evaluate(
                    tool.descriptor(), request.plan().descriptor().toolPolicy(),
                    request.runRequest()), "tool policy decision");
            if (decision == ToolPolicyDecision.DENY) {
                return false;
            }
        }
        return true;
    }

    void bindCallbacks(List<ToolCallback> allCallbacks) {
        this.callbacks = List.copyOf(allCallbacks);
        Map<String, ToolCallback> allowed = new LinkedHashMap<>();
        for (ToolCallback callback : allCallbacks) {
            String name = callback.getToolDefinition().name();
            if (name.equals(NAME)) catalogCallback = callback;
            else if (authorizedNames.contains(name)) allowed.put(name, callback);
        }
        if (catalogCallback == null || allowed.size() != authorizedNames.size()) {
            throw new IllegalStateException("tool catalog callbacks do not match run tools");
        }
        authorized = Map.copyOf(allowed);
        int directCharacters = authorizedNames.stream().map(authorized::get)
                .mapToInt(SpringAiToolCatalog::schemaCharacters).sum();
        directFit = authorizedNames.size() <= policy.maxTools()
                && directCharacters <= policy.maxToolSchemaCharacters();
        if (!directFit) {
            catalogAllowed = allowedByToolPolicy(this);
            boolean plannedPerStep = request.plan().descriptor().onDemandContextPolicy() != null;
            if (!plannedPerStep && !catalogAllowed) {
                throw new IllegalStateException("tool catalog is denied by execution-plan policy; "
                        + "allow " + NAME + " or increase the context tool limits");
            }
            if (!plannedPerStep && SpringAiToolCatalog.schemaCharacters(catalogCallback)
                    > policy.maxToolSchemaCharacters()) {
                throw new IllegalStateException("tool catalog schema exceeds model context policy");
            }
        }
    }

    @Override public ToolDescriptor descriptor() { return descriptor; }

    @Override
    public JsonNode execute(JsonNode arguments, ToolExecutionContext context) {
        return switch (arguments.path("action").asText()) {
            case "list" -> list(arguments);
            case "activate" -> activate(arguments);
            default -> error("action must be list or activate");
        };
    }

    private JsonNode list(JsonNode arguments) {
        int page = arguments.path("page").asInt(1);
        String query = arguments.path("query").asText("").trim().toLowerCase(java.util.Locale.ROOT);
        String group = arguments.path("group").asText("").trim();
        if (page < 1 || query.length() > 128 || group.length() > 128) {
            return error("page must be positive and query/group at most 128 characters");
        }
        List<ToolCallback> matches = authorizedNames.stream().map(authorized::get)
                .filter(callback -> callback != null)
                .filter(callback -> group.isEmpty() || group.equals(group(callback)))
                .filter(callback -> query.isEmpty()
                        || callback.getToolDefinition().name().toLowerCase(java.util.Locale.ROOT).contains(query)
                        || callback.getToolDefinition().description()
                                .toLowerCase(java.util.Locale.ROOT).contains(query))
                .toList();
        List<ToolCatalogPages.Entry> entries = matches.stream().map(callback ->
                new ToolCatalogPages.Entry(callback.getToolDefinition().name(), group(callback),
                        callback.getToolDefinition().description())).toList();
        return ToolCatalogPages.list(entries, page, policy.maxToolResultCharacters());
    }

    private JsonNode activate(JsonNode arguments) {
        JsonNode values = arguments.path("names");
        if (!values.isArray() || values.isEmpty()) return error("names must be a nonempty array");
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || !names.add(value.asText())) {
                return error("names must contain unique tool names");
            }
            if (!authorized.containsKey(value.asText())) {
                return error("unknown or unauthorized tool name: " + value.asText());
            }
        }
        int capacity = policy.maxTools();
        if (request.plan().descriptor().onDemandContextPolicy() != null) {
            capacity = Math.min(capacity,
                    request.plan().descriptor().onDemandContextPolicy().selectedTools());
        }
        if (names.size() > capacity) {
            return error("activation exceeds tool count budget: " + names.size() + " > " + capacity);
        }
        int characters = 0;
        for (String name : names) {
            int schema = SpringAiToolCatalog.schemaCharacters(authorized.get(name));
            if (schema > policy.maxToolSchemaCharacters()) {
                return error("tool schema exceeds model context policy: " + name);
            }
            characters += schema;
        }
        if (characters > policy.maxToolSchemaCharacters()) {
            return error("activated tool schemas exceed model context policy");
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("action", "activate");
        result.put("success", true);
        ArrayNode activated = result.putArray("activated");
        names.forEach(activated::add);
        result.put("note", "These tools are available in the next model call.");
        if (result.toString().length() > policy.maxToolResultCharacters()) {
            return error("activation result exceeds the tool result character budget");
        }
        return result;
    }

    /** Rebuilds activation solely from durable tool events, so restart needs no new store. */
    private synchronized void refresh() {
        for (var event : runs.eventsAfter(request.runId(), lastEventSequence)) {
            lastEventSequence = event.sequence();
            String type = event.type();
            JsonNode payload = event.payload();
            String stepId = payload.path("stepId").asText();
            if (type.equals("core.step.started")) {
                String kind = payload.path("kind").asText();
                if (kind.equals("MODEL") && !activeNames.isEmpty()) {
                    activeModelSteps.add(stepId);
                } else if (kind.equals("TOOL")) {
                    JsonNode input = payload.path("input");
                    startedTools.put(stepId, new StartedTool(input.path("tool").asText(),
                            input.path("arguments").path("action").asText()));
                }
                continue;
            }
            if (type.equals("core.step.completed")) {
                if (activeModelSteps.remove(stepId)) {
                    activeNames = List.of();
                    activeModelSteps.clear();
                }
                StartedTool started = startedTools.remove(stepId);
                if (started != null) {
                    if (started.name().equals(NAME) && started.action().equals("activate")
                            && payload.path("credentialRedacted").asBoolean(false)) {
                        throw new ToolRecoveryRequiredException(stepId,
                                "persisted tool catalog activation was redacted: " + stepId);
                    }
                    JsonNode output = payload.path("output");
                    try {
                        applyToolCompletion(started.name(), output.path("rawOutput"),
                                output.path("waitingInput").asBoolean(false));
                    } catch (IllegalStateException invalid) {
                        throw new ToolRecoveryRequiredException(stepId,
                                "persisted tool completion cannot be restored: "
                                        + invalid.getMessage());
                    }
                }
                continue;
            }
            if (type.equals("core.step.failed")) {
                activeModelSteps.remove(stepId);
                startedTools.remove(stepId);
                continue;
            }
        }
    }

    private void applyToolCompletion(String name, JsonNode output, boolean waitingInput) {
        if (waitingInput || name.isBlank()) return;
        if (name.equals(NAME)) {
            if (!output.path("action").asText().equals("activate")
                    || !output.path("success").asBoolean(false)) return;
            JsonNode activated = output.path("activated");
            if (!activated.isArray() || activated.isEmpty()) {
                throw new IllegalStateException("persisted tool catalog activation has no tool names");
            }
            List<String> names = new ArrayList<>();
            Set<String> unique = new HashSet<>();
            for (JsonNode value : activated) {
                String selected = value.asText("");
                if (selected.isBlank() || !unique.add(selected)) {
                    throw new IllegalStateException("persisted tool catalog activation is invalid");
                }
                if (!authorized.containsKey(selected)) {
                    throw new IllegalStateException("persisted activation names a tool no longer authorized: "
                            + selected);
                }
                names.add(selected);
            }
            activeNames = List.copyOf(names);
            activeModelSteps.clear();
        } else if (!name.startsWith("framework_context_")) {
            businessToolCompleted = true;
        }
    }

    synchronized ToolCatalogProjection project(List<Message> messages) {
        refresh();
        if (callbacks.isEmpty()) throw new IllegalStateException("tool catalog callbacks are not bound");
        List<ToolCallback> direct = authorizedNames.stream().map(authorized::get).toList();
        int remaining = request.control().remainingToolCalls();
        if (directFit) {
            if (remaining == 0) return new ToolCatalogProjection(List.of(), 0, direct.size());
            return new ToolCatalogProjection(direct,
                    direct.stream().mapToInt(SpringAiToolCatalog::schemaCharacters).sum(), direct.size());
        }
        if (remaining == 0) {
            if (businessToolCompleted) return new ToolCatalogProjection(List.of(), 0, authorizedNames.size() + 1);
            throw insufficientToolBudget();
        }
        if (activeNames.isEmpty() && remaining < 2) {
            if (businessToolCompleted) {
                // A target result can still be used for a final answer, but another
                // catalog activation and target call cannot fit in the Run budget.
                return new ToolCatalogProjection(List.of(), 0, authorizedNames.size() + 1);
            }
            throw insufficientToolBudget();
        }
        List<ToolCallback> selected = new ArrayList<>();
        int characters = 0;
        int activeCharacters = activeNames.stream().map(authorized::get)
                .filter(Objects::nonNull).mapToInt(SpringAiToolCatalog::schemaCharacters).sum();
        boolean catalogVisible = remaining > 1
                && (activeNames.isEmpty() || (activeNames.size() < policy.maxTools()
                && activeCharacters + SpringAiToolCatalog.schemaCharacters(catalogCallback)
                    <= policy.maxToolSchemaCharacters()));
        if (catalogVisible) {
            selected.add(catalogCallback);
            characters += SpringAiToolCatalog.schemaCharacters(catalogCallback);
        }
        for (String name : activeNames) {
            ToolCallback callback = authorized.get(name);
            if (callback == null) throw new IllegalStateException("activated tool is no longer authorized: " + name);
            characters = addRequired(selected, characters, callback);
        }
        if (catalogVisible) {
            Set<String> preferredGroups = SpringAiToolCatalog.preferredGroups(request, messages, callbacks);
            List<ToolCallback> optional = authorizedNames.stream().map(authorized::get)
                    .filter(Objects::nonNull)
                    .filter(callback -> !selected.contains(callback))
                    .sorted(Comparator.comparingInt(callback ->
                            SpringAiToolCatalog.priority(callback, preferredGroups)))
                    .toList();
            for (ToolCallback callback : optional) {
                if (selected.size() >= policy.maxTools()) break;
                int size = SpringAiToolCatalog.schemaCharacters(callback);
                if (characters + size > policy.maxToolSchemaCharacters()) continue;
                selected.add(callback);
                characters += size;
            }
        }
        return new ToolCatalogProjection(selected, characters, authorizedNames.size() + 1);
    }

    /** The on-demand planner chooses business tools and discovery mode. */
    synchronized ToolCatalogProjection projectPlanned(List<String> names, int maxSelectedTools,
            CatalogMode mode) {
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(mode, "mode");
        refresh();
        if (callbacks.isEmpty()) throw new IllegalStateException("tool catalog callbacks are not bound");
        if (catalogAllowed == null) catalogAllowed = allowedByToolPolicy(this);
        if (mode == CatalogMode.REQUIRED && authorizedNames.isEmpty()) {
            throw new IllegalStateException("tool discovery was requested but the Run has no authorized tools");
        }
        Set<String> unique = new LinkedHashSet<>(activeNames);
        if (names.stream().distinct().count() != names.size()) {
            throw new IllegalStateException("duplicate planned tool name");
        }
        unique.addAll(names);
        if (unique.size() > maxSelectedTools) {
            throw new IllegalStateException("planned and activated tools exceed on-demand selection limit");
        }
        int remaining = request.control().remainingToolCalls();
        if (remaining == 0) {
            if (!unique.isEmpty() || mode == CatalogMode.REQUIRED) throw insufficientToolBudget();
            return new ToolCatalogProjection(List.of(), 0, authorizedNames.size());
        }
        List<ToolCallback> selected = new ArrayList<>();
        int characters = 0;
        for (String name : unique) {
            ToolCallback callback = authorized.get(name);
            if (callback == null) throw new IllegalStateException("planned tool is not authorized: " + name);
            characters = addRequired(selected, characters, callback);
        }
        boolean hidden = authorizedNames.size() > unique.size();
        if (mode != CatalogMode.NONE && hidden && catalogAllowed && remaining >= 2
                && selected.size() < policy.maxTools() && catalogCallback != null
                && characters + SpringAiToolCatalog.schemaCharacters(catalogCallback)
                    <= policy.maxToolSchemaCharacters()) {
            selected.add(catalogCallback);
            characters += SpringAiToolCatalog.schemaCharacters(catalogCallback);
        } else if (mode == CatalogMode.REQUIRED) {
            if (!catalogAllowed) {
                throw new IllegalStateException("tool catalog is denied by execution-plan policy");
            }
            if (remaining < 2) throw insufficientToolBudget();
            throw new IllegalStateException(hidden
                    ? "tool catalog cannot fit the provider tool context limits"
                    : "tool discovery was requested but no hidden authorized tools remain");
        }
        return new ToolCatalogProjection(List.copyOf(selected), characters,
                authorizedNames.size() + (catalogAllowed && hidden ? 1 : 0));
    }

    synchronized List<ToolSummary> summaries() {
        if (callbacks.isEmpty()) throw new IllegalStateException("tool catalog callbacks are not bound");
        return authorizedNames.stream().map(authorized::get).filter(Objects::nonNull)
                .map(callback -> new ToolSummary(callback.getToolDefinition().name(),
                        group(callback), callback.getToolDefinition().description()))
                .toList();
    }

    /** Metadata only: candidates come from the Run-authorized callbacks, without a tool call. */
    synchronized List<ToolGroupSummary> groups() {
        return groupDirectory(authorizedCallbacks());
    }

    /** Searches only the Run-authorized business tools; no external read or budget charge. */
    synchronized List<ToolCandidate> searchAuthorized(
            String query, List<String> groups, int limit) {
        return searchDirectory(authorizedCallbacks(), query, groups, limit);
    }

    /** Rechecks a persisted candidate against the current Run authorization and definition. */
    synchronized boolean matchesCandidate(String name, String fingerprint) {
        ToolCallback callback = authorized.get(name);
        return callback != null && fingerprint(callback).equals(fingerprint);
    }

    /** Recheck both the current definition and catalog policy when restoring a provider step. */
    synchronized boolean matchesProviderDefinition(String name, String fingerprint) {
        ToolCallback callback;
        if (NAME.equals(name)) {
            if (!allowedByToolPolicy(this)) return false;
            callback = catalogCallback;
        } else {
            callback = authorized.get(name);
        }
        return callback != null && fingerprint(callback).equals(fingerprint);
    }

    private List<ToolCallback> authorizedCallbacks() {
        if (callbacks.isEmpty()) throw new IllegalStateException("tool catalog callbacks are not bound");
        return authorizedNames.stream().map(authorized::get).filter(Objects::nonNull).toList();
    }

    static List<ToolGroupSummary> groupDirectory(List<ToolCallback> allowed) {
        Map<String, Integer> counts = new TreeMap<>();
        for (ToolCallback callback : allowed) {
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
        // Give every explicitly selected group one candidate before tools from
        // a larger or higher-scoring group fill the whole candidate limit.
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

    synchronized List<String> activeNames() {
        refresh();
        return activeNames;
    }

    synchronized List<ToolCallback> restoreProviderTools(List<String> names) {
        List<ToolCallback> restored = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (String name : names) {
            if (!unique.add(name)) throw new IllegalStateException("duplicate persisted provider tool");
            if (NAME.equals(name) && !allowedByToolPolicy(this)) {
                throw new IllegalStateException("persisted tool catalog is no longer authorized");
            }
            ToolCallback callback = NAME.equals(name) ? catalogCallback : authorized.get(name);
            if (callback == null) throw new IllegalStateException(
                    "persisted provider tool is no longer authorized: " + name);
            restored.add(callback);
        }
        return List.copyOf(restored);
    }

    record ToolSummary(String name, String group, String description) { }
    record ToolGroupSummary(String name, int count) { }
    record ToolCandidate(String name, String group, String description,
            String fingerprint, int schemaCharacters) { }
    enum CatalogMode { NONE, OPTIONAL, REQUIRED }
    private record StartedTool(String name, String action) { }
    private record ScoredTool(ToolCallback callback, int score, int position) { }

    private IllegalStateException insufficientToolBudget() {
        return new IllegalStateException("tool-call budget is insufficient for catalog activation "
                + "and one target tool call; at least two calls must remain");
    }

    private int addRequired(List<ToolCallback> selected, int characters, ToolCallback callback) {
        int next = SpringAiToolCatalog.schemaCharacters(callback);
        if (selected.size() >= policy.maxTools()
                || characters + next > policy.maxToolSchemaCharacters()) {
            throw new IllegalStateException("activated tool catalog exceeds model context policy: "
                    + callback.getToolDefinition().name());
        }
        selected.add(callback);
        return characters + next;
    }

    void validateActual(List<ToolCallback> actual, List<Message> messages) {
        List<ToolCallback> expected = project(messages).callbacks();
        if (!actual.equals(expected)) {
            throw new IllegalStateException("provider tool catalog differs from the authorized projection");
        }
        int characters = actual.stream().mapToInt(SpringAiToolCatalog::schemaCharacters).sum();
        if (actual.size() > policy.maxTools()
                || characters > policy.maxToolSchemaCharacters()) {
            throw new IllegalStateException("provider tool catalog exceeds model context policy");
        }
    }

    private static String group(ToolCallback callback) {
        return callback instanceof SpringAiToolCatalog.GroupedCallback grouped ? grouped.group() : "";
    }

    private static ObjectNode error(String message) {
        return JsonNodeFactory.instance.objectNode().put("success", false).put("error", message);
    }
}
