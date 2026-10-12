package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.core.OnDemandContextPolicy;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.core.StepEvents;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.util.SensitiveDataRedactor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves tool names exclusively from a persisted, Run-authorized candidate directory. */
final class OnDemandToolSelection {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private final ReasoningRequest request;
    private final OnDemandContextPolicy policy;
    private final ToolCatalogSession catalog;
    private final RunStore runs;
    private final RunStepQuery steps;
    private final PlanningStage stage;
    private boolean repaired;

    OnDemandToolSelection(ReasoningRequest request, OnDemandContextPolicy policy,
            ToolCatalogSession catalog, RunStore runs, RunStepQuery steps, PlanningStage stage) {
        this.request = Objects.requireNonNull(request);
        this.policy = Objects.requireNonNull(policy);
        this.catalog = catalog;
        this.runs = Objects.requireNonNull(runs);
        this.steps = Objects.requireNonNull(steps);
        this.stage = Objects.requireNonNull(stage);
    }

    Intent intent(JsonNode first, JsonNode firstInput, String key) {
        Set<String> visible = new HashSet<>();
        firstInput.path("toolGroups").forEach(group -> visible.add(group.path("name").asText()));
        JsonNode value = first.path("toolIntent");
        if (!validIntent(value, visible)) {
            ObjectNode input = NODES.objectNode();
            input.put("instruction", "Correct the tool intent. Choose group names only from allowedGroups. "
                    + "A tool name is never a group name. Return an empty intent if no tool is needed.");
            input.set("original", value.deepCopy());
            input.set("task", firstInput.path("task").deepCopy());
            ArrayNode allowed = input.putArray("allowedGroups");
            visible.stream().sorted().forEach(allowed::add);
            while (input.toString().length() > policy.plannerInputChars()
                    && !allowed.isEmpty()) allowed.remove(allowed.size() - 1);
            value = repair("repair_select_tools_v2", key, input, intentSchema(allowed));
            value = value.path("toolIntent");
        }
        if (!validIntent(value, visible)) {
            throw pause("planner selected a tool group absent from the authorized Run catalog");
        }
        List<String> groups = new ArrayList<>();
        value.path("groups").forEach(group -> groups.add(group.asText()));
        return new Intent(value.path("query").asText().strip(), List.copyOf(groups));
    }

    Snapshot retrieve(String key, Intent intent, int limit) {
        if (!intent.requested()) return new Snapshot(List.of(), null);
        if (catalog == null || catalog.summaries().isEmpty()) {
            throw pause("planner requested tools but the Run has no authorized tool catalog");
        }
        ObjectNode input = NODES.objectNode().put("phase", "tool_search_v2")
                .put("key", key).put("query", intent.query()).put("limit", limit);
        ArrayNode groups = input.putArray("groups");
        intent.groups().forEach(groups::add);
        StepId id = StepId.tool(request.runId(), "context/tools/" + key);
        var old = steps.step(request.runId(), id);
        if (old.isPresent()) {
            AgentStep step = old.get();
            if (step.kind() != AgentStep.Kind.ORCHESTRATION || !input.equals(step.input())) {
                throw pause("persisted tool retrieval input changed before replay: " + id.value());
            }
            if (step.state() == AgentStep.State.COMPLETED) {
                if (outputRedacted(id)) {
                    throw pause("persisted tool candidate catalog was redacted: " + id.value());
                }
                return snapshot(step.output(), id);
            }
            if (step.state() != AgentStep.State.RUNNING) {
                throw pause("tool retrieval outcome is unknown; reconcile step " + id.value());
            }
        } else {
            StepEvents.started(request.events(), id, AgentStep.Kind.ORCHESTRATION, input, null);
        }
        ObjectNode output = NODES.objectNode();
        ArrayNode values = output.putArray("candidates");
        if (catalog != null) {
            int index = 0;
            for (var candidate : catalog.searchAuthorized(intent.query(), intent.groups(), limit)) {
                // StepEvents redacts text at settlement; build the same safe summary up front.
                values.addObject().put("id", "t" + index++)
                        .put("name", candidate.name()).put("group", candidate.group())
                        .put("summary", SensitiveDataRedactor.redactText(
                                excerpt(candidate.description(), 160)))
                        .put("fingerprint", candidate.fingerprint());
            }
        }
        StepEvents.completed(request.events(), id, output, null);
        AgentStep completed = steps.step(request.runId(), id).orElseThrow();
        if (outputRedacted(id) || !output.equals(completed.output())) {
            throw pause("persisted tool candidate catalog was redacted or changed: " + id.value());
        }
        return snapshot(completed.output(), id);
    }

