package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.InteractionMetrics;
import com.javaclaw.framework.api.InteractionMetricsClient;
import com.javaclaw.framework.api.InteractionMode;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.TaskResult;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.StoredRun;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/** Rebuildable metrics over the same readable Run/Step journals, with no model or tool execution. */
public final class InteractionMetricsQuery implements InteractionMetricsClient {
    private final RunStore runs;
    private final RunStepQuery steps;
    private final ObjectMapper json;
    private final Clock clock;

    public InteractionMetricsQuery(RunStore runs, ObjectMapper json, Clock clock) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.steps = new RunStepQuery(runs);
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public InteractionMetrics metrics(RunId parentRunId) {
        StoredRun parent = readable(parentRunId);
        Instant observedAt = clock.instant();
        List<StoredRun> family = new ArrayList<>();
        collect(parent, parent.request().scope(), new HashSet<>(), family);
        var accounting = associatedMaintenance(family, parent.request().scope());
        Accumulator direct = new Accumulator();
        Accumulator descendants = new Accumulator();
        Accumulator aggregate = new Accumulator();
        Accumulator executionFamily = new Accumulator();
        Accumulator associatedMaintenance = new Accumulator();
        Map<String, Accumulator> purposes = new LinkedHashMap<>();
        List<InteractionMetrics.RunMetrics> perRun = new ArrayList<>();
        long delegationCalls = 0, interactionChildren = 0, modeSelections = 0, modeSwitches = 0;
        long clarificationRequests = 0, approvalRequests = 0;
        boolean terminalFamily = true;
        TaskResult taskResult = null;
        for (StoredRun stored : accounting.runs()) {
            RunId id = stored.snapshot().id();
            Accumulator own = new Accumulator();
            List<RunEventEnvelope> events = runs.eventsAfter(id, 0);
            List<AgentStep> recordedSteps = steps.steps(id);
            Map<String, LateUsage> lateUsage = lateProviderUsage(id, recordedSteps, events);
            for (AgentStep step : recordedSteps) {
                if (step.kind() != AgentStep.Kind.MODEL && step.kind() != AgentStep.Kind.MODEL_TASK) continue;
                String purpose = step.kind() == AgentStep.Kind.MODEL ? "primary"
                        : step.input() == null ? "unknown.model_task"
                        : step.input().path("purpose").asText("unknown.model_task");
                LateUsage physical = lateUsage.get(step.id().value());
                own.add(step, physical);
                purposes.computeIfAbsent(purpose, ignored -> new Accumulator()).add(step, physical);
            }
            boolean maintenance = accounting.associations().containsKey(id);
            if (!maintenance) {
                Set<String> delegationInvocations = new HashSet<>();
                InteractionMode previous = InteractionExecutionPolicy.activeMode(stored.request(), List.of());
                for (RunEventEnvelope event : events) {
                    if (event.schemaVersion() == 1 && "framework.core".equals(event.producer())) {
                        // Rejected model tool arguments also have TOOL steps. Only the
                        // authorized gateway's actual invocation start counts as delegation.
                        JsonNode invocation = event.payload().path("invocationId");
                        if ("core.tool.started".equals(event.type())
                                && id.value().equals(event.runId())
                                && InteractionExecutionPolicy.DELEGATE_TOOL.equals(event.payload().path("tool").asText())
                                && invocation.isTextual() && !invocation.asText().isBlank()
                                && delegationInvocations.add(invocation.asText())) delegationCalls++;
                        if ("core.run.waiting_input".equals(event.type())) clarificationRequests++;
                        if ("core.run.waiting_approval".equals(event.type())) approvalRequests++;
                    }
                    if (!InteractionExecutionPolicy.MODE_SELECTED_EVENT.equals(event.type())
                            || event.schemaVersion() != 1 || !"framework.core".equals(event.producer())) continue;
                    InteractionMode selected;
                    try { selected = InteractionMode.valueOf(event.payload().path("mode").asText()); }
                    catch (IllegalArgumentException invalid) { continue; }
                    if (!InteractionExecutionPolicy.allowedModes(stored.request()).contains(selected)) continue;
                    modeSelections++;
                    if (previous != null && previous != selected) modeSwitches++;
                    previous = selected;
                }
            }
            if (maintenance) {
                associatedMaintenance.add(own);
            } else if (id.equals(parentRunId)) {
                direct.add(own);
                if (stored.snapshot().state().terminal()
                        || stored.snapshot().state() == com.javaclaw.framework.api.RunState.PAUSED) {
                    taskResult = TaskResultEvaluator.latestOutcome(events, json).orElse(null);
                }
            } else {
                descendants.add(own);
                if (InteractionExecutionPolicy.isInteraction(stored.request())) interactionChildren++;
            }
            aggregate.add(own);
            if (!maintenance) {
                executionFamily.add(own);
                terminalFamily &= stored.snapshot().state().terminal();
            }
            perRun.add(new InteractionMetrics.RunMetrics(id, stored.request().linkage().parentRunId(),
                    stored.request().source().kind(), stored.snapshot().state(), stored.snapshot().lastSequence(),
                    elapsed(stored, observedAt), own.snapshot()));
        }
        Map<String, InteractionMetrics.Totals> byPurpose = new LinkedHashMap<>();
        purposes.forEach((purpose, value) -> byPurpose.put(purpose, value.snapshot()));
        return new InteractionMetrics(parentRunId, observedAt, parent.snapshot().state(), elapsed(parent, observedAt),
                direct.snapshot(), descendants.snapshot(), aggregate.snapshot(), byPurpose, perRun,
                delegationCalls, interactionChildren, modeSelections, modeSwitches,
                clarificationRequests, approvalRequests, taskResult,
                taskResult == null ? 0 : taskResult.satisfiedCriteria().size(),
                taskResult == null ? 0 : taskResult.unmetCriteria().size(),
                taskResult == null ? 0 : taskResult.evidenceRefs().size(), terminalFamily,
                executionFamily.snapshot(), associatedMaintenance.snapshot(),
                List.copyOf(accounting.associations().values()), true);
    }

