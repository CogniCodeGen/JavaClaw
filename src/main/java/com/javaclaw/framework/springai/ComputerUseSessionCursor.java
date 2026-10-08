package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.core.DesktopObservationBaseline;

import java.time.Instant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Host-owned computer-use control state. Conversation IDs, frame tokens and
 * effect evidence occupy different fields; screen text cannot change this state.
 * Rebuilt from the journal on every step, rather than serialized model claims.
 */
record ComputerUseSessionCursor(Phase phase, String requiredTool, String sessionId,
        String targetId, String observationId, List<String> pendingInvocationIds,
        List<String> evidenceRefs, boolean sessionExpired,
        List<String> observedPendingInvocationIds, ControlAccess controlAccess) {
    enum Phase { BOOTSTRAP, DISCOVER_TARGETS, OPEN_SESSION, OBSERVE, READY,
        RECONCILE, RECOVER_SESSION, DISCOVER_APPLICATIONS, SELECT_APPLICATION, OPEN_CONTROL }
    enum ControlAccess { UNKNOWN, READ_ONLY, GRANTED }

    ComputerUseSessionCursor {
        pendingInvocationIds = List.copyOf(pendingInvocationIds);
        evidenceRefs = List.copyOf(evidenceRefs);
        observedPendingInvocationIds = List.copyOf(observedPendingInvocationIds);
        controlAccess = java.util.Objects.requireNonNull(controlAccess, "controlAccess");
    }

    ComputerUseSessionCursor(Phase phase, String requiredTool, String sessionId,
            String targetId, String observationId, List<String> pendingInvocationIds,
            List<String> evidenceRefs, boolean sessionExpired,
            List<String> observedPendingInvocationIds) {
        this(phase, requiredTool, sessionId, targetId, observationId,
                pendingInvocationIds, evidenceRefs, sessionExpired, observedPendingInvocationIds,
                ControlAccess.UNKNOWN);
    }

    ComputerUseSessionCursor(Phase phase, String requiredTool, String sessionId,
            String targetId, String observationId, List<String> pendingInvocationIds,
            List<String> evidenceRefs, boolean sessionExpired) {
        this(phase, requiredTool, sessionId, targetId, observationId,
                pendingInvocationIds, evidenceRefs, sessionExpired, List.of(), ControlAccess.UNKNOWN);
    }

    boolean engaged() { return phase != Phase.BOOTSTRAP; }
    boolean requiresTool() { return !requiredTool.isBlank(); }
    boolean inputAllowed() {
        return controlAccess == ControlAccess.GRANTED && phase == Phase.READY && !observationId.isBlank()
                && observedPendingInvocationIds.containsAll(pendingInvocationIds) && !sessionExpired;
    }
    boolean controlGranted() { return controlAccess == ControlAccess.GRANTED; }
    boolean needsControl() { return !controlGranted() && !sessionId.isBlank() && !targetId.isBlank(); }

    static ComputerUseSessionCursor derive(List<AgentStep> steps,
            List<RunEventEnvelope> events,
            OnDemandHistoryCatalog.DesktopObservation observation,
            List<JsonNode> runtimeContext) {
        return derive(steps, events, observation, runtimeContext, false);
    }

    static ComputerUseSessionCursor derive(List<AgentStep> steps,
            List<RunEventEnvelope> events,
            OnDemandHistoryCatalog.DesktopObservation observation,
            List<JsonNode> runtimeContext, boolean inheritedInputNeedsObservation) {
        RunId owner = steps.isEmpty() ? null : steps.getFirst().turnId();
        List<AgentStep> ownedSteps = steps.stream()
                .filter(step -> step.turnId().equals(owner)).toList();
        List<RunEventEnvelope> ownedEvents = events.stream()
                .filter(event -> owner != null && event.runId().equals(owner.value())).toList();
        Map<String, JsonNode> receipts = new LinkedHashMap<>();
        Map<String, Long> receiptSequences = new LinkedHashMap<>();
        List<RunEventEnvelope> reconciliation = new ArrayList<>();
        for (RunEventEnvelope event : ownedEvents) {
            if (event.schemaVersion() != 1 || !event.producer().equals("framework.core")) continue;
            JsonNode data = event.payload();
            if (event.type().equals("core.tool.receipt")) {
                String invocation = data.path("invocationId").asText("");
                if (!invocation.isBlank()) {
                    receipts.put(invocation, data);
                    receiptSequences.put(invocation, event.sequence());
                }
            } else if (event.type().equals("core.effect.reconciled")
                    && data.path("outcome").asText().equals("SATISFIED")) {
                reconciliation.add(event);
            }
        }
        String session = "";
        String target = "";
        String applicationId = "";
        long openedAt = 0;
        long discoveredAt = 0;
        JsonNode discoveredTargets = JsonNodeFactory.instance.nullNode();
        long closedAt = 0;
        boolean engaged = false;
        ControlAccess control = ControlAccess.UNKNOWN;
        boolean controlUpgrade = false;
        List<String> pending = new ArrayList<>();
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        List<AgentStep> ordered = ownedSteps.stream()
                .sorted(Comparator.comparingLong(AgentStep::startSequence)).toList();
        for (AgentStep step : ordered) {
            if (step.kind() != AgentStep.Kind.TOOL || step.input() == null) continue;
            JsonNode input = step.input();
            String tool = input.path("tool").asText("");
            String invocation = input.path("invocationId").asText("");
            JsonNode receipt = receipts.get(invocation);
            boolean matched = receipt != null && tool.equals(receipt.path("tool").asText());
            JsonNode arguments = input.path("arguments");
            JsonNode metadata = receipt == null ? JsonNodeFactory.instance.nullNode()
                    : receipt.path("metadata");
            boolean succeeded = step.state() == AgentStep.State.COMPLETED && step.output() != null
                    && step.output().path("status").asText().equals("SUCCEEDED");
            if (matched && tool.equals("desktop_session_open"))
                matched = !arguments.path("targetId").asText("").isBlank()
                        && arguments.path("targetId").asText().equals(metadata.path("targetId").asText());
            if (matched && (tool.equals("desktop_session_observe")
                    || tool.equals("desktop_session_close")
                    || OnDemandDesktopPrerequisites.desktopFrameAction(tool)))
                matched = !arguments.path("sessionId").asText("").isBlank()
                        && arguments.path("sessionId").asText().equals(metadata.path("sessionId").asText());
            if (matched && OnDemandDesktopPrerequisites.desktopFrameAction(tool))
                matched = arguments.path("observationId").asText("")
                        .equals(metadata.path("observationId").asText(""));
            if (matched && tool.startsWith("desktop_session_")) {
                String status = receipt.path("status").asText();
                String ref = receipt.path("evidenceRef").asText("");
                if (Set.of("ACCEPTED", "OBSERVED", "VERIFIED").contains(status)
                        && !ref.isBlank() && ref.length() <= 256) refs.add(ref);
                if ((tool.equals("desktop_session_open") && status.equals("ACCEPTED")
                        || tool.equals("desktop_session_observe") && status.equals("OBSERVED"))
                        && succeeded
                        && !metadata.path("sessionId").asText("").isBlank()
                        && !metadata.path("targetId").asText("").isBlank()) {
                    boolean sameSession = session.equals(metadata.path("sessionId").asText())
                            && target.equals(metadata.path("targetId").asText());
                    ControlAccess capability = controlAccess(step, receipt, ownedEvents);
                    if (tool.equals("desktop_session_open") || !sameSession
                            || capability != ControlAccess.UNKNOWN) control = capability;
                    session = metadata.path("sessionId").asText();
                    target = metadata.path("targetId").asText();
                    applicationId = metadata.path("applicationId").asText("");
                    engaged = true;
                    if (tool.equals("desktop_session_open")) {
                        openedAt = step.lastSequence();
                        controlUpgrade = false;
                    }
                }
                if (tool.equals("desktop_session_targets") && status.equals("OBSERVED") && succeeded) {
                    discoveredAt = step.lastSequence();
                    discoveredTargets = step.output().path("rawOutput");
                }
                if (tool.equals("desktop_session_launch_application")
                        && Set.of("ACCEPTED", "UNKNOWN").contains(status)) {
                    engaged = true;
                    if (succeeded && status.equals("ACCEPTED")) {
                        JsonNode data = step.output().path("rawOutput");
                        if (data.path("schemaVersion").asInt() == 1
                                && data.path("kind").asText().equals("desktop.launch")
                                && data.path("requestedApplication").asText().strip()
                                        .equals(arguments.path("application").asText().strip())
                                && metadata.path("requestedApplication").asText().strip()
                                        .equals(arguments.path("application").asText().strip())) {
                            List<JsonNode> launched = new ArrayList<>();
                            for (JsonNode window : data.path("targets")) {
                                if (usableTarget(window) && data.path("processId").asLong() > 0
                                        && window.path("processId").asLong() == data.path("processId").asLong())
                                    launched.add(window);
                            }
                            if (launched.size() == 1) target = launched.getFirst().path("targetId").asText();
                            applicationId = data.path("applicationId").asText("");
                        }
                    }
                }
                if (tool.equals("desktop_session_close") && status.equals("ACCEPTED") && succeeded
                        && input.path("arguments").path("sessionId").asText().equals(session)) {
                    session = "";
                    target = "";
                    engaged = false;
                    control = ControlAccess.UNKNOWN;
                    controlUpgrade = false;
                    closedAt = step.lastSequence();
                }
                if (OnDemandDesktopPrerequisites.desktopFrameAction(tool)
                        && session.equals(arguments.path("sessionId").asText())
                        && target.equals(metadata.path("targetId").asText())
                        && controlRequired(step, receipt)) {
                    control = ControlAccess.READ_ONLY;
                    controlUpgrade = true;
                }
            }
            if (OnDemandDesktopPrerequisites.desktopFrameAction(tool)
                    && !invocation.isBlank()
                    && !rejectedBeforeExecution(step, ownedSteps, ownedEvents)
                    && !reconciled(step, receipt,
                            reconciliation, receipts, receiptSequences, ownedSteps)) {
                // A receipt certifying NOT_SENT is safe. UNKNOWN remains a
                // pending business outcome; a paired later host frame may only
                // establish the baseline for a newly grounded action below.
                String delivery = metadata.path("delivery").asText("");
                String status = receipt == null ? "" : receipt.path("status").asText("");
                boolean unresolved = !matched || !(delivery.equals("NOT_SENT")
                        || delivery.equals("SENT") && Set.of("ACCEPTED", "VERIFIED").contains(status));
                if (unresolved) { pending.add(invocation); engaged = true; }
            }
        }
        String prerequisite = OnDemandDesktopPrerequisites.pendingDesktopPrerequisite(ownedSteps, ownedEvents);
        String required = prerequisite == null ? "" : prerequisite;
        long invalidSessionAt = OnDemandDesktopSessionRecovery.invalidSessionFailureSequence(
                ownedSteps, ownedEvents, runtimeContext);
        var frameRecovery = OnDemandDesktopSessionRecovery.frameRecovery(ownedSteps, ownedEvents);
        boolean expired = OnDemandDesktopSessionRecovery.missingSession(session, runtimeContext);
        boolean recovering = expired || invalidSessionAt > 0 || frameRecovery.required()
                || session.isBlank() && discoveredAt > closedAt
                    && (inheritedInputNeedsObservation
                        || OnDemandDesktopSessionRecovery.noLiveSessions(runtimeContext));
        List<String> observedPending = observedPending(ownedSteps, receipts, receiptSequences,
                ownedEvents, observation, pending);
        Phase phase;
        String observedId = "";
        if (recovering) {
            // Native handles do not survive restart. Rediscovery is read-only;
            // reopening never reconciles a possibly dispatched old input.
            phase = Phase.RECOVER_SESSION;
            List<JsonNode> recovered = recoveryTargets(discoveredTargets, target, applicationId);
            long invalidatedAt = Math.max(invalidSessionAt, frameRecovery.failureSequence());
            long discoveryBoundary = frameRecovery.required() ? Math.max(closedAt, invalidatedAt)
                    : Math.max(openedAt, Math.max(closedAt, invalidatedAt));
            required = discoveredAt > discoveryBoundary
                    && (!frameRecovery.required() || openedAt < discoveredAt)
                    && !recovered.isEmpty()
                    ? "desktop_session_open" : "desktop_session_targets";
            if (frameRecovery.required() && openedAt > invalidatedAt && openedAt > discoveredAt) {
                // One fresh open is followed by observation, never another discovery/reopen loop.
                required = "desktop_session_observe";
                recovering = false;
                phase = Phase.OBSERVE;
            }
            if (required.equals("desktop_session_open"))
                target = recovered.size() == 1 ? recovered.getFirst().path("targetId").asText() : "";
            // A missing handle is diagnostic data, never an argument for the next call.
            if (recovering) {
                session = "";
                control = ControlAccess.UNKNOWN;
            }
        } else if (controlUpgrade && !session.isBlank() && !target.isBlank()) {
            phase = Phase.OPEN_CONTROL;
            required = "desktop_session_open";
        } else if (!required.isBlank()) {
            phase = switch (required) {
                case "desktop_session_targets" -> Phase.DISCOVER_TARGETS;
                case "desktop_session_open" -> Phase.OPEN_SESSION;
                case "desktop_session_applications" -> Phase.DISCOVER_APPLICATIONS;
                case "desktop_session_launch_application" -> Phase.SELECT_APPLICATION;
                default -> Phase.OBSERVE;
            };
        } else if (observation != null && validObservation(observation, ownedSteps,
                receipts, Math.max(closedAt, openedAt))
                && (session.isBlank() || session.equals(observation.sessionId()))) {
            session = observation.sessionId();
            target = observation.targetId();
            observedId = observation.observationId();
            phase = observedPending.containsAll(pending) ? Phase.READY : Phase.RECONCILE;
        } else if (!pending.isEmpty() && session.isBlank()) {
            phase = Phase.RECONCILE;
        } else if (!pending.isEmpty() || engaged && !session.isBlank()) {
            phase = Phase.OBSERVE;
            required = "desktop_session_observe";
        } else {
            phase = engaged ? Phase.DISCOVER_TARGETS : Phase.BOOTSTRAP;
            if (engaged) required = "desktop_session_targets";
        }
        // Only recent receipts are useful in the provider prompt. The complete
        // journal remains available to trusted task/effect verification.
        List<String> evidence = new ArrayList<>(refs);
        if (evidence.size() > 8) evidence = evidence.subList(evidence.size() - 8, evidence.size());
        return new ComputerUseSessionCursor(phase, required, session, target, observedId,
                pending, evidence, recovering, observedPending, control);
    }

    /** Only actual host grants, paired with this operation's raw result, establish control. */
    private static ControlAccess controlAccess(AgentStep step, JsonNode receipt, List<RunEventEnvelope> events) {
        String invocation = step.input().path("invocationId").asText();
        String tool = step.input().path("tool").asText();
        var triple = events.stream().filter(event -> event.runId().equals(step.turnId().value())
                && Set.of("core.tool.started", "core.tool.completed", "core.tool.receipt").contains(event.type())
                && event.payload().path("invocationId").asText().equals(invocation)).toList();
        if (triple.size() != 3 || triple.stream().anyMatch(event ->
                !event.producer().equals("framework.core") || !event.payload().path("tool").asText().equals(tool)
                        || event.payload().has("trustedDesktopTool")
                            && (!event.payload().path("trustedDesktopTool").isBoolean()
                                || !event.payload().path("trustedDesktopTool").booleanValue())))
            return ControlAccess.UNKNOWN;
        var started = triple.stream().filter(event -> event.type().equals("core.tool.started")
                && event.schemaVersion() == 1).findFirst().orElse(null);
        var completed = triple.stream().filter(event -> event.type().equals("core.tool.completed")
                && event.schemaVersion() == 2).findFirst().orElse(null);
        var receipted = triple.stream().filter(event -> event.type().equals("core.tool.receipt")
                && event.schemaVersion() == 1).findFirst().orElse(null);
        if (started == null || completed == null || receipted == null
                || started.sequence() >= completed.sequence() || completed.sequence() >= receipted.sequence()
                || !started.payload().path("arguments").equals(step.input().path("arguments"))
                || !completed.payload().path("status").asText().equals("SUCCEEDED")
                || !completed.payload().path("output").equals(step.output().path("rawOutput"))
                || !receipt.equals(receipted.payload())
                || !receipt.path("evidenceRef").asText().equals("core.tool.completed:"
                        + step.turnId().value() + ":" + invocation)) return ControlAccess.UNKNOWN;
        JsonNode raw = step.output().path("rawOutput");
        JsonNode metadata = receipt.path("metadata");
        boolean opening = tool.equals("desktop_session_open");
        if (!raw.path("schemaVersion").isInt() || raw.path("schemaVersion").intValue() != 1
                || !raw.path("kind").asText().equals(opening ? "desktop.session" : "desktop.observation")
                || !receipt.path("operation").asText().equals(opening ? "open" : "observe")
                || !raw.path("controlGranted").isBoolean()
                || !metadata.path("controlGranted").isTextual()
                || !raw.path("sessionId").asText().equals(metadata.path("sessionId").asText()))
            return ControlAccess.UNKNOWN;
        String target = opening ? raw.path("target").path("targetId").asText() : raw.path("targetId").asText();
        if (target.isBlank() || !target.equals(metadata.path("targetId").asText())
                || !opening && !raw.path("observationId").asText()
                        .equals(metadata.path("observationId").asText())) return ControlAccess.UNKNOWN;
        String grant = metadata.path("controlGranted").asText();
        if (!grant.equals("true") && !grant.equals("false")
                || raw.path("controlGranted").booleanValue() != grant.equals("true")) return ControlAccess.UNKNOWN;
        if (!opening && DesktopObservationBaseline.fromEvents(triple).stream().noneMatch(frame ->
                frame.invocationId().equals(invocation))) return ControlAccess.UNKNOWN;
        return grant.equals("true") ? ControlAccess.GRANTED : ControlAccess.READ_ONLY;
    }

    /** Only a complete, uniquely bound host argument rejection proves that dispatch never started. */
    private static boolean rejectedBeforeExecution(AgentStep step,
            List<AgentStep> steps, List<RunEventEnvelope> events) {
        JsonNode input = step.input();
        JsonNode output = step.output();
        if (step.kind() != AgentStep.Kind.TOOL || step.state() != AgentStep.State.COMPLETED
                || input == null || !input.isObject() || output == null || !output.isObject()
                || !input.path("tool").isTextual() || !input.path("invocationId").isTextual()
                || !output.path("validationRejected").isBoolean()
                || !output.path("validationRejected").booleanValue()
                || !output.path("status").isTextual() || !output.path("status").textValue().equals("FAILED")
                || !output.path("errorCode").isTextual()
                || !output.path("errorCode").textValue().equals("INVALID_TOOL_ARGUMENTS")) return false;
        String tool = input.path("tool").textValue();
        String invocation = input.path("invocationId").textValue();
        JsonNode feedback = output.path("rawOutput");
        if (tool.isBlank() || invocation.isBlank() || !feedback.isObject()
                || !feedback.path("executed").isBoolean() || feedback.path("executed").booleanValue()
                || !feedback.path("tool").isTextual() || !feedback.path("tool").textValue().equals(tool)
                || !feedback.path("error").isTextual()
                || !feedback.path("error").textValue().equals("invalid_tool_arguments")) return false;
        if (!step.id().equals(StepId.tool(step.turnId(), invocation))) return false;
        var ownedSteps = steps.stream().filter(value -> value.turnId().equals(step.turnId())).toList();
        if (ownedSteps.stream().filter(value -> value.id().equals(step.id())).count() != 1
                || ownedSteps.stream().filter(value -> value.input() != null
                        && value.input().path("invocationId").asText().equals(invocation)).count() != 1)
            return false;
        var ownedEvents = events.stream().filter(event -> event.runId().equals(step.turnId().value())).toList();
        if (ownedEvents.stream().anyMatch(event -> Set.of(
                        "core.tool.started", "core.tool.completed", "core.tool.receipt").contains(event.type())
                        && event.payload().path("invocationId").asText().equals(invocation))) return false;
        var lifecycle = ownedEvents.stream().filter(event -> Set.of(
                        "core.step.started", "core.step.completed", "core.step.failed").contains(event.type())
                        && event.payload().path("stepId").asText().equals(step.id().value())).toList();
        if (lifecycle.size() != 2 || lifecycle.stream().anyMatch(event ->
                event.schemaVersion() != 1 || !event.producer().equals("framework.core"))) return false;
        var started = lifecycle.stream().filter(event -> event.type().equals("core.step.started"))
                .findFirst().orElse(null);
        var completed = lifecycle.stream().filter(event -> event.type().equals("core.step.completed"))
                .findFirst().orElse(null);
        if (started == null || completed == null || started.sequence() != step.startSequence()
                || completed.sequence() != step.lastSequence() || started.sequence() >= completed.sequence()
                || !started.payload().path("kind").asText().equals("TOOL")
                || !started.payload().path("input").equals(input)
                || !completed.payload().path("output").equals(output)) return false;
        var rejections = ownedEvents.stream().filter(event -> event.type().equals("core.tool.arguments_rejected")
                && event.payload().path("invocationId").asText().equals(invocation)).toList();
        if (rejections.size() != 1) return false;
        var rejected = rejections.getFirst();
        return rejected.schemaVersion() == 1 && rejected.producer().equals("framework.springai")
                && rejected.sequence() > completed.sequence()
                && rejected.payload().path("tool").isTextual()
                && rejected.payload().path("tool").textValue().equals(tool)
                && rejected.payload().path("feedback").equals(feedback);
    }

    private static boolean controlRequired(AgentStep step, JsonNode receipt) {
        if (step.state() != AgentStep.State.COMPLETED || step.output() == null
                || !receipt.path("status").asText().equals("FAILED")) return false;
        JsonNode raw = step.output().path("rawOutput");
        JsonNode metadata = receipt.path("metadata");
        return raw.path("schemaVersion").asInt() == 1 && raw.path("kind").asText().equals("desktop.action")
                && raw.path("reason").asText().equals("SESSION_CONTROL_REQUIRED")
                && metadata.path("reasonCode").asText().equals("SESSION_CONTROL_REQUIRED")
                && raw.path("delivery").asText().equals("NOT_SENT")
                && metadata.path("delivery").asText().equals("NOT_SENT")
                && raw.path("dispatchAttempted").isBoolean() && !raw.path("dispatchAttempted").booleanValue()
                && metadata.path("dispatchAttempted").asText().equals("false")
                && raw.path("nextStep").asText().equals("OPEN_SESSION")
                && metadata.path("nextStep").asText().equals("OPEN_SESSION")
                && raw.path("sessionId").asText().equals(metadata.path("sessionId").asText())
                && raw.path("targetId").asText().equals(metadata.path("targetId").asText())
                && raw.path("observationId").asText().equals(metadata.path("observationId").asText());
    }

    /**
     * A paired host observation establishes where a new action may start. It does
     * not reconcile delivery, prove a business result, or forget the old input.
     */
    private static List<String> observedPending(List<AgentStep> steps,
            Map<String, JsonNode> receipts, Map<String, Long> receiptSequences,
            List<RunEventEnvelope> events,
            OnDemandHistoryCatalog.DesktopObservation observation, List<String> pending) {
        if (observation == null || pending.isEmpty()) return List.of();
        var frame = DesktopObservationBaseline.fromEvents(events).stream()
                .filter(value -> value.sessionId().equals(observation.sessionId())
                        && value.targetId().equals(observation.targetId())
                        && value.observationId().equals(observation.observationId()))
                .max(Comparator.comparingLong(DesktopObservationBaseline.Frame::sequence))
                .orElse(null);
        if (frame == null) return List.of();
        List<String> observed = new ArrayList<>();
        for (AgentStep step : steps) {
            if (step.input() == null || step.kind() != AgentStep.Kind.TOOL
                    || step.state() != AgentStep.State.COMPLETED) continue;
            String invocation = step.input().path("invocationId").asText("");
            if (!pending.contains(invocation) || step.lastSequence() >= frame.sequence()) continue;
            String tool = step.input().path("tool").asText("");
            JsonNode receipt = receipts.get(invocation);
            if (receipt == null || !receipt.path("tool").asText().equals(tool)
                    || !receipt.path("operation").asText()
                            .equals(tool.substring("desktop_session_".length()))
                    || receiptSequences.getOrDefault(invocation, Long.MAX_VALUE) >= frame.sequence()) continue;
            String status = receipt.path("status").asText("");
            JsonNode metadata = receipt.path("metadata");
            if (!(status.equals("UNKNOWN") || metadata.path("delivery").asText().equals("MAYBE_SENT")))
                continue;
            JsonNode args = step.input().path("arguments");
            String session = args.path("sessionId").asText("");
            String before = args.path("observationId").asText("");
            if (session.isBlank() || before.isBlank()
                    || !session.equals(metadata.path("sessionId").asText())
                    || !before.equals(metadata.path("observationId").asText())
                    || before.equals(frame.observationId())
                    || !frame.targetId().equals(metadata.path("targetId").asText())) continue;
            try {
                long attemptedAt = Instant.parse(receipt.path("observedAt").asText()).toEpochMilli();
                if (attemptedAt > 0 && frame.capturedAtMillis() > attemptedAt) observed.add(invocation);
            } catch (RuntimeException invalidTime) { /* No timestamp means no usable baseline. */ }
        }
        return List.copyOf(observed);
    }

    private static boolean usableTarget(JsonNode target) {
        return target.isObject() && !target.path("targetId").asText("").isBlank()
                && target.path("processId").isIntegralNumber() && target.path("processId").asLong() > 0
                && target.path("visible").isBoolean() && target.path("visible").booleanValue()
                && !target.path("systemSurface").asBoolean(false);
    }

    private static List<JsonNode> recoveryTargets(JsonNode data, String targetId, String applicationId) {
        if (data.path("schemaVersion").asInt() != 1
                || !data.path("kind").asText().equals("desktop.targets")
                || !data.path("targets").isArray()) return List.of();
        List<JsonNode> matching = new ArrayList<>();
        for (JsonNode window : data.path("targets")) {
            if (usableTarget(window) && (targetId.isBlank() && applicationId.isBlank()
                    || window.path("targetId").asText().equals(targetId)
                    || !applicationId.isBlank() && window.path("applicationId").asText()
                            .equalsIgnoreCase(applicationId))) matching.add(window);
        }
        return List.copyOf(matching);
    }

    private static boolean validObservation(OnDemandHistoryCatalog.DesktopObservation observation,
            List<AgentStep> steps, Map<String, JsonNode> receipts, long after) {
        for (AgentStep step : steps) {
            if (step.input() == null || step.output() == null
                    || step.kind() != AgentStep.Kind.TOOL || step.state() != AgentStep.State.COMPLETED
                    || step.startSequence() <= after
                    || !step.input().path("tool").asText().equals("desktop_session_observe")
                    || !step.output().path("status").asText().equals("SUCCEEDED")) continue;
            JsonNode receipt = receipts.get(step.input().path("invocationId").asText());
            if (receipt == null || !receipt.path("tool").asText().equals("desktop_session_observe")
                    || !receipt.path("status").asText().equals("OBSERVED")) continue;
            JsonNode metadata = receipt.path("metadata");
            JsonNode data = step.output().path("rawOutput");
            if (step.input().path("arguments").path("sessionId").asText()
                    .equals(observation.sessionId())
                    && metadata.path("sessionId").asText().equals(observation.sessionId())
                    && metadata.path("targetId").asText().equals(observation.targetId())
                    && metadata.path("observationId").asText().equals(observation.observationId())
                    && data.path("schemaVersion").asInt() == 1
                    && data.path("kind").asText().equals("desktop.observation")
                    && data.path("sessionId").asText().equals(observation.sessionId())
                    && data.path("targetId").asText().equals(observation.targetId())
                    && data.path("observationId").asText().equals(observation.observationId())) return true;
        }
        return false;
    }

    private static boolean reconciled(AgentStep action, JsonNode receipt,
            List<RunEventEnvelope> reconciliation, Map<String, JsonNode> receipts,
            Map<String, Long> receiptSequences, List<AgentStep> steps) {
        if (receipt == null || !receipt.path("tool").asText()
                .equals(action.input().path("tool").asText())) return false;
        JsonNode args = action.input().path("arguments");
        JsonNode metadata = receipt.path("metadata");
        String session = args.path("sessionId").asText("");
        String actionObservation = args.path("observationId").asText("");
        String target = metadata.path("targetId").asText("");
        if (session.isBlank() || actionObservation.isBlank() || target.isBlank()
                || !session.equals(metadata.path("sessionId").asText())
                || !actionObservation.equals(metadata.path("observationId").asText())) return false;
        for (RunEventEnvelope event : reconciliation) {
            JsonNode proof = event.payload();
            String evidenceObservation = proof.path("evidenceObservationId").asText("");
            if (!proof.path("actionInvocationId").asText()
                    .equals(action.input().path("invocationId").asText())
                    || !session.equals(proof.path("sessionId").asText())
                    || !target.equals(proof.path("targetId").asText())
                    || !actionObservation.equals(proof.path("actionObservationId").asText())
                    || evidenceObservation.isBlank()) continue;
            for (AgentStep observed : steps) {
                if (observed.input() == null || observed.output() == null
                        || observed.kind() != AgentStep.Kind.TOOL
                        || observed.state() != AgentStep.State.COMPLETED
                        || observed.startSequence() <= action.lastSequence()
                        || !observed.input().path("tool").asText().equals("desktop_session_observe")
                        || !observed.output().path("status").asText().equals("SUCCEEDED")) continue;
                String invocation = observed.input().path("invocationId").asText();
                JsonNode observedReceipt = receipts.get(invocation);
                if (observedReceipt == null || receiptSequences.getOrDefault(invocation, Long.MAX_VALUE)
                        >= event.sequence()) continue;
                JsonNode frame = observedReceipt.path("metadata");
                JsonNode raw = observed.output().path("rawOutput");
                if (observedReceipt.path("tool").asText().equals("desktop_session_observe")
                        && observedReceipt.path("status").asText().equals("OBSERVED")
                        && session.equals(observed.input().path("arguments").path("sessionId").asText())
                        && session.equals(frame.path("sessionId").asText())
                        && target.equals(frame.path("targetId").asText())
                        && evidenceObservation.equals(frame.path("observationId").asText())
                        && raw.path("schemaVersion").asInt() == 1
                        && raw.path("kind").asText().equals("desktop.observation")
                        && session.equals(raw.path("sessionId").asText())
                        && target.equals(raw.path("targetId").asText())
                        && evidenceObservation.equals(raw.path("observationId").asText())) return true;
            }
        }
        return false;
    }

    ObjectNode payload() {
        ObjectNode data = JsonNodeFactory.instance.objectNode()
                .put("schemaVersion", 1).put("protocol", "computer-use")
                .put("kind", "computer_use.cursor").put("phase", phase.name())
                .put("requiredTool", requiredTool).put("sessionId", sessionId)
                .put("targetId", targetId).put("observationId", observationId)
                .put("inputAllowed", inputAllowed()).put("sessionExpired", sessionExpired);
        data.put("controlAccess", controlAccess.name()).put("controlGranted", controlGranted());
        if (needsControl()) data.put("controlUpgradeTool", "desktop_session_open")
                .put("controlUpgradeTargetId", targetId).put("controlUpgradeRequestedValue", true);
        pendingInvocationIds.forEach(data.putArray("pendingInvocationIds")::add);
        observedPendingInvocationIds.forEach(data.putArray("observedPendingInvocationIds")::add);
        evidenceRefs.forEach(data.putArray("evidenceRefs")::add);
        if (phase == Phase.RECOVER_SESSION) {
            data.put("sessionRecovery", "REQUIRED").put("recoveryOrder", "targets -> open -> observe")
                    .put("sessionIdSource", "successful host desktop_session_open only")
                    .put("targetIdSource", "current host desktop_session_targets only");
        }
        return data;
    }
}
