package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.ToolExecutionStatus;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.spi.RunStore;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Derives the next desktop tool from persisted steps and trusted effect receipts. */
final class OnDemandDesktopPrerequisites {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private final ReasoningRequest request;
    private final RunStore runs;
    private final RunStepQuery steps;

    OnDemandDesktopPrerequisites(ReasoningRequest request, RunStore runs, RunStepQuery steps) {
        this.request = request;
        this.runs = runs;
        this.steps = steps;
    }

    static boolean desktopFrameAction(String tool) {
        return switch (tool) {
            case "desktop_session_click", "desktop_session_type", "desktop_session_key",
                    "desktop_session_scroll" -> true;
            default -> false;
        };
    }

    /** Next desktop step proved by this Run's settled tool outputs and receipts. */
    String pendingDesktopPrerequisite() {
        return pendingDesktopPrerequisite(steps.steps(request.runId()),
                runs.eventsAfter(request.runId(), 0));
    }

    static String pendingDesktopPrerequisite(List<AgentStep> persistedSteps,
            List<RunEventEnvelope> events) {
        Map<String, JsonNode> receipts = new HashMap<>();
        Map<String, Set<String>> mustObserve = new HashMap<>();
        String owner = persistedSteps.isEmpty() ? "" : persistedSteps.getFirst().turnId().value();
        for (var event : events) {
            if (!event.type().equals("core.tool.receipt")
                    || !event.runId().equals(owner)
                    || event.schemaVersion() != 1
                    || !event.producer().equals("framework.core")) continue;
            String invocationId = event.payload().path("invocationId").asText("");
            String status = event.payload().path("status").asText("");
            if (!invocationId.isBlank() && !status.isBlank()) {
                String tool = event.payload().path("tool").asText("");
                receipts.put(invocationId, event.payload());
                if (com.javaclaw.agent.ToolRiskRegistry.isDesktopSessionTool(tool)
                        && event.payload().path("metadata").path("nextStep")
                                .asText().equals("OBSERVE")) {
                    mustObserve.computeIfAbsent(invocationId, ignored -> new HashSet<>())
                            .add(tool);
                }
            }
        }
        Set<String> openedSessions = new HashSet<>();
        Map<String, String> sessionTargets = new HashMap<>();
        String activeSession = "";
        String prerequisite = null;
        String pendingLaunchApplication = "";
        String pendingLaunchApplicationId = "";
        long pendingLaunchProcessId = 0;
        List<AgentStep> persisted = persistedSteps.stream()
                .filter(step -> step.turnId().value().equals(owner))
                .sorted(java.util.Comparator.comparingLong(AgentStep::startSequence)).toList();
        for (AgentStep step : persisted) {
            if (step.kind() == AgentStep.Kind.ORCHESTRATION && step.input() != null
                    && step.state() == AgentStep.State.COMPLETED
                    && step.input().path("phase").asText()
                            .equals(ModelStepJournal.LEGACY_DESKTOP_REOBSERVE)) {
                String sessionId = step.input().path("sessionId").asText("");
                if (!sessionId.isBlank()) {
                    activeSession = sessionId;
                    prerequisite = "desktop_session_observe";
                }
                continue;
            }
            if (step.kind() != AgentStep.Kind.TOOL || step.input() == null) continue;
            String tool = step.input().path("tool").asText("");
            String sessionId = step.input().path("arguments").path("sessionId").asText("");
            switch (tool) {
                case "desktop_session_launch_application" -> {
                    JsonNode output = step.output() == null ? NODES.nullNode() : step.output();
                    JsonNode data = output.path("rawOutput");
                    ToolExecutionStatus invocationStatus = statusOf(output.path("status"));
                    String requestedApplication = step.input().path("arguments")
                            .path("application").asText("").strip();
                    boolean typedLaunch = data.path("schemaVersion").isInt()
                            && data.path("schemaVersion").intValue() == 1
                            && data.path("kind").asText().equals("desktop.launch")
                            && data.path("requestedApplication").asText()
                                    .equals(requestedApplication)
                            && data.path("targets").isArray();
                    JsonNode receipt = receipts.get(step.input().path("invocationId").asText(""));
                    boolean notSent = OnDemandApplicationRecovery.confirmedNotSent(step, receipt);
                    boolean accepted = trustedDesktopReceipt(step, receipts, "ACCEPTED") != null;
                    if (invocationStatus == ToolExecutionStatus.UNCERTAIN
                            || invocationStatus == ToolExecutionStatus.TIMED_OUT
                            || receipt != null && receipt.path("tool").asText().equals(tool)
                                && receipt.path("status").asText().equals("UNKNOWN")
                            || invocationStatus != ToolExecutionStatus.SUCCEEDED && !notSent
                            || invocationStatus == ToolExecutionStatus.SUCCEEDED
                                && (!accepted || !typedLaunch || data.path("processId").asLong() <= 0)) {
                        prerequisite = "desktop_session_targets";
                        pendingLaunchApplication = requestedApplication;
                        pendingLaunchApplicationId = typedLaunch
                                ? data.path("applicationId").asText("") : "";
                        pendingLaunchProcessId = typedLaunch
                                && data.path("processId").isIntegralNumber()
                                ? data.path("processId").asLong(0) : 0;
                    } else if (invocationStatus == ToolExecutionStatus.SUCCEEDED
                            && accepted
                            && typedLaunch
                            && data.path("processId").isIntegralNumber()
                            && data.path("processId").asLong(0) > 0
                            && data.path("targets").isArray()) {
                        prerequisite = data.path("targets").isEmpty()
                                ? "desktop_session_targets" : "desktop_session_open";
                        pendingLaunchApplication = requestedApplication;
                        pendingLaunchApplicationId = data.path("applicationId").asText("");
                        pendingLaunchProcessId = data.path("processId").asLong();
                    } else if (!"desktop_session_targets".equals(prerequisite)
                            || pendingLaunchApplication.isBlank()) {
                        prerequisite = null;
                        pendingLaunchApplication = "";
                        pendingLaunchApplicationId = "";
                        pendingLaunchProcessId = 0;
                    }
                }
                case "desktop_session_targets" -> {
                    if ("desktop_session_targets".equals(prerequisite)
                            && !pendingLaunchApplication.isBlank()
                            && statusOf(step.output() == null ? NODES.nullNode()
                                    : step.output().path("status")) == ToolExecutionStatus.SUCCEEDED
                            && trustedDesktopReceipt(step, receipts, "OBSERVED") != null
                            && launchTargetDiscovered(pendingLaunchProcessId,
                                    pendingLaunchApplication, pendingLaunchApplicationId,
                                    step.output().path("rawOutput"))) {
                        prerequisite = "desktop_session_open";
                    }
                }
                case "desktop_session_open" -> {
                    prerequisite = null;
                    pendingLaunchApplication = "";
                    pendingLaunchApplicationId = "";
                    pendingLaunchProcessId = 0;
                    JsonNode receipt = trustedDesktopReceipt(step, receipts, "ACCEPTED");
                    if (receipt != null) {
                        JsonNode metadata = receipt.path("metadata");
                        String opened = metadata.path("sessionId").asText("");
                        String targetId = metadata.path("targetId").asText("");
                        if (!opened.isBlank() && !targetId.isBlank()
                                && targetId.equals(step.input().path("arguments")
                                        .path("targetId").asText(""))) {
                            openedSessions.add(opened);
                            sessionTargets.put(opened, targetId);
                            activeSession = opened;
                            prerequisite = "desktop_session_observe";
                        } else {
                            // Historical receipts did not bind a live native session.
                            // Re-discover instead of replaying a sessionId parsed from text.
                            prerequisite = "desktop_session_targets";
                        }
                    }
                }
                case "desktop_session_observe" -> {
                    JsonNode receipt = trustedDesktopReceipt(step, receipts, "OBSERVED");
                    JsonNode metadata = receipt == null ? NODES.nullNode() : receipt.path("metadata");
                    String observedSession = metadata.path("sessionId").asText("");
                    String observedTarget = metadata.path("targetId").asText("");
                    String observationId = metadata.path("observationId").asText("");
                    // A trusted frame also re-establishes a resumed session whose open
                    // happened in an earlier Run. The model-visible session text is not proof.
                    if (!sessionId.isBlank() && sessionId.equals(observedSession)
                            && !observedTarget.isBlank() && !observationId.isBlank()
                            && (sessionTargets.get(sessionId) == null
                                    || sessionTargets.get(sessionId).equals(observedTarget))) {
                        sessionTargets.put(sessionId, observedTarget);
                        openedSessions.add(sessionId);
                    }
                    if ("desktop_session_observe".equals(prerequisite)
                            && sessionId.equals(activeSession) && !sessionId.isBlank()
                            && sessionId.equals(observedSession)
                            && sessionTargets.getOrDefault(sessionId, "")
                                    .equals(observedTarget)
                            && !observationId.isBlank()) {
                        prerequisite = null;
                    }
                }
                case "desktop_session_click", "desktop_session_type", "desktop_session_key",
                        "desktop_session_scroll" -> {
                    String invocationId = step.input().path("invocationId").asText("");
                    boolean oldActionWithoutObservation = step.state() == AgentStep.State.COMPLETED
                            && step.output() != null && step.output().has("modelOutput")
                            && step.input().path("arguments").path("observationId")
                                    .asText("").isBlank();
                    if (!sessionId.isBlank() && (oldActionWithoutObservation
                            || openedSessions.contains(sessionId)
                                    && (trustedDesktopReceipt(step, receipts, "ACCEPTED") != null
                                        || mustObserve.getOrDefault(invocationId, Set.of())
                                                .contains(tool)))) {
                        activeSession = sessionId;
                        prerequisite = "desktop_session_observe";
                    }
                }
                case "desktop_session_close" -> {
                    if (trustedDesktopReceipt(step, receipts, "ACCEPTED") != null) {
                        openedSessions.remove(sessionId);
                        sessionTargets.remove(sessionId);
                        if (sessionId.equals(activeSession)) {
                            activeSession = "";
                            prerequisite = null;
                        }
                    }
                }
                default -> { }
            }
        }
        if (prerequisite != null) return prerequisite;
        String recovery = OnDemandApplicationRecovery.derive(persisted, events).requiredTool();
        return recovery.isBlank() ? null : recovery;
    }