    private StoredRun readable(RunId id) {
        StoredRun run = runs.find(id).orElseThrow(() -> new NoSuchElementException("run is not readable"));
        if (!runs.readable(run.request().scope())) throw new NoSuchElementException("run is not readable");
        return run;
    }

    private void collect(StoredRun owner, RunScope rootScope, Set<RunId> visited, List<StoredRun> family) {
        RunId id = owner.snapshot().id();
        if (!visited.add(id)) throw new IllegalStateException("cyclic or duplicate run lineage");
        family.add(owner);
        for (StoredRun child : runs.childRuns(id)) {
            if (!id.equals(child.request().linkage().parentRunId())
                    || !rootScope.workspaceId().equals(child.request().scope().workspaceId())
                    || !rootScope.userId().equals(child.request().scope().userId())) {
                throw new SecurityException("metrics lineage crosses the owner scope");
            }
            collect(readable(child.snapshot().id()), rootScope, visited, family);
        }
    }

    /** Follow only host-attested accounting associations; never invent a live parent or budget link. */
    private Accounting associatedMaintenance(List<StoredRun> family, RunScope rootScope) {
        List<StoredRun> accounted = new ArrayList<>(family);
        Set<RunId> included = new HashSet<>();
        family.forEach(run -> included.add(run.snapshot().id()));
        Map<RunId, InteractionMetrics.MaintenanceAssociation> associations = new LinkedHashMap<>();
        Map<RunScope, List<StoredRun>> inventories = new LinkedHashMap<>();
        for (int index = 0; index < accounted.size(); index++) {
            StoredRun origin = accounted.get(index);
            // Calls already owned by a maintenance Run stay in that Run; the gateway does not mint another sidecar.
            if (origin.request().source().kind().equals("maintenance")) continue;
            RunScope maintenanceScope = MaintenanceRunProvenance.scopeFor(origin.request().scope());
            if (!runs.readable(maintenanceScope)) continue;
            for (StoredRun candidate : inventories.computeIfAbsent(maintenanceScope, runs::scopeRuns)) {
                if (included.contains(candidate.snapshot().id()) || !trustedMaintenance(origin, candidate)) continue;
                List<StoredRun> branch = new ArrayList<>();
                collect(readable(candidate.snapshot().id()), rootScope, new HashSet<>(), branch);
                for (StoredRun member : branch) {
                    if (!included.add(member.snapshot().id()))
                        throw new IllegalStateException("maintenance association overlaps an existing execution lineage");
                    accounted.add(member);
                    associations.put(member.snapshot().id(), new InteractionMetrics.MaintenanceAssociation(
                            member.snapshot().id(), origin.snapshot().id(), candidate.snapshot().id()));
                }
            }
        }
        return new Accounting(List.copyOf(accounted), associations);
    }

