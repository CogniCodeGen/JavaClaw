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
import com.javaclaw.framework.core.TaskResultEvaluator;
import com.javaclaw.framework.core.ToolRecoveryRequiredException;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.ToolDescriptor;
import com.javaclaw.framework.spi.ToolExecutionContext;
import com.javaclaw.framework.spi.ToolPolicyDecision;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static com.javaclaw.framework.springai.ToolCatalogDirectory.group;
import static com.javaclaw.framework.springai.ToolCatalogDirectory.putIfFits;

/** Run-scoped, durable discovery and activation of the tools already allowed by a plan. */
final class ToolCatalogSession implements FrameworkTool {
    static final String NAME = "framework_tool_catalog";

    private final ReasoningRequest request;
    private final RunStore runs;
    private final ObjectMapper json;
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
    private final ToolCatalogActivationState activation;

    ToolCatalogSession(ReasoningRequest request, RunStore runs, ObjectMapper json,
                       StepContextPolicy policy, List<FrameworkTool> runTools) {
        this.request = Objects.requireNonNull(request, "request");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.json = Objects.requireNonNull(json, "json");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.activation = new ToolCatalogActivationState(request.runId(), runs,
                () -> authorized, this::exposedActivationNames);
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
        return ToolCatalogDirectory.activateSelection(arguments, authorized, maxTools, maxSchemaCharacters, maxResultCharacters);
    }

    private synchronized void refresh() { activation.refresh(); }

