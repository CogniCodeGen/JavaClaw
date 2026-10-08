package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.ToolApprovalChallenge;
import com.javaclaw.framework.api.ToolApprovalGrant;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.framework.spi.ToolEffectPolicy;

import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

/** Replays durable tool and approval events into a recovered run control. */
final class PersistedRunStateRestorer {
    private static final String LAUNCH_TOOL = "desktop_session_launch_application";
    private PersistedRunStateRestorer() { }

    static ApprovalState restore(List<RunEventEnvelope> events, RunControl control) {
        EffectReplay replay = effectReplay(events, control);
        ToolApprovalChallenge pendingApproval = null;
        ApprovedToolInvocation pendingApprovedInvocation = null;
        for (RunEventEnvelope event : events) {
            restoreEffectEvent(event, control, replay);
            if (event.type().equals("core.tool.started")
                    && event.producer().equals("framework.core")) {
                String fingerprint = event.payload().path("fingerprint").asText("");
                control.restoreToolCall(fingerprint);
                if (pendingApprovedInvocation != null
                        && pendingApprovedInvocation.challenge().fingerprint().equals(fingerprint)) {
                    pendingApprovedInvocation = null;
                }
            }
            if (event.type().equals("core.task.repair_requested")
                    && event.producer().equals("framework.springai")) {
                control.enterTaskRepair();
            }
            if (event.type().equals("core.run.waiting_approval")) {
                pendingApproval = ToolApprovalChallenge.fromEventPayload(event.payload());
                pendingApprovedInvocation = null;
            } else if (event.type().equals("core.run.resumed") && pendingApproval != null) {
                JsonNode command = event.payload().path("command");
                String commandType = event.payload().path("commandType").asText("");
                String fingerprint = command.path("fingerprint").asText("");
                if (commandType.equals("tool.approval")
                        && command.path("approved").asBoolean(false)
                        && pendingApproval.fingerprint().equals(fingerprint)) {
                    ToolApprovalGrant grant = new ToolApprovalGrant(
                            pendingApproval.tool(), fingerprint, true,
                            command.path("humanApproved").asBoolean(false));
                    pendingApprovedInvocation = new ApprovedToolInvocation(
                            pendingApproval, grant);
                } else {
                    pendingApprovedInvocation = null;
                }
                pendingApproval = null;
            } else if (clearsApprovalState(event)) {
                pendingApproval = null;
                pendingApprovedInvocation = null;
            }
        }
        return new ApprovalState(pendingApproval, pendingApprovedInvocation);
    }

    /** 仅回放所属 Turn 的累计调用预算和重复调用检测状态。 */
    static void restoreToolCalls(List<RunEventEnvelope> events, RunControl control) {
        for (RunEventEnvelope event : events) {
            if (event.type().equals("core.tool.started")
                    && event.producer().equals("framework.core")) {
                control.restoreToolCall(event.payload().path("fingerprint").asText(""));
            }
        }
    }

    /**
     * Inherit only unresolved effects from an ordered journal of one previous Run.
     * Tool counters, task-repair mode, deadlines and approval grants belong to the new Run.
     * A terminal Run's cancellation is not evidence that its external effect did not occur.
     */
    static void restoreUnresolvedEffects(List<RunEventEnvelope> events, RunControl target) {
        restoreUnresolvedEffects(events, target, false);
    }

    static void restoreUnresolvedEffects(List<RunEventEnvelope> events, RunControl target,
            boolean allowCompletedBrowserNavigation) {
        java.util.Objects.requireNonNull(events, "events");
        java.util.Objects.requireNonNull(target, "target");
        if (events.isEmpty()) return;
        String sourceRunId = events.getFirst().runId();
        if (events.stream().anyMatch(event -> !event.runId().equals(sourceRunId)))
            throw new IllegalArgumentException("effect inheritance requires one Run's journal");
        RunControl effects = new RunControl(com.javaclaw.framework.api.RunBudget.UNBOUNDED,
                java.time.Clock.systemUTC());
        EffectReplay replay = effectReplay(events, effects);
        for (RunEventEnvelope event : events) restoreEffectEvent(event, effects, replay);
        // Only the caller's existing distinct-host-human-intent gate may release a settled no-op.
        // Ordinary restore, same-root AMEND and default inheritance keep their original fences.
        Set<String> settledNavigationNoOps = allowCompletedBrowserNavigation
                ? BrowserNavigationNoOpProof.completed(events) : Set.of();
        target.inheritUnresolvedEffects(effects, sourceRunId, allowCompletedBrowserNavigation,
                settledNavigationNoOps);
    }

