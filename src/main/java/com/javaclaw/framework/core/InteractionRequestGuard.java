package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.RunStore;

/** Validates host delegation provenance before an executor can acquire either backend. */
final class InteractionRequestGuard {
    private InteractionRequestGuard() {}

    static void validate(RunStore runs, ObjectMapper json, RunRequest request) {
        if (!InteractionExecutionPolicy.AGENT_ID.equals(request.agent().id())) return;
        if (!InteractionExecutionPolicy.isInteraction(request) || request.linkage().parentRunId() == null)
            throw new SecurityException("the interaction executor requires an attached host delegation");
        var parent = runs.find(request.linkage().parentRunId()).orElseThrow(() ->
                new SecurityException("interaction parent does not exist"));
        if (!runs.readable(parent.request().scope()) || parent.snapshot().state().terminal()
                || !InteractionDelegateCoordinator.childScope(parent.request().scope()).equals(request.scope())
                || !json.valueToTree(parent.request().scope()).equals(request.attributes().get(InteractionExecutionPolicy.PARENT_SCOPE_ATTRIBUTE))
                || !json.valueToTree(parent.request().source()).equals(request.attributes().get(InteractionExecutionPolicy.PARENT_SOURCE_ATTRIBUTE))
                || !parent.request().permissionCeiling().containsAll(request.permissionCeiling()))
            throw new SecurityException("invalid interaction delegation scope or permission ceiling");
        try {
            var frozen = json.treeToValue(request.attributes().get("framework.taskContract"), TaskContractV3.class);
            var task = json.treeToValue(request.attributes().get(InteractionExecutionPolicy.TASK_ATTRIBUTE), InteractionTask.class);
            var original = TaskResultEvaluator.latestContractV3(runs.eventsAfter(parent.snapshot().id(), 0), json).orElseThrow();
            InteractionExecutionPolicy.requireContiguousInteractionBlock(original);
            var established = TaskResultEvaluator.criterionEvidenceV3(original,
                    TaskEvidenceCollector.collect(runs, parent.snapshot().id()), "", TrustedCapabilityRegistry.builtins());
            for (var criterion : original.criteria()) {
                if (criterion.capabilityId().startsWith("browser.") || criterion.capabilityId().startsWith("desktop.")) break;
                if (!established.containsKey(criterion.id()))
                    throw new SecurityException("INTERACTION_PREREQUISITE_UNMET: " + criterion.id());
            }
            var expected = original.criteria().stream().filter(criterion ->
                    criterion.capabilityId().startsWith("browser.") || criterion.capabilityId().startsWith("desktop.")).toList();
            if (expected.isEmpty() || !frozen.criteria().equals(expected) || !frozen.reliable() || !original.reliable()
                    || !frozen.applicable() || !original.applicable()
                    || frozen.intentStatus() != original.intentStatus()
                    || !task.acceptanceCriterionIds().equals(expected.stream().map(TaskCriterionV3::id).toList())
                    || !task.goal().equals(frozen.originalRequest())
                    || frozen.desktopObservationPolicy() != original.desktopObservationPolicy())
                throw new SecurityException("interaction acceptance contract was weakened or reordered");
            var modes = InteractionExecutionPolicy.allowedModes(request);
            if (modes.isEmpty()
                    || task.mode() == InteractionMode.BROWSER && modes.contains(InteractionMode.DESKTOP)
                    || task.mode() == InteractionMode.DESKTOP && modes.contains(InteractionMode.BROWSER)
                    || expected.stream().anyMatch(c -> c.capabilityId().startsWith("browser."))
                        && !modes.contains(InteractionMode.BROWSER)
                    || expected.stream().anyMatch(c -> c.capabilityId().startsWith("desktop."))
                        && !modes.contains(InteractionMode.DESKTOP))
                throw new SecurityException("INTERACTION_MODE_CONFLICT: missing a required contract backend");
            for (var mode : InteractionExecutionPolicy.allowedModes(request)) {
                String group = mode == InteractionMode.BROWSER ? "web" : "desktop-session";
                if (!ToolGroupAccess.allowsDelegation(parent.request(), group))
                    throw new SecurityException("delegated backend exceeds the parent's authorization");
            }
        } catch (SecurityException invalid) { throw invalid; }
        catch (Exception malformed) { throw new SecurityException("invalid frozen interaction contract", malformed); }
    }
}