    private boolean trustedMaintenance(StoredRun origin, StoredRun candidate) {
        var source = origin.request();
        var request = candidate.request();
        if (!request.scope().equals(MaintenanceRunProvenance.scopeFor(source.scope()))
                || !runs.readable(request.scope()) || !request.source().kind().equals("maintenance")
                || !request.source().id().equals(source.scope().sessionId())
                || request.linkage().parentRunId() != null
                || !Objects.equals(request.linkage().workflowRunId(), source.linkage().workflowRunId())
                || !request.agent().equals(source.agent()) || !request.profile().equals(source.profile())
                || !request.permissionCeiling().equals(com.javaclaw.framework.api.PermissionSet.NONE)
                || !request.attributes().getOrDefault("framework.maintenance", json.nullNode()).isBoolean()
                || !request.attributes().getOrDefault("framework.maintenance", json.nullNode()).booleanValue()
                || !request.attributes().getOrDefault("framework.originTurnId", json.nullNode()).isTextual()
                || !request.attributes().get("framework.originTurnId").textValue().equals(origin.snapshot().id().value())
                || !request.attributes().getOrDefault("framework.targetThreadId", json.nullNode()).isTextual()
                || !request.attributes().get("framework.targetThreadId").textValue().equals(source.scope().sessionId()))
            return false;
        String purpose = request.linkage().correlationId();
        if (purpose == null || !purpose.startsWith("memory.") || request.inputs().size() != 1
                || !request.inputs().getFirst().type().equals("core.text")
                || !request.inputs().getFirst().data().equals(json.createObjectNode().put("text", purpose))) return false;
        var created = runs.eventsAfter(candidate.snapshot().id(), 0).stream().filter(event ->
                event.type().equals("core.run.created") && event.runId().equals(candidate.snapshot().id().value())).toList();
        if (created.size() != 1) return false;
        var event = created.getFirst();
        JsonNode marker = event.payload().path(MaintenanceRunProvenance.CREATED_FIELD);
        // Reserved request attributes alone, including old historical records, never acquire this authority.
        return event.sequence() == 1 && event.schemaVersion() == 1 && event.producer().equals("framework.core")
                && marker.isObject() && marker.path("schemaVersion").isInt() && marker.path("schemaVersion").intValue() == 1
                && marker.path("issuer").asText().equals(MaintenanceRunProvenance.ISSUER)
                && marker.path("originRunId").asText().equals(origin.snapshot().id().value())
                && marker.path("workspaceId").asText().equals(source.scope().workspaceId())
                && marker.path("userId").asText().equals(source.scope().userId())
                && marker.path("targetThreadId").asText().equals(source.scope().sessionId())
                && marker.path("originSourceKind").asText().equals(source.source().kind())
                && marker.path("originSourceId").asText().equals(source.source().id())
                && marker.path("purpose").asText().equals(purpose);
    }

    private record Accounting(List<StoredRun> runs,
                              Map<RunId, InteractionMetrics.MaintenanceAssociation> associations) { }

    private static long elapsed(StoredRun run, Instant now) {
        return Math.max(0, Duration.between(run.snapshot().createdAt(),
                run.snapshot().state().terminal() ? run.snapshot().updatedAt() : now).toMillis());
    }

    /** A late physical charge replaces this attempt's usage; it never creates another attempt. */
    private static Map<String, LateUsage> lateProviderUsage(RunId owner, List<AgentStep> steps,
                                                           List<RunEventEnvelope> events) {
        Map<String, AgentStep> providers = new LinkedHashMap<>();
        for (AgentStep step : steps) {
            if (owner.equals(step.turnId())
                    && (step.kind() == AgentStep.Kind.MODEL || step.kind() == AgentStep.Kind.MODEL_TASK))
                providers.put(step.id().value(), step);
        }
        Set<String> trustedStarts = new HashSet<>();
        for (RunEventEnvelope event : events) {
            if (!owner.value().equals(event.runId()) || event.schemaVersion() != 1
                    || !"framework.core".equals(event.producer()) || !"core.step.started".equals(event.type())) continue;
            AgentStep step = providers.get(event.payload().path("stepId").asText(""));
            if (step != null && event.sequence() == step.startSequence()
                    && step.kind().name().equals(event.payload().path("kind").asText())
                    && Objects.equals(step.input(), event.payload().get("input")))
                trustedStarts.add(step.id().value());
        }
        Map<String, LateUsage> latest = new LinkedHashMap<>();
        for (RunEventEnvelope event : events) {
            if (!owner.value().equals(event.runId()) || event.schemaVersion() != 1
                    || !"framework.core".equals(event.producer()) || !"core.step.usage".equals(event.type())
                    || !event.payload().path("physicalCallSettled").isBoolean()
                    || !event.payload().path("physicalCallSettled").booleanValue()) continue;
            String stepId = event.payload().path("stepId").asText("");
            AgentStep step = providers.get(stepId);
            JsonNode usage = event.payload().path("usage");
            if (!trustedStarts.contains(stepId) || event.sequence() <= step.startSequence()
                    || !usage.isObject() || !nonNegativeToken(usage.path("inputTokens"))
                    || !nonNegativeToken(usage.path("outputTokens"))) continue;
            LateUsage previous = latest.get(stepId);
            if (previous == null || previous.sequence() < event.sequence())
                latest.put(stepId, new LateUsage(event.sequence(), usage));
        }
        return latest;
    }

