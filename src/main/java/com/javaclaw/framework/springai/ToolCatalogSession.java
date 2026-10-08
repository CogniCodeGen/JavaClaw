package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.InteractionExecutionPolicy;
import com.javaclaw.framework.core.InteractionModeFreshness;
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
    private final List<FrameworkTool> authorizedTools;
    private List<ToolCallback> callbacks = List.of();
    private Map<String, ToolCallback> authorized = Map.of();
    private ToolCallback catalogCallback;
    private HarnessDecisionToolCallback decisionCallback;
    private boolean directFit;
    private Boolean catalogAllowed;
    private boolean businessToolCompleted;
    private long lastEventSequence;
    private List<String> activeNames = List.of();
    private final Map<String, Set<String>> activeModelSteps = new LinkedHashMap<>();
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
        authorizedTools = List.copyOf(allowedTools);
        authorizedNames = allowedTools.stream().map(tool -> tool.descriptor().name()).toList();
        try {
            JsonNode schema = json.readTree("""
                    {"type":"object","properties":{
                      "action":{"type":"string","enum":["list","activate"],"default":"list"},
                      "query":{"type":"string"},"group":{"type":"string"},
                      "page":{"type":"integer","minimum":1},
                      "names":{"type":"array","minItems":1,"items":{"type":"string"}}
                    },"additionalProperties":false}
                    """);
            // Use a group with an allowed tool so group-scoped policies can admit discovery.
            String group = (allowedTools.isEmpty() ? runTools : allowedTools)
                    .getFirst().descriptor().group();
            descriptor = new ToolDescriptor(NAME,
                    "List authorized tools by page and activate tool names for the next model call. "
                            + "For a read-only list, omit action or use action=list with optional query, group and page. "
                            + "Group must exactly match a listed authorized group (desktop tools use desktop-session). "
                            + "To activate names, explicitly use action=activate with exact listed names. "
                            + "Activation is atomic and limited by tool count and combined schema size.",
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
            else if (callback instanceof HarnessDecisionToolCallback trusted) {
                if (decisionCallback != null) throw new IllegalStateException("duplicate harness decision callback");
                decisionCallback = trusted;
            }
            else if (authorizedNames.contains(name)) allowed.put(name, callback);
        }
        if (catalogCallback == null || decisionCallback == null
                || allowed.size() != authorizedNames.size()) {
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
        String action = arguments.path("action").asText("");
        if (action.isEmpty()) {
            if (arguments.has("names")) {
                return error("action=activate is required when names are supplied");
            }
            action = "list";
        }
        return switch (action) {
            case "list" -> list(arguments);
            case "activate" -> activate(arguments);
            default -> error("action must be list or activate");
        };
    }

    private JsonNode list(JsonNode arguments) {
        int page = arguments.path("page").asInt(1);
        String query = arguments.path("query").asText("").trim();
        String group = arguments.path("group").asText("").trim();
        if (arguments.has("names")) {
            return error("list", "action=activate is required when names are supplied");
        }
        if (page < 1 || query.length() > 128 || group.length() > 128) {
            return error("list", "page must be positive and query/group at most 128 characters");
        }
        List<ToolCallback> allowed = authorizedCallbacks();
        if (!group.isEmpty() && groupDirectory(allowed).stream()
                .noneMatch(summary -> summary.name().equals(group))) {
            return invalidGroupResult(allowed, group, policy.maxToolResultCharacters());
        }
        return ToolCatalogPages.list(listDirectory(allowed, query, group),
                page, policy.maxToolResultCharacters());
    }

    private JsonNode activate(JsonNode arguments) {
        int selectedLimit = request.plan().descriptor().onDemandContextPolicy() == null
                ? policy.maxTools() : request.plan().descriptor().onDemandContextPolicy().selectedTools();
        int capacity = selectedBusinessToolLimit(selectedLimit);
        Map<String, ToolCallback> current = new LinkedHashMap<>();
        authorized.forEach((name, callback) -> { if (roleAllowed(name)) current.put(name, callback); });
        ObjectNode result = activateSelection(arguments, current, capacity,
                businessSchemaLimit(), policy.maxToolResultCharacters());
        if (InteractionExecutionPolicy.isInteraction(request.runRequest())
                && result.path("success").asBoolean(false)) {
            putIfFits(result, "note", "Activation remains pending until the tool is actually exposed in a frozen "
                    + "provider directory. Current host lifecycle and required observation take priority; "
                    + "tools deferred by capacity remain activated for a later step.", policy.maxToolResultCharacters());
        }
        return result;
    }

    /** Validate a complete activation before any durable completion can make it visible. */
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

    /** Rebuilds activation solely from durable tool events, so restart needs no new store. */
    private synchronized void refresh() {
        for (var event : runs.eventsAfter(request.runId(), lastEventSequence)) {
            long priorSequence = lastEventSequence;
            lastEventSequence = event.sequence();
            String type = event.type();
            JsonNode payload = event.payload();
            String stepId = payload.path("stepId").asText();
            if (type.equals("core.step.started")) {
                String kind = payload.path("kind").asText();
                if (kind.equals("MODEL") && !activeNames.isEmpty()) {
                    if (event.schemaVersion() != 1 || !event.producer().equals("framework.core")
                            || !event.runId().equals(request.runId().value())) {
                        lastEventSequence = priorSequence;
                        throw new ToolRecoveryRequiredException(stepId,
                                "catalog activation requires a trusted frozen provider directory");
                    }
                    try {
                        activeModelSteps.put(stepId, exposedActivationNames(payload.path("input"),
                                new LinkedHashSet<>(activeNames), stepId));
                    } catch (ToolRecoveryRequiredException invalid) {
                        lastEventSequence = priorSequence;
                        throw invalid;
                    }
                } else if (kind.equals("TOOL")) {
                    JsonNode input = payload.path("input");
                    startedTools.put(stepId, new StartedTool(input.path("tool").asText(),
                            input.path("arguments").path("action").asText(),
                            input.path("trustedContextRead").asBoolean(false)));
                }
                continue;
            }
            if (type.equals("core.step.completed")) {
                Set<String> consumed = activeModelSteps.get(stepId);
                if (consumed != null) {
                    if (event.schemaVersion() != 1 || !event.producer().equals("framework.core")
                            || !event.runId().equals(request.runId().value())) {
                        lastEventSequence = priorSequence;
                        throw new ToolRecoveryRequiredException(stepId,
                                "catalog activation requires a trusted provider completion");
                    }
                    activeModelSteps.remove(stepId);
                    activeNames = activeNames.stream().filter(name -> !consumed.contains(name)).toList();
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
                                output.path("waitingInput").asBoolean(false),
                                started.trustedContextRead());
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

    private void applyToolCompletion(String name, JsonNode output,
            boolean waitingInput, boolean trustedContextRead) {
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
        } else if (!trustedContextRead
                && !name.equals(HarnessDecisionToolCallback.NAME)) {
            businessToolCompleted = true;
        }
    }

    synchronized ToolCatalogProjection project(List<Message> messages) {
        refresh();
        if (callbacks.isEmpty()) throw new IllegalStateException("tool catalog callbacks are not bound");
        List<ToolCallback> direct = authorizedNames.stream().map(authorized::get).toList();
        int remaining = request.control().remainingToolCalls();
        if (directFit) {
            if (remaining == 0) return withControl(List.of(), 0, direct.size());
            return withControl(direct,
                    direct.stream().mapToInt(SpringAiToolCatalog::schemaCharacters).sum(), direct.size());
        }
        if (remaining == 0) {
            return withControl(List.of(), 0, authorizedNames.size() + 1);
        }
        if (activeNames.isEmpty() && remaining < 2) {
            // The model can still submit a structured blocked/needs-input decision.
            return withControl(List.of(), 0, authorizedNames.size() + 1);
        }
        List<ToolCallback> selected = new ArrayList<>();
        int characters = 0;
        int activeCharacters = activeNames.stream().map(authorized::get)
                .filter(Objects::nonNull).mapToInt(SpringAiToolCatalog::schemaCharacters).sum();
        boolean catalogVisible = remaining > 1
                && (activeNames.isEmpty() || (activeNames.size() < businessToolLimit()
                && activeCharacters + SpringAiToolCatalog.schemaCharacters(catalogCallback)
                    <= businessSchemaLimit()));
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
            Set<String> preferredGroups = SpringAiToolCatalog.preferredGroups(request, messages,
                    callbacks.stream().filter(callback ->
                            !(callback instanceof HarnessDecisionToolCallback)).toList());
            List<ToolCallback> optional = authorizedNames.stream().map(authorized::get)
                    .filter(Objects::nonNull)
                    .filter(callback -> !selected.contains(callback))
                    .sorted(Comparator.comparingInt(callback ->
                            SpringAiToolCatalog.priority(callback, preferredGroups)))
                    .toList();
            for (ToolCallback callback : optional) {
                if (selected.size() >= businessToolLimit()) break;
                int size = SpringAiToolCatalog.schemaCharacters(callback);
                if (characters + size > businessSchemaLimit()) continue;
                selected.add(callback);
                characters += size;
            }
        }
        return withControl(selected, characters, authorizedNames.size() + 1);
    }

    /** The on-demand planner chooses business tools and discovery mode. */
    synchronized ToolCatalogProjection projectPlanned(List<String> names, int maxSelectedTools,
            CatalogMode mode) {
        return projectPlanned(names, maxSelectedTools, mode, false);
    }

    /** 角色路由只投影本步精确接口，不恢复另一后端的历史激活。 */
    synchronized ToolCatalogProjection projectExactRole(List<String> names, int maxSelectedTools,
            CatalogMode mode) {
        refresh();
        List<ToolCallback> selected = new ArrayList<>();
        int characters = 0;
        if (request.control().remainingToolCalls() > 0) {
            for (String name : new LinkedHashSet<>(names)) {
                if (!roleAllowed(name)) throw new IllegalStateException("tool is outside the active role: " + name);
                if (roleControlNames().contains(name)) continue;
                if (selected.size() >= maxSelectedTools)
                    throw new ToolCountBudgetExceededException(selected.size() + 1, maxSelectedTools, name);
                ToolCallback callback = authorized.get(name);
                if (callback == null) throw new IllegalStateException("role tool is not authorized: " + name);
                characters = addRequired(selected, characters, callback);
            }
            if (mode != CatalogMode.NONE && catalogCallback != null && allowedByToolPolicy(this)
                    && request.control().remainingToolCalls() >= 2) {
                characters = addRequired(selected, characters, catalogCallback);
            }
        }
        return withControl(selected, characters, authorizedNames.size());
    }

    /** A required host stage defers launch activation until identities or uncertain delivery are observed. */
    synchronized ToolCatalogProjection projectComputerUseStage(List<String> names, int maxSelectedTools) {
        return projectComputerUseStage(names, maxSelectedTools, false);
    }

    /** Defers callbacks whose native session arguments cannot be live during recovery. */
    synchronized ToolCatalogProjection projectComputerUseStage(List<String> names, int maxSelectedTools,
            boolean recoveringSession) {
        return projectComputerUseStage(names, maxSelectedTools, recoveringSession, true);
    }

    /** Runtime capability masks affect provider projection only; activation remains journaled. */
    synchronized ToolCatalogProjection projectComputerUseStage(List<String> names, int maxSelectedTools,
            boolean recoveringSession, boolean inputAllowed) {
        return projectPlanned(names, maxSelectedTools, CatalogMode.NONE,
                !names.contains(OnDemandApplicationRecovery.LAUNCH), recoveringSession, !inputAllowed);
    }

    synchronized ToolCatalogProjection projectPlannedDesktop(List<String> names, int maxSelectedTools,
            CatalogMode mode, boolean inputAllowed) {
        return projectPlanned(names, maxSelectedTools, mode, false, false, !inputAllowed);
    }

    /** A frozen read masks other activations without granting or consuming their interfaces. */
    synchronized ToolCatalogProjection projectRequiredRead(String name, int maxSelectedTools) {
        refresh();
        if (maxSelectedTools < 1 || !trustedReadOnlyTool(name)) {
            throw new IllegalStateException("required frozen read is not currently authorized");
        }
        if (request.control().remainingToolCalls() == 0) {
            return withControl(List.of(), 0, authorizedNames.size());
        }
        List<ToolCallback> selected = new ArrayList<>();
        int characters = addRequired(selected, 0, authorized.get(name));
        return withControl(selected, characters, authorizedNames.size());
    }

    /** 下一冻结输入隐藏其它激活接口，仍由原门禁决定能否执行。 */
    synchronized ToolCatalogProjection projectRequiredInput(String name, int maxSelectedTools,
            ComputerUseSessionCursor cursor) {
        refresh();
        if (maxSelectedTools < 1 || !trustedDesktopInputTool(name) || cursor.requiresTool()
                || !cursor.inputAllowed() || !cursor.pendingInvocationIds().isEmpty()) {
            throw new IllegalStateException("required frozen input is not currently grounded and authorized");
        }
        if (request.control().remainingToolCalls() == 0) {
            return withControl(List.of(), 0, authorizedNames.size());
        }
        List<ToolCallback> selected = new ArrayList<>();
        int characters = addRequired(selected, 0, authorized.get(name));
        return withControl(selected, characters, authorizedNames.size());
    }

    /** READY schemas mask preparation activations without consuming their durable authorization. */
    synchronized ToolCatalogProjection projectReadyDesktop(List<String> names, int maxSelectedTools,
            ComputerUseSessionCursor cursor) {
        refresh();
        if (cursor.requiresTool() || !cursor.inputAllowed() || !cursor.pendingInvocationIds().isEmpty()
                || !request.control().pendingInteractionEffects().isEmpty())
            throw new IllegalStateException("desktop READY projection lacks a grounded input baseline");
        if (names.size() > desktopReadyToolLimit(maxSelectedTools)
                || names.stream().distinct().count() != names.size())
            throw new IllegalStateException("desktop READY schemas exceed the current selection limit");
        for (String name : names) {
            boolean observation = name.equals("desktop_session_observe") && trustedReadOnlyTool(name);
            if (!observation && !trustedDesktopInputTool(name))
                throw new IllegalStateException("desktop READY interface is not currently authorized: " + name);
        }
        if (request.control().remainingToolCalls() == 0)
            return withControl(List.of(), 0, authorizedNames.size());
        List<ToolCallback> selected = new ArrayList<>();
        int characters = 0;
        for (String name : names) characters = addRequired(selected, characters, authorized.get(name));
        return withControl(selected, characters, authorizedNames.size());
    }

    /** Business schemas share the provider limit with permanent interaction controls. */
    synchronized int selectedBusinessToolLimit(int maxSelectedTools) {
        return Math.max(0, Math.min(maxSelectedTools, businessToolLimit()));
    }

    synchronized int desktopReadyToolLimit(int maxSelectedTools) {
        return selectedBusinessToolLimit(maxSelectedTools);
    }

    /** An original launch requirement projects its actual callback, never an open-session substitute. */
    synchronized ToolCatalogProjection projectRequiredLaunch(int maxSelectedTools,
            ComputerUseSessionCursor cursor) {
        refresh();
        if (desktopReadyToolLimit(maxSelectedTools) < 1
                || !trustedHostTool(OnDemandApplicationRecovery.LAUNCH)
                || cursor.phase() == ComputerUseSessionCursor.Phase.RECONCILE
                || !cursor.pendingInvocationIds().isEmpty()
                || !request.control().pendingInteractionEffects().isEmpty())
            throw new IllegalStateException("required frozen launch is not currently authorized and delivery-safe");
        if (request.control().remainingToolCalls() == 0)
            return withControl(List.of(), 0, authorizedNames.size());
        List<ToolCallback> selected = new ArrayList<>();
        int characters = addRequired(selected, 0, authorized.get(OnDemandApplicationRecovery.LAUNCH));
        return withControl(selected, characters, authorizedNames.size());
    }

    /** Only the host's frozen provider directory can consume a prior activation. */
    synchronized Set<String> exposedActivationNames(JsonNode input, Set<String> active, String modelStepId) {
        if (active.isEmpty()) return Set.of();
        if (input == null || !input.isObject() || !input.path("toolNames").isArray()) {
            throw new ToolRecoveryRequiredException(modelStepId,
                    "activated tools lack a frozen provider directory; reconcile legacy history");
        }
        boolean fingerprinted = request.plan().descriptor().onDemandContextPolicy() != null;
        JsonNode fingerprints = input.path("toolFingerprints");
        if (fingerprinted && !fingerprints.isObject()) {
            throw new ToolRecoveryRequiredException(modelStepId,
                    "activated tools lack frozen provider definitions");
        }
        Set<String> seen = new LinkedHashSet<>();
        Set<String> exposed = new LinkedHashSet<>();
        for (JsonNode value : input.path("toolNames")) {
            String name = value.asText("");
            if (!value.isTextual() || name.isBlank() || !seen.add(name)
                    || fingerprinted && (!fingerprints.path(name).isTextual()
                        || fingerprints.path(name).asText().isBlank())) {
                throw new ToolRecoveryRequiredException(modelStepId,
                        "activated tools have an invalid frozen provider directory");
            }
            boolean knownName = authorized.containsKey(name)
                    || HarnessDecisionToolCallback.NAME.equals(name) && decisionCallback != null
                    || NAME.equals(name) && catalogCallback != null && allowedByToolPolicy(this);
            if (!knownName || fingerprinted
                    && !matchesProviderDefinition(name, fingerprints.path(name).asText())) {
                throw new ToolRecoveryRequiredException(modelStepId,
                        "frozen provider tool is no longer authorized or has changed: " + name);
            }
            if (active.contains(name)) exposed.add(name);
        }
        if (fingerprinted && fingerprints.size() != seen.size()) {
            throw new ToolRecoveryRequiredException(modelStepId,
                    "activated provider tool fingerprints do not match frozen names");
        }
        if (!seen.contains(HarnessDecisionToolCallback.NAME)) {
            throw new ToolRecoveryRequiredException(modelStepId,
                    "activated provider directory lacks the trusted harness control tool");
        }
        return Set.copyOf(exposed);
    }

    private ToolCatalogProjection projectPlanned(List<String> names, int maxSelectedTools,
            CatalogMode mode, boolean deferLaunch) {
        return projectPlanned(names, maxSelectedTools, mode, deferLaunch, false);
    }

    private ToolCatalogProjection projectPlanned(List<String> names, int maxSelectedTools,
            CatalogMode mode, boolean deferLaunch, boolean recoveringSession) {
        return projectPlanned(names, maxSelectedTools, mode, deferLaunch, recoveringSession, false);
    }

    private ToolCatalogProjection projectPlanned(List<String> names, int maxSelectedTools,
            CatalogMode mode, boolean deferLaunch, boolean recoveringSession, boolean deferDesktopInput) {
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(mode, "mode");
        refresh();
        if (callbacks.isEmpty()) throw new IllegalStateException("tool catalog callbacks are not bound");
        if (catalogAllowed == null) catalogAllowed = allowedByToolPolicy(this);
        if (mode == CatalogMode.REQUIRED && authorizedNames.isEmpty()) {
            throw new IllegalStateException("tool discovery was requested but the Run has no authorized tools");
        }
        Set<String> unique = new LinkedHashSet<>(activeNames);
        unique.removeIf(name -> !roleAllowed(name) || roleControlNames().contains(name));
        if (deferLaunch) unique.remove(OnDemandApplicationRecovery.LAUNCH);
        if (recoveringSession) unique.removeIf(OnDemandDesktopSessionRecovery::requiresSession);
        if (deferDesktopInput) unique.removeIf(OnDemandDesktopPrerequisites::desktopFrameAction);
        if (names.stream().distinct().count() != names.size()) {
            throw new IllegalStateException("duplicate planned tool name");
        }
        unique.addAll(names);
        unique.removeIf(name -> !roleAllowed(name) || roleControlNames().contains(name));
        if (deferDesktopInput) unique.removeIf(OnDemandDesktopPrerequisites::desktopFrameAction);
        if (unique.size() > maxSelectedTools) {
            throw new ToolCountBudgetExceededException(unique.size(), maxSelectedTools,
                    "planned and activated tools");
        }
        int remaining = request.control().remainingToolCalls();
        if (remaining == 0) {
            return withControl(List.of(), 0, authorizedNames.size());
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
                && selected.size() < businessToolLimit() && catalogCallback != null
                && characters + SpringAiToolCatalog.schemaCharacters(catalogCallback)
                    <= businessSchemaLimit()) {
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
        return withControl(selected, characters,
                authorizedNames.size() + (catalogAllowed && hidden ? 1 : 0));
    }

    synchronized List<ToolSummary> summaries() {
        if (callbacks.isEmpty()) throw new IllegalStateException("tool catalog callbacks are not bound");
        return authorizedNames.stream().map(authorized::get).filter(Objects::nonNull)
                .filter(callback -> roleAllowed(callback.getToolDefinition().name()))
                .map(callback -> new ToolSummary(callback.getToolDefinition().name(),
                        group(callback), callback.getToolDefinition().description()))
                .toList();
    }

    /** Required task schemas remain restricted to currently authorized, genuine host reads. */
    synchronized boolean trustedReadOnlyTool(String name) {
        return com.javaclaw.agent.ToolRiskRegistry.isKnownHostReadOnly(name)
                && trustedHostTool(name);
    }

    synchronized boolean trustedDesktopInputTool(String name) {
        return OnDemandDesktopPrerequisites.desktopFrameAction(name) && trustedHostTool(name);
    }

    /** Metadata matching never activates an interface or grants execution permission. */
    synchronized boolean trustedHostTool(String name) {
        return trustedHostTool(name, authorized.get(name));
    }

    synchronized boolean trustedHostTool(String name, ToolCallback callback) {
        return roleAllowed(name) && callback == authorized.get(name) && callback instanceof SpringAiToolCallback
                && authorizedTools.stream()
                .filter(tool -> tool.descriptor().name().equals(name))
                .anyMatch(tool -> SpringAiAnnotatedToolRegistry.isTrustedReceiptSource(tool)
                        && SpringAiAnnotatedToolRegistry.isExactHostTool(tool)
                        && com.javaclaw.agent.ToolRiskRegistry.matchesHostContract(tool.descriptor())
                        && callback.getToolDefinition().inputSchema().equals(
                                tool.descriptor().inputSchema().toString())
                        && allowedByToolPolicy(tool));
    }

    /** Metadata only: candidates come from the Run-authorized callbacks, without a tool call. */
    synchronized List<ToolGroupSummary> groups() {
        return groupDirectory(authorizedCallbacks());
    }

    /** Read current host state without executing a tool or expanding Run authorization. */
    synchronized List<JsonNode> currentRuntimeContext() {
        Set<com.javaclaw.framework.spi.ToolRuntimeContextProvider> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Set<JsonNode> snapshots = new HashSet<>();
        List<JsonNode> context = new ArrayList<>();
        int characters = 0;
        int limit = InteractionExecutionPolicy.activeMode(request.runRequest(),
                runs.eventsAfter(request.runId(), 0)) == com.javaclaw.framework.api.InteractionMode.BROWSER ? 4_000 : 800;
        for (FrameworkTool tool : authorizedTools) {
            if (!roleAllowed(tool.descriptor().name())) continue;
            if (!SpringAiAnnotatedToolRegistry.isTrustedReceiptSource(tool)) continue;
            var provider = tool.runtimeContextProvider();
            if (provider == null || !seen.add(provider)) continue;
            for (JsonNode value : provider.currentContext()) {
                if (value == null || !value.isObject()) continue;
                if (!snapshots.add(value)) continue;
                int size = value.toString().length();
                if (characters + size > limit) continue;
                context.add(value.deepCopy());
                characters += size;
            }
        }
        return List.copyOf(context);
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
        if (HarnessDecisionToolCallback.NAME.equals(name)) {
            callback = decisionCallback;
        } else if (NAME.equals(name)) {
            if (!allowedByToolPolicy(this)) return false;
            callback = catalogCallback;
        } else {
            callback = authorized.get(name);
        }
        return callback != null && fingerprint(callback).equals(fingerprint);
    }

    private List<ToolCallback> authorizedCallbacks() {
        if (callbacks.isEmpty()) throw new IllegalStateException("tool catalog callbacks are not bound");
        return authorizedNames.stream().filter(this::roleAllowed)
                .map(authorized::get).filter(Objects::nonNull).toList();
    }

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

    /** A multi-term query expands one clearly matching authorized group for tool discovery. */
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

    synchronized List<String> activeNames() {
        refresh();
        return activeNames.stream().filter(this::roleAllowed)
                .filter(name -> !roleControlNames().contains(name)).toList();
    }

    synchronized List<ToolCallback> restoreProviderTools(List<String> names) {
        List<ToolCallback> restored = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (String name : names) {
            if (!unique.add(name)) throw new IllegalStateException("duplicate persisted provider tool");
            if (HarnessDecisionToolCallback.NAME.equals(name)) {
                if (decisionCallback == null) throw new IllegalStateException("harness decision callback is unavailable");
                restored.add(decisionCallback);
                continue;
            }
            if (NAME.equals(name) && !allowedByToolPolicy(this)) {
                throw new IllegalStateException("persisted tool catalog is no longer authorized");
            }
            ToolCallback callback = NAME.equals(name) ? catalogCallback : authorized.get(name);
            if (callback == null) throw new IllegalStateException(
                    "persisted provider tool is no longer authorized: " + name);
            restored.add(callback);
        }
        if (!unique.contains(HarnessDecisionToolCallback.NAME)) {
            throw new IllegalStateException("persisted provider step omitted the harness decision callback");
        }
        int businessLimit = request.plan().descriptor().onDemandContextPolicy() == null
                ? policy.maxTools()
                : Math.min(policy.maxTools(),
                        request.plan().descriptor().onDemandContextPolicy().selectedTools());
        if (restored.size() - 1 > businessLimit
                || restored.stream().filter(callback -> callback != decisionCallback)
                        .mapToInt(SpringAiToolCatalog::schemaCharacters).sum()
                        > policy.maxToolSchemaCharacters()) {
            throw new IllegalStateException("persisted provider tools exceed model context limits");
        }
        return List.copyOf(restored);
    }

    record ToolSummary(String name, String group, String description) { }
    record ToolGroupSummary(String name, int count) { }
    record ToolCandidate(String name, String group, String description,
            String fingerprint, int schemaCharacters) { }
    enum CatalogMode { NONE, OPTIONAL, REQUIRED }
    private record StartedTool(String name, String action, boolean trustedContextRead) { }
    private record ScoredTool(ToolCallback callback, int score, int position) { }

    private IllegalStateException insufficientToolBudget() {
        return new IllegalStateException("tool-call budget is insufficient for catalog activation "
                + "and one target tool call; at least two calls must remain");
    }

    private int addRequired(List<ToolCallback> selected, int characters, ToolCallback callback) {
        int next = SpringAiToolCatalog.schemaCharacters(callback);
        if (selected.size() >= businessToolLimit()) {
            throw new ToolCountBudgetExceededException(selected.size() + 1, businessToolLimit(),
                    callback.getToolDefinition().name());
        }
        if ((long) characters + next > businessSchemaLimit()) {
            throw new ToolSchemaBudgetExceededException((long) characters + next,
                    policy.maxToolSchemaCharacters(), callback.getToolDefinition().name());
        }
        selected.add(callback);
        return characters + next;
    }

    private int controlCharacters() {
        return SpringAiToolCatalog.schemaCharacters(decisionCallback);
    }

    private int businessToolLimit() {
        int limit = policy.maxTools();
        if (InteractionExecutionPolicy.isInteraction(request.runRequest())
                && request.plan().descriptor().onDemandContextPolicy() != null) {
            limit = Math.min(limit, request.plan().descriptor().onDemandContextPolicy().selectedTools());
        }
        return limit - roleControlNames().size();
    }

    private int businessSchemaLimit() {
        return policy.maxToolSchemaCharacters() - roleControlNames().stream().map(authorized::get)
                .filter(Objects::nonNull).mapToInt(SpringAiToolCatalog::schemaCharacters).sum();
    }

    private boolean roleAllowed(String name) {
        ToolCallback callback = authorized.get(name);
        if (callback == null) return false;
        var events = runs.eventsAfter(request.runId(), 0);
        return InteractionExecutionPolicy.allowsTool(request.runRequest(), events, name, group(callback))
                && (!InteractionExecutionPolicy.isInteraction(request.runRequest())
                    || !InteractionExecutionPolicy.WAIT_EVENT_TOOL.equals(name)
                    || InteractionModeFreshness.latestDesktopBaseline(request.runId(), events).isPresent());
    }

    private List<String> roleControlNames() {
        if (!InteractionExecutionPolicy.isInteraction(request.runRequest())
                || request.control().remainingToolCalls() == 0) return List.of();
        List<String> result = new ArrayList<>(List.of(InteractionExecutionPolicy.SELECT_MODE_TOOL,
                "ask_user_clarification"));
        if (roleAllowed(InteractionExecutionPolicy.WAIT_EVENT_TOOL)) {
            result.add(InteractionExecutionPolicy.WAIT_EVENT_TOOL);
        }
        for (String name : result) {
            if (!authorized.containsKey(name)) throw new IllegalStateException("required interaction control is unavailable: " + name);
        }
        return List.copyOf(result);
    }

    private ToolCatalogProjection withControl(List<ToolCallback> business,
            int businessCharacters, int businessAvailable) {
        if (decisionCallback == null) throw new IllegalStateException("harness decision callback is unavailable");
        List<ToolCallback> selected = new ArrayList<>(business.stream()
                .filter(callback -> callback == catalogCallback || roleAllowed(callback.getToolDefinition().name()))
                .filter(callback -> !roleControlNames().contains(callback.getToolDefinition().name())).toList());
        for (String name : roleControlNames()) selected.add(authorized.get(name));
        businessCharacters = selected.stream().mapToInt(SpringAiToolCatalog::schemaCharacters).sum();
        int characters = businessCharacters + controlCharacters();
        if (selected.size() > policy.maxTools()
                || businessCharacters > policy.maxToolSchemaCharacters()) {
            throw new IllegalStateException("harness decision callback exceeds model context limits");
        }
        selected.add(decisionCallback);
        return new ToolCatalogProjection(selected, characters, businessAvailable + 1);
    }

    void validateActual(List<ToolCallback> actual, List<Message> messages) {
        List<ToolCallback> expected = project(messages).callbacks();
        if (!actual.equals(expected)) {
            throw new IllegalStateException("provider tool catalog differs from the authorized projection");
        }
        int characters = actual.stream().mapToInt(SpringAiToolCatalog::schemaCharacters).sum();
        if (actual.size() - 1 > policy.maxTools()
                || characters - controlCharacters() > policy.maxToolSchemaCharacters()) {
            throw new IllegalStateException("provider tool catalog exceeds model context policy");
        }
    }

    private static String group(ToolCallback callback) {
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

    private ObjectNode error(String message) {
        return error("", message, policy.maxToolResultCharacters());
    }

    private ObjectNode error(String action, String message) {
        return error(action, message, policy.maxToolResultCharacters());
    }

    private static ObjectNode error(String action, String message, int maxCharacters) {
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

    private static void putIfFits(ObjectNode result, String key, String value, int maxCharacters) {
        result.put(key, value);
        if (result.toString().length() > maxCharacters) result.remove(key);
    }

    private static void putIfFits(ObjectNode result, String key, int value, int maxCharacters) {
        result.put(key, value);
        if (result.toString().length() > maxCharacters) result.remove(key);
    }
}
