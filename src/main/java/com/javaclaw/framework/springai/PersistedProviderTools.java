package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.core.ToolRecoveryRequiredException;
import com.javaclaw.framework.spi.RunStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates the frozen provider tool directory and its recovery provenance. */
final class PersistedProviderTools {
    private final ReasoningRequest request;
    private final RunStepQuery steps;
    private final RunStore runs;

    PersistedProviderTools(ReasoningRequest request, RunStepQuery steps, RunStore runs) {
        this.request = request;
        this.steps = steps;
        this.runs = runs;
    }

    List<String> offeredNames(AgentStep modelStep) {
        if (modelStep.kind() != AgentStep.Kind.MODEL
                || modelStep.state() != AgentStep.State.COMPLETED
                || modelStep.input() == null
                || !modelStep.input().path("toolNames").isArray()) {
            throw recoveryRequired(modelStep, "persisted provider tool directory is unavailable");
        }
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode value : modelStep.input().path("toolNames")) {
            if (!value.isTextual() || value.asText().isBlank()
                    || !seen.add(value.asText())) {
                throw recoveryRequired(modelStep, "persisted provider tool directory is invalid");
            }
            names.add(value.asText());
        }
        return List.copyOf(names);
    }

    /** Validate the frozen candidate mapping before any pending tool can execute. */
    void validate(AgentStep model, ToolCatalogSession catalog,
            HarnessDecisionToolCallback decisionCallback) {
        var onDemand = request.plan().descriptor().onDemandContextPolicy();
        if (onDemand == null) return;
        JsonNode input = model.input();
        if (input == null || !input.path("toolNames").isArray()
                || !input.path("toolFingerprints").isObject()) {
            throw recoveryRequired(model, "persisted provider tool definitions are unavailable");
        }
        Set<String> candidates = candidateNamesFor(model, catalog);
        Set<String> activated = activatedBefore(model, catalog);
        Set<String> seen = new HashSet<>();
        JsonNode fingerprints = input.path("toolFingerprints");
        for (JsonNode value : input.path("toolNames")) {
            String name = value.asText("");
            String fingerprint = fingerprints.path(name).asText("");
            boolean trustedControl = HarnessDecisionToolCallback.NAME.equals(name)
                    && decisionCallback != null
                    && fingerprint.equals(ToolCatalogSession.fingerprint(decisionCallback))
                    && (catalog == null
                            || catalog.matchesProviderDefinition(name, fingerprint));
            boolean authorizedBusiness = !HarnessDecisionToolCallback.NAME.equals(name)
                    && catalog != null
                    && catalog.matchesProviderDefinition(name, fingerprint);
            if (!value.isTextual() || name.isBlank() || !seen.add(name)
                    || fingerprint.isBlank()
                    || !(trustedControl || authorizedBusiness)) {
                throw recoveryRequired(model, "persisted provider tool is no longer authorized or has changed: "
                        + name);
            }
            if (!name.equals(ToolCatalogSession.NAME)
                    && !name.equals(HarnessDecisionToolCallback.NAME)
                    && !candidates.contains(name) && !activated.contains(name)) {
                throw recoveryRequired(model,
                        "persisted provider tool has no authorized candidate mapping: " + name);
            }
        }
        if (fingerprints.size() != seen.size()) {
            throw recoveryRequired(model, "persisted provider tool fingerprints do not match tool names");
        }
        if (!seen.contains(HarnessDecisionToolCallback.NAME)
                || catalog == null && seen.size() != 1) {
            throw recoveryRequired(model,
                    "persisted provider prompt lacks a unique trusted harness control tool");
        }
    }

    private Set<String> candidateNamesFor(AgentStep model, ToolCatalogSession catalog) {
        JsonNode candidateId = model.input().path("toolCandidateStepId");
        if (candidateId.isMissingNode()) return Set.of();
        if (!candidateId.isTextual() || candidateId.asText().isBlank()) {
            throw recoveryRequired(model, "persisted tool candidate mapping ID is invalid");
        }
        String id = candidateId.asText();
        AgentStep candidate = steps.step(request.runId(), new StepId(id)).orElseThrow(() ->
                recoveryRequired(model, "persisted tool candidate mapping is unavailable: " + id));
        long priorCompletedModel = steps.steps(request.runId()).stream()
                .filter(step -> step.kind() == AgentStep.Kind.MODEL
                        && step.state() == AgentStep.State.COMPLETED
                        && step.startSequence() < model.startSequence())
                .mapToLong(AgentStep::startSequence).max().orElse(0);
        if (candidate.kind() != AgentStep.Kind.ORCHESTRATION
                || candidate.state() != AgentStep.State.COMPLETED
                || candidate.startSequence() <= priorCompletedModel
                || candidate.startSequence() >= model.startSequence()
                || !candidate.input().path("phase").asText().equals("tool_search_v2")
                || inputRedacted(candidate.id()) || outputRedacted(candidate.id())
                || !candidate.output().path("candidates").isArray()) {
            throw recoveryRequired(model, "persisted tool candidate mapping is unavailable: " + id);
        }
        Set<String> names = new HashSet<>();
        int index = 0;
        for (JsonNode entry : candidate.output().path("candidates")) {
            String name = entry.path("name").asText();
            String fingerprint = entry.path("fingerprint").asText();
            if (!entry.path("id").asText().equals("t" + index++)
                    || name.isBlank() || entry.path("group").asText().isBlank()
                    || fingerprint.isBlank() || !names.add(name) || catalog == null
                    || !catalog.matchesCandidate(name, fingerprint)) {
                throw recoveryRequired(model,
                        "persisted tool candidate is no longer authorized or has changed: " + name);
            }
        }
        return names;
    }

    /** A completed MODEL consumes only activations exposed by its trusted frozen directory. */
    private Set<String> activatedBefore(AgentStep model, ToolCatalogSession catalog) {
        Set<String> active = Set.of();
        var events = runs.eventsAfter(request.runId(), 0);
        List<AgentStep> earlier = steps.steps(request.runId()).stream()
                .filter(step -> step.state() == AgentStep.State.COMPLETED
                        && step.lastSequence() < model.startSequence())
                .sorted(Comparator.comparingLong(AgentStep::lastSequence)).toList();
        for (AgentStep step : earlier) {
            if (step.kind() == AgentStep.Kind.MODEL) {
                if (!active.isEmpty()) {
                    if (catalog == null || !step.turnId().equals(request.runId())) {
                        throw recoveryRequired(model, "activated provider history is unavailable");
                    }
                    var starts = events.stream().filter(event ->
                            event.runId().equals(request.runId().value())
                                && event.type().equals("core.step.started") && event.schemaVersion() == 1
                                && event.producer().equals("framework.core")
                                && event.sequence() == step.startSequence()
                                && event.payload().path("stepId").asText().equals(step.id().value())
                                && event.payload().path("kind").asText().equals("MODEL")
                                && event.payload().path("input").equals(step.input())).toList();
                    if (starts.size() != 1) {
                        throw recoveryRequired(model, "activated provider history lacks a trusted frozen directory");
                    }
                    var completions = events.stream().filter(event ->
                            event.runId().equals(request.runId().value())
                                && event.type().equals("core.step.completed") && event.schemaVersion() == 1
                                && event.producer().equals("framework.core")
                                && event.sequence() == step.lastSequence()
                                && event.payload().path("stepId").asText().equals(step.id().value())
                                && event.payload().path("output").equals(step.output())).toList();
                    if (completions.size() != 1) {
                        throw recoveryRequired(model, "activated provider history lacks a trusted completion");
                    }
                    Set<String> consumed = catalog.exposedActivationNames(step.input(), active, step.id().value());
                    Set<String> remaining = new HashSet<>(active);
                    remaining.removeAll(consumed);
                    active = Set.copyOf(remaining);
                }
            } else if (step.kind() == AgentStep.Kind.TOOL
                    && step.input().path("tool").asText().equals(ToolCatalogSession.NAME)
                    && step.input().path("arguments").path("action").asText().equals("activate")) {
                if (outputRedacted(step.id())) {
                    throw recoveryRequired(model, "persisted tool catalog activation was redacted");
                }
                JsonNode output = step.output().path("rawOutput");
                if (!output.path("success").asBoolean(false)
                        || !output.path("action").asText().equals("activate")) continue;
                JsonNode values = output.path("activated");
                if (!values.isArray() || values.isEmpty()) {
                    throw recoveryRequired(model, "persisted tool catalog activation is invalid");
                }
                Set<String> names = new HashSet<>();
                for (JsonNode value : values) {
                    if (!value.isTextual() || value.asText().isBlank()
                            || !names.add(value.asText())) {
                        throw recoveryRequired(model, "persisted tool catalog activation is invalid");
                    }
                }
                active = names;
            }
        }
        return active;
    }

    boolean outputRedacted(StepId id) {
        return runs.eventsAfter(request.runId(), 0).stream().anyMatch(event ->
                event.type().equals("core.step.completed") && event.payload().path("stepId").asText().equals(id.value())
                        && event.payload().path("credentialRedacted").asBoolean(false));
    }

    boolean inputRedacted(StepId id) {
        return runs.eventsAfter(request.runId(), 0).stream().anyMatch(event ->
                event.type().equals("core.step.started")
                        && event.payload().path("stepId").asText().equals(id.value())
                        && event.payload().path("credentialRedacted").asBoolean(false));
    }

    private static ToolRecoveryRequiredException recoveryRequired(AgentStep model, String reason) {
        return new ToolRecoveryRequiredException(model.id().value(), reason);
    }
}