    /** Shared parsing never imports usage or approval state. */
    private static EffectReplay effectReplay(List<RunEventEnvelope> events, RunControl control) {
        var identities = DesktopApplicationIdentityBindings.fromEvents(events);
        if (!events.isEmpty()) control.restoreDesktopApplicationBindings(identities, events.getFirst().runId());
        Map<String, DesktopObservationBaseline.Frame> observations = new HashMap<>();
        for (var frame : DesktopObservationBaseline.fromEvents(events))
            observations.put(frame.runId() + "\u0000" + frame.invocationId(), frame);
        return new EffectReplay(identities, new HashMap<>(), observations);
    }

    private static void restoreEffectEvent(RunEventEnvelope event, RunControl control, EffectReplay replay) {
        if (!event.producer().equals("framework.core")) return;
        JsonNode payload = event.payload();
        if (event.type().equals("core.tool.started")) {
            if (event.schemaVersion() != 1) return;
            String fingerprint = payload.path("fingerprint").asText("");
            if (fingerprint.isBlank())
                throw new IllegalStateException("persisted tool start lacks a fingerprint");
            String effectKey = payload.path("effectKey").asText(fingerprint);
            String invocationId = payload.path("invocationId").asText(fingerprint);
            String tool = payload.path("tool").asText("");
            ToolEffectPolicy policy;
            try { policy = ToolEffectPolicy.valueOf(payload.path("effectPolicy").asText("LEGACY")); }
            catch (IllegalArgumentException unknown) { policy = ToolEffectPolicy.LEGACY; }
            if (!payload.has("effectPolicy") && isDesktopInput(tool))
                policy = ToolEffectPolicy.OBSERVATION_GATED;
            String resourceKey = payload.path("resourceKey").asText("");
            if (LAUNCH_TOOL.equals(tool) && (policy == ToolEffectPolicy.LEGACY
                    || policy == ToolEffectPolicy.DISCOVERY_GATED)) {
                String persisted = policy == ToolEffectPolicy.DISCOVERY_GATED
                        ? persistedLaunchResource(resourceKey) : "";
                policy = ToolEffectPolicy.DISCOVERY_GATED;
                String canonical = replay.identities().canonicalIdentity(
                        payload.path("arguments").path("application").asText(""), event.runId(), event.sequence());
                resourceKey = !persisted.isBlank() ? persisted
                        : "desktop.application:" + (canonical.isBlank() ? "unknown" : canonical);
            }
            if (policy == ToolEffectPolicy.OBSERVATION_GATED && resourceKey.isBlank()
                    && isDesktopInput(tool)) resourceKey = "desktop:unknown";
            control.restoreEffectStart(invocationId, fingerprint, effectKey,
                    payload.path("idempotent").asBoolean(false), policy, resourceKey);
            if ("web_navigate".equals(tool) && payload.path("trustedBrowserNavigation").isBoolean()
                    && payload.path("trustedBrowserNavigation").booleanValue())
                control.restoreTrustedBrowserNavigationStart(invocationId);
            if (isDesktopInput(tool) && (!payload.has("trustedDesktopTool")
                    || payload.path("trustedDesktopTool").isBoolean()
                        && payload.path("trustedDesktopTool").booleanValue()))
                control.restoreDesktopInputStart(invocationId, payload.path("arguments"), event.timestamp());
            replay.starts().put(event.runId() + "\u0000" + invocationId, event);
        } else if (event.type().equals("core.tool.receipt")) {
            if (event.schemaVersion() != 1) return;
            String fingerprint = payload.path("fingerprint").asText("");
            String invocationId = payload.path("invocationId").asText(fingerprint);
            if (!invocationId.isBlank()) {
                RunEventEnvelope started = replay.starts().get(event.runId() + "\u0000" + invocationId);
                String startedTool = started == null ? "" : started.payload().path("tool").asText("");
                boolean launcher = LAUNCH_TOOL.equals(startedTool);
                if (launcher || LAUNCH_TOOL.equals(payload.path("tool").asText(""))) {
                    // An unrelated receipt with the same invocation token cannot bind a
                    // process identity or change the original launch's uncertainty state.
                    if (!launcher || !LAUNCH_TOOL.equals(payload.path("tool").asText(""))
                            || !"launch_application".equals(payload.path("operation").asText(""))) return;
                }
                try {
                    EffectReceiptV1.Status status = EffectReceiptV1.Status.valueOf(payload.path("status").asText(""));
                    if (launcher) {
                        String actual = DesktopApplicationIdentityBindings.normalize(
                                payload.path("metadata").path("applicationId").asText(""));
                        if (!actual.isBlank()) control.restoreEffectResource(invocationId, "desktop.application:" + actual);
                    }
                    control.restoreEffectReceipt(invocationId, status,
                            payload.path("metadata").path("delivery").asText(""));
                    control.restoreTrustedBrowserNavigationReceipt(invocationId,
                            payload.path("tool").asText(""), payload.path("operation").asText(""), status,
                            payload.path("metadata").path("delivery").asText(""),
                            payload.path("target").asText(""));
                    if (isDesktopInput(startedTool) && startedTool.equals(payload.path("tool").asText(""))) {
                        control.restoreDesktopInputReceiptTime(invocationId, event.timestamp());
                        Map<String, String> metadata = new HashMap<>();
                        payload.path("metadata").fields().forEachRemaining(entry ->
                                metadata.put(entry.getKey(), entry.getValue().asText("")));
                        try {
                            control.restoreDesktopInputReceipt(invocationId, started.payload().path("arguments"),
                                    new EffectReceiptV1(invocationId, payload.path("tool").asText(""),
                                            payload.path("operation").asText(""), payload.path("target").asText(""),
                                            status, java.time.Instant.parse(payload.path("observedAt")
                                                    .asText(event.timestamp().toString())),
                                            payload.path("evidenceRef").asText(""), "", "", metadata));
                        } catch (RuntimeException invalidInputProof) { /* keep the conservative start binding */ }
                    }
                    DesktopObservationBaseline.Frame observation = replay.observations().get(
                            event.runId() + "\u0000" + invocationId);
                    if (observation != null) control.restoreDesktopObservation(observation);
                } catch (IllegalArgumentException ignored) { /* unknown status cannot establish delivery */ }
            }
        } else if (event.type().equals("core.effect.reconciled")
                && event.schemaVersion() == 1
                && payload.path("outcome").asText("").equals("SATISFIED")) {
            String invocationId = payload.path("actionInvocationId").asText("");
            String targetId = payload.path("targetId").asText("");
            if (!invocationId.isBlank() && !targetId.isBlank())
                control.restoreEffectReconciliation(invocationId, "desktop:" + targetId);
        }
    }