    /** Freeze an authorized rejected batch for the next provider step without replaying it. */
    Snapshot retrieveExact(String key, List<String> names) {
        if (catalog == null) {
            throw pause("cannot repair unavailable tools: this Run has no authorized tool catalog");
        }
        if (names.isEmpty() || names.stream().distinct().count() != names.size()) {
            throw pause("cannot repair unavailable tools: the requested names are empty or duplicated");
        }
        if (names.size() > policy.candidates()) {
            throw pause("unavailable-tool repair needs " + names.size()
                    + " candidate slots, above the limit of " + policy.candidates());
        }
        ObjectNode input = NODES.objectNode().put("phase", "tool_search_v2")
                .put("key", key).put("query", "exact rejected tool names")
                .put("limit", names.size());
        ArrayNode requested = input.putArray("names");
        names.forEach(requested::add);
        StepId id = StepId.tool(request.runId(),
                "context/tools/" + key + "/repair/" + requested);
        var old = steps.step(request.runId(), id);
        if (old.isPresent()) {
            AgentStep step = old.get();
            if (step.kind() != AgentStep.Kind.ORCHESTRATION || !input.equals(step.input())
                    || step.state() != AgentStep.State.COMPLETED || outputRedacted(id)) {
                throw pause("persisted unavailable-tool repair catalog changed: " + id.value());
            }
            return snapshot(step.output(), id);
        }
        ObjectNode output = NODES.objectNode();
        ArrayNode values = output.putArray("candidates");
        for (String name : names) {
            var found = catalog.searchAuthorized(name, List.of(), Integer.MAX_VALUE).stream()
                    .filter(candidate -> candidate.name().equals(name)).findFirst();
            if (found.isEmpty()) {
                throw pause("requested tool is not authorized for this Run: " + name);
            }
            var candidate = found.get();
            int index = values.size();
            values.addObject().put("id", "t" + index)
                    .put("name", candidate.name()).put("group", candidate.group())
                    .put("summary", SensitiveDataRedactor.redactText(
                            excerpt(candidate.description(), 160)))
                    .put("fingerprint", candidate.fingerprint());
        }
        StepEvents.started(request.events(), id, AgentStep.Kind.ORCHESTRATION, input, null);
        StepEvents.completed(request.events(), id, output, null);
        AgentStep completed = steps.step(request.runId(), id).orElseThrow();
        if (outputRedacted(id) || !output.equals(completed.output())) {
            throw pause("persisted unavailable-tool repair catalog was redacted or changed: "
                    + id.value());
        }
        return snapshot(completed.output(), id);
    }

    /** Keep a grounded desktop action when its companion observe schema will not fit. */
    ObservedActionFit fitObservedDesktopAction(String key, List<String> names,
            List<String> activated, ToolCatalogSession.CatalogMode mode, Snapshot snapshot) {
        if (!names.contains("desktop_session_observe")
                || activated.contains("desktop_session_observe")) {
            return new ObservedActionFit(names, snapshot);
        }
        try {
            catalog.projectPlanned(names, policy.selectedTools(), mode);
            return new ObservedActionFit(names, snapshot);
        } catch (IllegalStateException invalid) {
            if (!(invalid instanceof ToolSchemaBudgetExceededException)) {
                throw OnDemandContextSession.pause("invalid or over-budget planned tool selection: "
                        + invalid.getMessage(), invalid);
            }
            // The existing frame grounds this action. The next provider step
            // observes its effect; no additional input is sent in this step.
            List<String> fitted = names.stream()
                    .filter(name -> !name.equals("desktop_session_observe")).toList();
            return new ObservedActionFit(fitted, fitted.isEmpty()
                    ? new Snapshot(List.of(), null) : retrieveExact(key, fitted));
        }
    }

