package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Starts one attached executor; the AgentEngine owns suspension and event-driven delivery. */
public final class InteractionDelegateCoordinator implements InteractionDelegateGateway {
    private final Supplier<AgentClient> agents;
    private final RunStore runs;
    private final ObjectMapper json;
    private final RunUsageLedger usage;
    private final BiConsumer<ToolContext, RunHandle> observer;

    public InteractionDelegateCoordinator(Supplier<AgentClient> agents, RunStore runs,
            ObjectMapper json, RunUsageLedger usage, BiConsumer<ToolContext, RunHandle> observer) {
        this.agents = Objects.requireNonNull(agents);
        this.runs = Objects.requireNonNull(runs);
        this.json = Objects.requireNonNull(json);
        this.usage = Objects.requireNonNull(usage);
        this.observer = Objects.requireNonNull(observer);
    }

    @Override public InteractionResult delegate(ToolContext parent, ToolExecutionContext execution,
            InteractionTask proposed) {
        if (InteractionExecutionPolicy.isInteraction(parent.request()))
            throw new SecurityException("interaction executors cannot delegate recursively");
        execution.cancellation().throwIfCancelled();
        String key = "interaction-executor";
        RunScope scope = childScope(parent.scope());
        var currentContract = TaskResultEvaluator.latestContractV3(runs.eventsAfter(parent.runId(), 0), json)
                .orElseThrow(() -> new IllegalStateException("interaction delegation requires a frozen task contract"));
        // Different provider invocation IDs for the same frozen task must converge even when
        // they arrive concurrently. The store's unique key supplies the atomic reservation.
        String idempotency = "interaction:" + parent.runId().value() + ":contract:"
                + contractKey(currentContract);
        StoredRun existing = runs.findByIdempotencyKey(scope, idempotency).orElse(null);
        if (existing != null) return waitOrResult(parent, execution, existing);
        // A model retry does not create another executor for the same frozen interaction task.
        // Only a host AMEND creates a different contract/revision in this parent Run.
        {
            var expected = currentContract.criteria().stream().filter(c ->
                    c.capabilityId().startsWith("browser.") || c.capabilityId().startsWith("desktop.")).toList();
            var attached = runs.childRuns(parent.runId()).stream()
                    .filter(child -> InteractionExecutionPolicy.isInteraction(child.request())
                            && parent.runId().equals(child.request().linkage().parentRunId())
                            && scope.equals(child.request().scope()))
                    .filter(child -> {
                        try { return expected.equals(json.treeToValue(child.request().attributes()
                                .get("framework.taskContract"), TaskContractV3.class).criteria()); }
                        catch (Exception invalid) { return false; }
                    }).max(Comparator.comparing(child -> child.snapshot().createdAt())).orElse(null);
            if (attached != null) return waitOrResult(parent, execution, attached);
        }
        var active = agents.get().activeTurn(scope).orElse(null);
        if (active != null && !active.state().terminal())
            throw new IllegalStateException("INTERACTION_EXECUTOR_BUSY: " + active.id());

        TaskContractV3 original = currentContract;
        InteractionExecutionPolicy.requireContiguousInteractionBlock(original);
        var established = TaskResultEvaluator.criterionEvidenceV3(original,
                TaskEvidenceCollector.collect(runs, parent.runId()), "", TrustedCapabilityRegistry.builtins());
        for (var criterion : original.criteria()) {
            if (criterion.capabilityId().startsWith("browser.") || criterion.capabilityId().startsWith("desktop.")) break;
            if (!established.containsKey(criterion.id()))
                throw new IllegalStateException("INTERACTION_PREREQUISITE_UNMET: " + criterion.id());
        }
        var criteria = original.criteria().stream().filter(criterion ->
                criterion.capabilityId().startsWith("browser.")
                        || criterion.capabilityId().startsWith("desktop.")).toList();
        if (criteria.isEmpty()) throw new IllegalArgumentException("NO_INTERACTION_CRITERIA");
        Set<String> requested = new HashSet<>(proposed.acceptanceCriterionIds());
        if (!requested.isEmpty() && !requested.equals(criteria.stream()
                .map(TaskCriterionV3::id).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalArgumentException("delegate the complete interaction contract in its original order");
        InteractionTask task = new InteractionTask(1, execution.invocationId(), 1,
                proposed.mode(), proposed.goal(), proposed.necessaryData(), proposed.constraints(),
                criteria.stream().map(TaskCriterionV3::id).toList());
        TaskContractV3 frozen = new TaskContractV3(3, task.goal(), criteria, true,
                original.reliable(), "host.interaction", original.reasonCodes(),
                original.unresolvedInputs(), original.desktopObservationPolicy(), original.intentStatus());
        Map<String, JsonNode> attributes = new LinkedHashMap<>();
        copy(parent.request(), attributes, "workDir");
        attributes.put("framework.threadModelPolicy", parent.request().attributes()
                .getOrDefault("framework.modelPolicyRef", JsonNodeFactory.instance.textNode("")));
        attributes.put("framework.parentStepId", JsonNodeFactory.instance.textNode(
                StepId.tool(parent.runId(), execution.invocationId()).value()));
        attributes.put("framework.interaction.task", json.valueToTree(task));
        attributes.put("framework.interaction.parentSource", json.valueToTree(parent.request().source()));
        attributes.put("framework.interaction.parentScope", json.valueToTree(parent.scope()));
        attributes.put("framework.taskContract", json.valueToTree(frozen));
        attributes.put("framework.interaction.contractOrigin", object()
                .put("parentRunId", parent.runId().value()).put("parentContractHash",
                        Integer.toHexString(original.hashCode())).put("revision", task.revision()));
        var groups = JsonNodeFactory.instance.arrayNode();
        var modes = JsonNodeFactory.instance.arrayNode();
        if (task.mode() != InteractionMode.DESKTOP && ToolGroupAccess.allowsDelegation(parent.request(), "web")) {
            groups.add("web"); modes.add("BROWSER");
        }
        if (task.mode() != InteractionMode.BROWSER && ToolGroupAccess.allowsDelegation(parent.request(), "desktop-session")) {
            groups.add("desktop-session"); modes.add("DESKTOP");
        }
        if (modes.isEmpty()) throw new SecurityException("no interaction backend is authorized");
        if (criteria.stream().anyMatch(c -> c.capabilityId().startsWith("browser."))
                    && !containsMode(modes, InteractionMode.BROWSER)
                || criteria.stream().anyMatch(c -> c.capabilityId().startsWith("desktop."))
                    && !containsMode(modes, InteractionMode.DESKTOP))
            throw new SecurityException("INTERACTION_MODE_CONFLICT: the frozen contract requires an unavailable backend");
        groups.add("interaction");
        // Clarification is a host-controlled interaction capability, not arbitrary tool inheritance.
        groups.add("agents");
        attributes.put(ToolGroupAccess.ATTRIBUTE, groups);
        attributes.put("framework.interaction.allowedModes", modes);
        if (modes.size() == 1) attributes.put("framework.interaction.initialMode", modes.get(0));
        else attributes.put("framework.interaction.initialMode", JsonNodeFactory.instance.textNode("AUTO"));
        Duration remaining = Duration.between(Instant.now(), execution.deadline());
        if (remaining.isZero() || remaining.isNegative()) execution.cancellation().throwIfCancelled();
        RunBudget budget = usage.remainingBudget(parent.runId());
        // Reserve a bounded parent continuation; the ancestor ledger still enforces all spending.
        budget = budget.restrictWith(new RunBudget(remaining.isNegative() || remaining.isZero() ? Duration.ofMillis(1) : remaining,
                Math.max(0, budget.maxInputTokens() - 8_000),
                Math.max(0, budget.maxOutputTokens() - 1_024), budget.maxToolCalls(), budget.maxCost()));
        RunRequest request = RunRequest.builder()
                .agent(AgentDefinitionRef.latest("system.interaction-executor"))
                .profile(RunProfileRef.latest("interaction-executor"))
                .source(new InvocationSource("interaction", key)).scope(scope)
                .input(InputBlock.text(json.valueToTree(task).toString()))
                .linkage(new RunLinkage(parent.runId(), parent.request().linkage().workflowRunId(), key))
                .permissionCeiling(parent.permissions()).budget(budget)
                .idempotencyKey(idempotency).attributes(attributes).build();
        RunHandle handle = agents.get().start(request);
        observer.accept(parent, handle);
        return waitOrResult(parent, execution, runs.find(handle.id()).orElseThrow());
    }

    private InteractionResult waitOrResult(ToolContext parent, ToolExecutionContext execution, StoredRun child) {
        if (!parent.runId().equals(child.request().linkage().parentRunId()))
            throw new SecurityException("interaction child owner mismatch");
        if (child.snapshot().state().terminal()) return InteractionResultProjector.project(runs, json, child);
        throw new InteractionWaitRequiredException(object()
                .put("kind", "interaction.waiting_child")
                .put("childRunId", child.snapshot().id().value())
                .put("invocationId", execution.invocationId())
                .put("taskId", child.request().attributes().get("framework.interaction.task").path("taskId").asText())
                .put("revision", child.request().attributes().get("framework.interaction.task").path("revision").asLong(1))
                .put("afterSequence", 0));
    }

    @Override public JsonNode selectMode(ToolContext context, ToolExecutionContext execution, InteractionMode mode) {
        return selectMode(context, execution, mode, null);
    }

    @Override public JsonNode selectMode(ToolContext context, ToolExecutionContext execution,
            InteractionMode mode, JsonNode checkpoint) {
        if (!InteractionExecutionPolicy.isInteraction(context.request())
                || mode != InteractionMode.BROWSER && mode != InteractionMode.DESKTOP
                || !InteractionExecutionPolicy.allowedModes(context.request()).contains(mode))
            throw new SecurityException("interaction backend is not authorized: " + mode);
        execution.cancellation().throwIfCancelled();
        var events = runs.eventsAfter(context.runId(), 0);
        InteractionMode previous = InteractionExecutionPolicy.activeMode(context.request(), events);
        boolean deliveryUnknown = context.cancellation() instanceof RunControl control
                && !control.pendingInteractionEffects().isEmpty();
        if (previous != null && previous != mode && !deliveryUnknown
                && !InteractionModeFreshness.observed(context.runId(), events, previous, json)) {
            return object().put("kind", "interaction.mode_rejected").put("modeSelectionAccepted", false)
                    .put("activeMode", previous.name()).put("requestedMode", mode.name())
                    .put("errorCode", "FRESH_OBSERVATION_REQUIRED").put("dispatchAttempted", false)
                    .put("message", "The active backend has no fresh host observation after its latest mode/contract. "
                            + "Keep this backend and follow the current host cursor to obtain an observation; "
                            + "for desktop use its actual targets -> open -> observe path. Draft completion claims and "
                            + "historical handles do not count. Clarify or report BLOCKED if observation is unavailable.")
                    .put("inputAuthority", false).put("acceptanceEvidence", false);
        }
        JsonNode business = InteractionBusinessCheckpoint.sanitize(checkpoint);
        long version = events.stream().filter(event -> event.type().equals("core.interaction.mode_selected")
                && event.producer().equals("framework.core")).count() + 1;
        ObjectNode payload = object().put("activeMode", mode.name()).put("mode", mode.name())
                .put("modeVersion", version).put("invocationId", execution.invocationId())
                .put("requiresFreshObservation", true);
        if (business != null) payload.set("checkpoint", business);
        payload.set("handoff", handoff(context, events));
        var appended = runs.append(context.runId(), Set.of(RunState.RUNNING), RunState.RUNNING,
                new RunEventDraft("core.interaction.mode_selected", 1, "framework.core",
                        context.request().linkage().correlationId(), execution.causationStepId(), payload), null, null);
        if (appended.isEmpty()) throw new IllegalStateException("mode selection owner is no longer running");
        return payload;
    }

    private ObjectNode handoff(ToolContext context, List<RunEventEnvelope> events) {
        InteractionMode previous = InteractionExecutionPolicy.activeMode(context.request(), events);
        ObjectNode handoff = object().put("sourceRunId", context.runId().value())
                .put("previousMode", previous == null ? "UNKNOWN" : previous.name())
                .put("confidence", "UNKNOWN").put("inputAuthority", false).put("acceptanceEvidence", false);
        handoff.set("source", json.valueToTree(context.request().source()));
        var started = events.stream().filter(event -> event.schemaVersion() == 1
                        && "framework.core".equals(event.producer()) && "core.tool.started".equals(event.type())
                        && event.payload().path("trustedDesktopTool").asBoolean(false)
                        && com.javaclaw.agent.ToolRiskRegistry.isKnownHostTool(event.payload().path("tool").asText())
                        && (previous == InteractionMode.BROWSER && InteractionExecutionPolicy.allowsTool(
                                context.request(), events, event.payload().path("tool").asText(), "web")
                            || previous == InteractionMode.DESKTOP
                                && event.payload().path("tool").asText().startsWith("desktop_session_")))
                .max(Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
        if (started == null) return handoff;
        String invocation = started.payload().path("invocationId").asText();
        handoff.put("invocationId", invocation).put("tool", started.payload().path("tool").asText());
        events.stream().filter(event -> event.schemaVersion() == 1 && "framework.core".equals(event.producer())
                        && "core.tool.receipt".equals(event.type()) && event.sequence() > started.sequence()
                        && invocation.equals(event.payload().path("invocationId").asText())
                        && started.payload().path("tool").asText().equals(event.payload().path("tool").asText()))
                .max(Comparator.comparingLong(RunEventEnvelope::sequence))
                .ifPresent(receipt -> handoff.put("evidenceRef", receipt.payload().path("evidenceRef").asText()));
        return handoff;
    }

    @Override public JsonNode waitForEvent(ToolContext context, ToolExecutionContext execution, JsonNode input) {
        if (!InteractionExecutionPolicy.isInteraction(context.request())
                || InteractionExecutionPolicy.activeMode(context.request(), runs.eventsAfter(context.runId(), 0))
                    != InteractionMode.DESKTOP)
            throw new SecurityException("native event waits require the desktop backend");
        ObjectNode wait = object().put("kind", "interaction.waiting_event")
                .put("invocationId", execution.invocationId()).put("sessionId", input.path("sessionId").asText())
                .put("afterCapturedAtMillis", input.path("afterCapturedAtMillis").asLong())
                .put("timeoutMillis", Math.min(30_000, Math.max(1, input.path("timeoutMillis").asLong(30_000))));
        var baseline = InteractionModeFreshness.desktopBaselines(context.runId(),
                runs.eventsAfter(context.runId(), 0)).stream().filter(frame ->
                        frame.sessionId().equals(wait.path("sessionId").asText())
                                && frame.capturedAtMillis() == wait.path("afterCapturedAtMillis").asLong())
                .max(Comparator.comparingLong(DesktopObservationBaseline.Frame::sequence)).orElse(null);
        if (baseline == null) return object().put("kind", "interaction.wait_rejected")
                .put("errorCode", "WAIT_BASELINE_REQUIRED").put("dispatchAttempted", false)
                .put("subscriptionCreated", false).put("inputAuthority", false).put("acceptanceEvidence", false)
                .put("message", "No fresh host observation binds these wait arguments. Follow the current host cursor "
                        + "to discover/open/observe a real session, then use its exact sessionId and capturedAtMillis. "
                        + "Do not copy handles or timestamps from a draft, history or invented completion claim.");
        wait.put("afterWindowGeneration", baseline.windowGeneration())
                .put("afterContentRevision", baseline.contentRevision());
        throw new InteractionEventWaitRequiredException(wait);
    }

    @Override public JsonNode readResult(ToolContext parent, ToolExecutionContext execution, JsonNode input) {
        execution.cancellation().throwIfCancelled();
        if (!InteractionExecutionPolicy.isMain(parent.request()) || !parent.runId().equals(execution.runId())
                || !runs.readable(parent.scope()))
            throw new SecurityException("interaction result reader is not an authorized parent");
        String ref = input.path("resultRef").asText("");
        if (!ref.matches("run:[^:]{1,256}:output")) throw new IllegalArgumentException("invalid result reference");
        StoredRun child = runs.find(new RunId(ref.substring(4, ref.length() - 7)))
                .orElseThrow(() -> new IllegalArgumentException("interaction result is unavailable"));
        StoredRun creator = child.request().linkage().parentRunId() == null ? null
                : runs.find(child.request().linkage().parentRunId()).orElse(null);
        if (!InteractionExecutionPolicy.isInteraction(child.request())
                || !child.snapshot().state().terminal() || creator == null
                || !creator.request().scope().equals(parent.scope())
                || !childScope(parent.scope()).equals(child.request().scope())
                || !runs.readable(child.request().scope()) || !runs.readable(creator.request().scope()))
            throw new SecurityException("interaction result does not belong to this conversation");
        int offset = input.path("offset").asInt(0);
        int maxChars = input.path("maxChars").asInt(4_000);
        if (offset < 0 || maxChars < 1 || maxChars > 4_000)
            throw new IllegalArgumentException("invalid result slice");
        JsonNode output = child.snapshot().output();
        String text = output == null ? "" : output.path("text").asText("");
        String contentType = "text/plain";
        if (output != null && output.path("data").isObject()) {
            ObjectNode business = object().put("text", text);
            business.set("data", output.path("data"));
            text = business.toString();
            contentType = "application/json";
        }
        int from = Math.min(offset, text.length());
        int end = (int) Math.min((long) from + maxChars, text.length());
        return object().put("kind", "interaction.business_result").put("resultRef", ref)
                .put("contentType", contentType)
                .put("childRunId", child.snapshot().id().value()).put("offset", from)
                .put("text", text.substring(from, end)).put("nextOffset", end)
                .put("hasMore", end < text.length()).put("totalChars", text.length());
    }

    public static RunScope childScope(RunScope parent) {
        String identity = parent.workspaceId() + "\0" + parent.userId() + "\0" + parent.sessionId()
                + "\0interaction-executor";
        return new RunScope(parent.workspaceId(), parent.userId(), "child:" +
                UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)));
    }
    private String contractKey(TaskContractV3 contract) {
        var criteria = contract.criteria().stream().filter(criterion ->
                criterion.capabilityId().startsWith("browser.")
                        || criterion.capabilityId().startsWith("desktop.")).toList();
        try {
            byte[] value = json.valueToTree(criteria).toString().getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private static void copy(RunRequest request, Map<String, JsonNode> attributes, String key) {
        if (request.attributes().containsKey(key)) attributes.put(key, request.attributes().get(key));
    }
    private static boolean containsMode(JsonNode modes, InteractionMode mode) {
        for (JsonNode value : modes) if (mode.name().equals(value.asText())) return true;
        return false;
    }
    private static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }
}