    /** A rediscovered target must belong to the launched process when its PID is known. */
    static boolean launchTargetDiscovered(long processId, String requestedApplication,
            JsonNode data) {
        return launchTargetDiscovered(processId, requestedApplication, "", data);
    }

    static boolean launchTargetDiscovered(long processId, String requestedApplication,
            String applicationId, JsonNode data) {
        if (data == null || !data.path("schemaVersion").isInt()
                || data.path("schemaVersion").intValue() != 1
                || !data.path("kind").asText().equals("desktop.targets")
                || !data.path("targets").isArray()) return false;
        for (JsonNode target : data.path("targets")) {
            if (!target.isObject() || target.path("targetId").asText("").isBlank()
                    || !target.path("processId").isIntegralNumber()
                    || target.path("processId").asLong() <= 0
                    || !target.path("visible").isBoolean()
                    || !target.path("visible").booleanValue()
                    || target.path("systemSurface").asBoolean(false)) continue;
            if (processId > 0 && target.path("processId").asLong() == processId) return true;
            if (!applicationId.isBlank()
                    && applicationId.equalsIgnoreCase(target.path("applicationId").asText("")))
                return true;
            if (processId <= 0 && applicationId.isBlank() && !requestedApplication.isBlank()
                    && requestedApplication.equals(target.path("application").asText("")))
                return true;
        }
        return false;
    }

    private static ToolExecutionStatus statusOf(JsonNode value) {
        if (!value.isTextual()) return ToolExecutionStatus.UNKNOWN;
        try { return ToolExecutionStatus.valueOf(value.asText()); }
        catch (IllegalArgumentException invalid) { return ToolExecutionStatus.UNKNOWN; }
    }

    private static JsonNode trustedDesktopReceipt(AgentStep step,
            Map<String, JsonNode> receipts, String status) {
        if (step.state() != AgentStep.State.COMPLETED || step.output() == null) return null;
        String tool = step.input().path("tool").asText("");
        String invocationId = step.input().path("invocationId").asText("");
        JsonNode receipt = receipts.get(invocationId);
        return !invocationId.isBlank() && receipt != null
                && tool.equals(receipt.path("tool").asText(""))
                && status.equals(receipt.path("status").asText(""))
                ? receipt : null;
    }
}