    Choice choose(JsonNode decision, Intent intent, Snapshot snapshot,
            JsonNode refinementInput, String key) {
        verifySnapshot(snapshot);
        if (refinementInput == null) {
            return new Choice(List.of(), intent.requested()
                    ? ToolCatalogSession.CatalogMode.REQUIRED
                    : ToolCatalogSession.CatalogMode.NONE);
        }
        Set<String> visible = new HashSet<>();
        refinementInput.path("toolCandidates").forEach(value ->
                visible.add(value.path("id").asText()));
        if (intent.requested() && visible.isEmpty()) {
            if (catalog == null) {
                throw pause("planner requested tool discovery but no authorized tool catalog exists");
            }
            return new Choice(List.of(), ToolCatalogSession.CatalogMode.REQUIRED);
        }
        JsonNode value = decision;
        if (!validChoice(value, visible)) {
            ObjectNode input = NODES.objectNode();
            input.put("instruction", "Correct the tool selection. Choose only candidate IDs from "
                    + "toolCandidates. Never return a tool name. Use direct with IDs, or discover/none "
                    + "with an empty ID list.");
            ObjectNode original = input.putObject("original");
            original.put("toolAction", excerpt(value.path("toolAction").asText(), 32));
            ArrayNode originalIds = original.putArray("toolIds");
            for (JsonNode id : value.path("toolIds")) {
                if (originalIds.size() >= policy.selectedTools()) break;
                originalIds.add(excerpt(id.asText(), 64));
            }
            ArrayNode candidates = (ArrayNode) refinementInput.path("toolCandidates").deepCopy();
            input.set("toolCandidates", candidates);
            input.set("toolIntent", refinementInput.path("firstSelection")
                    .path("toolIntent").deepCopy());
            while (input.toString().length() > policy.plannerInputChars()
                    && !candidates.isEmpty()) candidates.remove(candidates.size() - 1);
            Set<String> allowed = new HashSet<>();
            candidates.forEach(candidate -> allowed.add(candidate.path("id").asText()));
            value = repair("repair_refine_tools_v2", key, input, choiceSchema(allowed));
        }
        if (!validChoice(value, visible)) {
            throw pause("planner selected a tool candidate absent from the retrieved catalog");
        }
        String action = value.path("toolAction").asText();
        if (intent.requested() && snapshot.candidates().isEmpty()) {
            if (catalog == null) {
                throw pause("planner requested tool discovery but no authorized tool catalog exists");
            }
            return new Choice(List.of(), ToolCatalogSession.CatalogMode.REQUIRED);
        }
        if (action.equals("none")) {
            return new Choice(List.of(), ToolCatalogSession.CatalogMode.NONE);
        }
        if (action.equals("discover")) {
            return new Choice(List.of(), ToolCatalogSession.CatalogMode.REQUIRED);
        }
        Map<String, Candidate> byId = new HashMap<>();
        snapshot.candidates().forEach(candidate -> byId.put(candidate.id(), candidate));
        List<String> names = new ArrayList<>();
        value.path("toolIds").forEach(id -> {
            Candidate candidate = byId.get(id.asText());
            if (candidate == null) throw pause("retrieved tool candidate is unavailable: " + id.asText());
            names.add(candidate.name());
        });
        // A concrete candidate is ready for this provider step. Discovery is
        // selected explicitly, rather than competing with the chosen callback.
        return new Choice(List.copyOf(names), ToolCatalogSession.CatalogMode.NONE);
    }

    private void verifySnapshot(Snapshot snapshot) {
        for (Candidate candidate : snapshot.candidates()) {
            if (catalog == null || !catalog.matchesCandidate(
                    candidate.name(), candidate.fingerprint())) {
                throw pause("retrieved tool candidate is no longer authorized or has changed: "
                        + candidate.name());
            }
        }
    }

    private JsonNode repair(String phase, String key, JsonNode input, String schema) {
        if (repaired) throw pause("planner tool selection remained invalid after one correction");
        if (input.toString().length() > policy.plannerInputChars()) {
            throw pause("required tool correction input exceeds the planner character limit");
        }
        repaired = true;
        return stage.run(phase, key, input, schema);
    }

    private boolean validIntent(JsonNode value, Set<String> visibleGroups) {
        if (!value.isObject() || !value.path("query").isTextual()
                || value.path("query").asText().length() > 128
                || !value.path("groups").isArray()
                || value.path("groups").size() > policy.selectedTools()) return false;
        Set<String> names = new HashSet<>();
        for (JsonNode group : value.path("groups")) {
            if (!group.isTextual() || !names.add(group.asText())
                    || !visibleGroups.contains(group.asText())) return false;
        }
        return true;
    }