    private static boolean nonNegativeToken(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0;
    }

    private record LateUsage(long sequence, JsonNode usage) { }

    private static final class Accumulator {
        long attempts, primary, auxiliary, completed, failed, unfinished, retries, cached;
        long input, output, unknownUsage, unknownCost, promptCharacters, unknownPrompt, schemaCharacters, media, lateUsage;
        BigDecimal cost = BigDecimal.ZERO;

        void add(AgentStep step, LateUsage physical) {
            JsonNode request = step.input();
            if (request != null && request.path("cacheHit").asBoolean(false)) { cached++; return; }
            attempts++;
            boolean isPrimary = step.kind() == AgentStep.Kind.MODEL;
            if (isPrimary) primary++; else auxiliary++;
            switch (step.state()) {
                case COMPLETED -> completed++;
                case FAILED -> failed++;
                case RUNNING -> unfinished++;
            }
            if (request != null && request.path("attempt").asInt(isPrimary ? 1 : 0) > (isPrimary ? 1 : 0)) retries++;
            JsonNode usage = physical == null ? step.usage() : physical.usage();
            if (physical != null) lateUsage++;
            boolean knownUsage = usage != null && usage.path("inputTokens").isIntegralNumber()
                    && usage.path("outputTokens").isIntegralNumber()
                    && usage.path("inputTokens").asLong() >= 0 && usage.path("outputTokens").asLong() >= 0;
            if (knownUsage) {
                input = Math.addExact(input, usage.path("inputTokens").asLong());
                output = Math.addExact(output, usage.path("outputTokens").asLong());
            }
            // Legacy adapters encoded absent provider usage as two zeros, so zero is ambiguous.
            if (!knownUsage || usage.path("inputTokens").asLong() + usage.path("outputTokens").asLong() == 0) unknownUsage++;
            if (usage != null && usage.path("estimatedCostCny").isNumber()
                    && usage.path("estimatedCostCny").decimalValue().signum() >= 0) {
                BigDecimal recordedCost = usage.path("estimatedCostCny").decimalValue();
                cost = cost.add(recordedCost);
                // Legacy pricing falls back to zero for missing quotes as well as free models.
                JsonNode priceKnown = usage.path("priceKnown");
                if (recordedCost.signum() == 0 && !(priceKnown.isBoolean() && priceKnown.booleanValue())) unknownCost++;
            } else unknownCost++;
            if (request != null && request.path("messages").isArray()) {
                JsonNode messages = request.path("messages");
                promptCharacters = Math.addExact(promptCharacters, messages.toString().length());
                for (JsonNode message : messages) {
                    if (message.path("media").isArray()) media += message.path("media").size();
                }
            } else unknownPrompt++;
            if (request != null && request.path("toolSchemaCharacters").isIntegralNumber())
                schemaCharacters = Math.addExact(schemaCharacters, Math.max(0, request.path("toolSchemaCharacters").asLong()));
        }

        void add(Accumulator value) {
            attempts += value.attempts; primary += value.primary; auxiliary += value.auxiliary;
            completed += value.completed; failed += value.failed; unfinished += value.unfinished;
            retries += value.retries; cached += value.cached; input = Math.addExact(input, value.input);
            output = Math.addExact(output, value.output); cost = cost.add(value.cost);
            unknownUsage += value.unknownUsage; unknownCost += value.unknownCost;
            promptCharacters = Math.addExact(promptCharacters, value.promptCharacters);
            unknownPrompt += value.unknownPrompt;
            schemaCharacters = Math.addExact(schemaCharacters, value.schemaCharacters); media += value.media;
            lateUsage += value.lateUsage;
        }

        InteractionMetrics.Totals snapshot() {
            return new InteractionMetrics.Totals(attempts, primary, auxiliary, completed, failed, unfinished,
                    retries, cached, input, output, cost, unknownUsage, unknownCost, promptCharacters,
                    unknownPrompt, schemaCharacters, media, lateUsage);
        }
    }
}