    private record EffectReplay(DesktopApplicationIdentityBindings identities,
                               Map<String, RunEventEnvelope> starts,
                               Map<String, DesktopObservationBaseline.Frame> observations) { }

    private static String persistedLaunchResource(String resourceKey) {
        String prefix = "desktop.application:";
        if (!resourceKey.startsWith(prefix)) return "";
        String identity = DesktopApplicationIdentityBindings.normalize(resourceKey.substring(prefix.length()));
        if (identity.isBlank() || identity.equals("unknown") || identity.contains("..")
                || identity.indexOf('/') >= 0 || identity.indexOf('\\') >= 0 || identity.indexOf(':') >= 0
                || identity.codePoints().anyMatch(Character::isISOControl)
                || identity.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256) return "";
        return prefix + identity;
    }

    private static boolean isDesktopInput(String tool) {
        return Set.of("desktop_session_click", "desktop_session_type",
                "desktop_session_key", "desktop_session_scroll").contains(tool);
    }

    private static boolean clearsApprovalState(RunEventEnvelope event) {
        return switch (event.type()) {
            case "core.run.waiting_input", "core.run.completed", "core.run.failed",
                    "core.run.cancelled", "core.run.recovery_blocked" -> true;
            case "core.run.paused" -> !event.payload().path("reason")
                    .asText("").equals("KERNEL_SHUTDOWN");
            default -> false;
        };
    }

    record ApprovalState(ToolApprovalChallenge pendingApproval,
                         ApprovedToolInvocation pendingApprovedInvocation) { }
}