    private boolean validChoice(JsonNode value, Set<String> visibleIds) {
        if (!value.isObject() || !value.path("toolAction").isTextual()
                || !value.path("toolIds").isArray()) return false;
        String action = value.path("toolAction").asText();
        if (!Set.of("direct", "discover", "none").contains(action)) return false;
        JsonNode ids = value.path("toolIds");
        if (ids.size() > policy.selectedTools()) return false;
        if (!action.equals("direct") && !ids.isEmpty()) return false;
        if (action.equals("direct") && ids.isEmpty()) return false;
        Set<String> unique = new HashSet<>();
        for (JsonNode id : ids) {
            if (!id.isTextual() || !visibleIds.contains(id.asText())
                    || !unique.add(id.asText())) return false;
        }
        return true;
    }

    private Snapshot snapshot(JsonNode output, StepId id) {
        JsonNode values = output == null ? null : output.path("candidates");
        if (values == null || !values.isArray() || values.size() > policy.candidates()) {
            throw pause("persisted tool candidate catalog is unavailable: " + id.value());
        }
        List<Candidate> candidates = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonNode value : values) {
            String candidateId = value.path("id").asText();
            String name = value.path("name").asText();
            String group = value.path("group").asText();
            String fingerprint = value.path("fingerprint").asText();
            if (!candidateId.equals("t" + candidates.size()) || name.isBlank()
                    || group.isBlank() || fingerprint.isBlank() || !names.add(name)
                    || catalog == null || !catalog.matchesCandidate(name, fingerprint)) {
                throw pause("persisted tool candidate is no longer authorized or has changed: " + name);
            }
            candidates.add(new Candidate(candidateId, name, group,
                    value.path("summary").asText(), fingerprint));
        }
        return new Snapshot(List.copyOf(candidates), id.value());
    }

    private boolean outputRedacted(StepId id) {
        return runs.eventsAfter(request.runId(), 0).stream().anyMatch(event ->
                event.type().equals("core.step.completed")
                        && event.payload().path("stepId").asText().equals(id.value())
                        && event.payload().path("credentialRedacted").asBoolean(false));
    }

    private static String intentSchema(ArrayNode groups) {
        String items = groups.isEmpty() ? "{\"type\":\"string\"}"
                : "{\"type\":\"string\",\"enum\":" + groups + "}";
        return "{\"type\":\"object\",\"properties\":{\"toolIntent\":{\"type\":\"object\","
                + "\"properties\":{\"query\":{\"type\":\"string\",\"maxLength\":128},"
                + "\"groups\":{\"type\":\"array\",\"uniqueItems\":true,"
                + (groups.isEmpty() ? "\"maxItems\":0," : "")
                + "\"items\":" + items + "}},"
                + "\"required\":[\"query\",\"groups\"],\"additionalProperties\":false}},"
                + "\"required\":[\"toolIntent\"],\"additionalProperties\":false}";
    }

    private static String choiceSchema(Set<String> visibleIds) {
        ArrayNode ids = NODES.arrayNode();
        visibleIds.stream().sorted().forEach(ids::add);
        String items = ids.isEmpty() ? "{\"type\":\"string\"}"
                : "{\"type\":\"string\",\"enum\":" + ids + "}";
        return "{\"type\":\"object\",\"properties\":{"
                + "\"toolAction\":{\"type\":\"string\",\"enum\":[\"direct\",\"discover\",\"none\"]},"
                + "\"toolIds\":{\"type\":\"array\",\"uniqueItems\":true,"
                + (ids.isEmpty() ? "\"maxItems\":0," : "")
                + "\"items\":" + items + "}},"
                + "\"required\":[\"toolAction\",\"toolIds\"],\"additionalProperties\":false}";
    }

    private static String excerpt(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    private static ContextPlanningRequiredException pause(String message) {
        return new ContextPlanningRequiredException(message);
    }

    @FunctionalInterface
    interface PlanningStage {
        JsonNode run(String phase, String key, JsonNode input, String schema);
    }

    record Intent(String query, List<String> groups) {
        boolean requested() { return !query.isBlank() || !groups.isEmpty(); }
    }
    record Candidate(String id, String name, String group, String summary, String fingerprint) { }
    record Snapshot(List<Candidate> candidates, String stepId) { }
    record ObservedActionFit(List<String> names, Snapshot snapshot) { }
    record Choice(List<String> names, ToolCatalogSession.CatalogMode mode) { }
}