    synchronized ToolCatalogProjection project(List<Message> messages) {
        refresh();
        if (callbacks.isEmpty()) throw new IllegalStateException("tool catalog callbacks are not bound");
        List<ToolCallback> direct = authorizedNames.stream().filter(this::roleAllowed)
                .map(authorized::get).toList();
        int remaining = request.control().remainingToolCalls();
        if (directFit) {
            if (remaining == 0) return withControl(List.of(), 0, direct.size());
            return withControl(direct,
                    direct.stream().mapToInt(SpringAiToolCatalog::schemaCharacters).sum(), direct.size());
        }
        if (remaining == 0) {
            return withControl(List.of(), 0, authorizedNames.size() + 1);
        }
        if (activation.names().isEmpty() && remaining < 2) {
            // The model can still submit a structured blocked/needs-input decision.
            return withControl(List.of(), 0, authorizedNames.size() + 1);
        }
        List<ToolCallback> selected = new ArrayList<>();
        int characters = 0;
        int activeCharacters = activation.names().stream().filter(this::roleAllowed).map(authorized::get)
                .filter(Objects::nonNull).mapToInt(SpringAiToolCatalog::schemaCharacters).sum();
        boolean catalogVisible = remaining > 1
                && (activation.names().isEmpty() || (activation.names().size() < businessToolLimit()
                && activeCharacters + SpringAiToolCatalog.schemaCharacters(catalogCallback)
                    <= businessSchemaLimit()));
        if (catalogVisible) {
            selected.add(catalogCallback);
            characters += SpringAiToolCatalog.schemaCharacters(catalogCallback);
        }
        for (String name : activation.names()) {
            if (!roleAllowed(name)) continue;
            ToolCallback callback = authorized.get(name);
            if (callback == null) throw new IllegalStateException("activated tool is no longer authorized: " + name);
            characters = addRequired(selected, characters, callback);
        }
        if (catalogVisible) {
            Set<String> preferredGroups = SpringAiToolCatalog.preferredGroups(request, messages,
                    callbacks.stream().filter(callback ->
                            !(callback instanceof HarnessDecisionToolCallback)).toList());
            List<ToolCallback> optional = authorizedNames.stream().filter(this::roleAllowed).map(authorized::get)
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
        return projectRequiredRead(name, maxSelectedTools, false);
    }

    /** The optional window inventory is another exact host read, never a control or input interface. */
    synchronized ToolCatalogProjection projectRequiredRead(String name, int maxSelectedTools,
            boolean includeWindowCandidates) {
        refresh();
        if (maxSelectedTools < 1 || !trustedReadOnlyTool(name)) {
            throw new IllegalStateException("required frozen read is not currently authorized");
        }
        if (includeWindowCandidates && (!name.equals("desktop_session_observe")
                || !trustedReadOnlyTool("desktop_session_window_candidates")))
            throw new IllegalStateException("frozen desktop window inventory is not currently authorized");
        if (includeWindowCandidates && selectedBusinessToolLimit(maxSelectedTools) < 2)
            throw new ToolCountBudgetExceededException(2, selectedBusinessToolLimit(maxSelectedTools),
                    "desktop_session_window_candidates");
        if (request.control().remainingToolCalls() == 0) {
            return withControl(List.of(), 0, authorizedNames.size());
        }
        List<ToolCallback> selected = new ArrayList<>();
        int characters = addRequired(selected, 0, authorized.get(name));
        if (includeWindowCandidates)
            characters = addRequired(selected, characters, authorized.get("desktop_session_window_candidates"));
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
            boolean candidates = name.equals("desktop_session_window_candidates") && trustedReadOnlyTool(name);
            if (!observation && !candidates && !trustedDesktopInputTool(name))
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
        Set<String> unique = new LinkedHashSet<>(activation.names());
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

    /** Internal routing only. Window tracking must not compete with the tiny provider runtime block. */
    synchronized List<JsonNode> currentDesktopWindowContext() {
        if (!trustedReadOnlyTool("desktop_session_window_candidates")) return List.of();
        for (FrameworkTool tool : authorizedTools) {
            if (!tool.descriptor().name().equals("desktop_session_window_candidates")
                    || !SpringAiAnnotatedToolRegistry.isExactHostTool(tool)
                    || !SpringAiAnnotatedToolRegistry.isTrustedReceiptSource(tool)) continue;
            var provider = tool.runtimeContextProvider();
            if (provider == null) continue;
            try {
                return provider.currentContext().stream().filter(value -> value != null && value.isObject()
                        && value.path("kind").asText().equals("desktop.windows.current")
                        && value.toString().length() <= 6_000).map(value -> value.<JsonNode>deepCopy()).toList();
            } catch (RuntimeException unavailable) {
                return List.of(); // Supplemental tracking cannot invalidate the current observed frame.
            }
        }
        return List.of();
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
        return ToolCatalogDirectory.groupDirectory(allowed);
    }

    static List<ToolCandidate> searchDirectory(List<ToolCallback> allowed, String query, List<String> groups, int limit) {
        return ToolCatalogDirectory.searchDirectory(allowed, query, groups, limit);
    }

    static List<ToolCatalogPages.Entry> listDirectory(List<ToolCallback> allowed, String query, String selectedGroup) {
        return ToolCatalogDirectory.listDirectory(allowed, query, selectedGroup);
    }

    static String fingerprint(ToolCallback callback) {
        return ToolCatalogDirectory.fingerprint(callback);
    }

    static ObjectNode invalidGroupResult(List<ToolCallback> allowed, String group, int maxCharacters) {
        return ToolCatalogDirectory.invalidGroupResult(allowed, group, maxCharacters);
    }

    synchronized List<String> activeNames() {
        refresh();
        return activation.names().stream().filter(this::roleAllowed)
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
        if (InteractionExecutionPolicy.DELEGATE_TOOL.equals(name)) {
            var contract = TaskResultEvaluator.latestContractV3(events.stream()
                    .filter(event -> event.runId().equals(request.runId().value())).toList(), json).orElse(null);
            if (InteractionExecutionPolicy.interactionCriteria(contract).isEmpty()) return false;
        }
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

    private ObjectNode error(String message) {
        return ToolCatalogDirectory.error("", message, policy.maxToolResultCharacters());
    }

    private ObjectNode error(String action, String message) {
        return ToolCatalogDirectory.error(action, message, policy.maxToolResultCharacters());
    }

}
