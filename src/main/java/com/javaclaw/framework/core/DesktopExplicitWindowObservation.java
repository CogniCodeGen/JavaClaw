package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.EffectReceiptV1;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Read-only acceptance of an explicitly opened candidate, never input/session authority. */
final class DesktopExplicitWindowObservation {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> READ_TOOLS = Set.of("desktop_session_probe", "desktop_session_applications",
            "desktop_session_targets", "desktop_session_window_candidates", "desktop_session_open", "desktop_session_observe");

    private DesktopExplicitWindowObservation() { }

    static Optional<RunEventEnvelope> opened(TaskContractV3 contract, TaskCriterionV3 criterion,
            RunEventEnvelope candidate, Map<String, String> previousBindings, List<RunEventEnvelope> history) {
        try { return derive(contract, criterion, candidate, previousBindings, history); }
        catch (Exception invalid) { return Optional.empty(); }
    }

    private static Optional<RunEventEnvelope> derive(TaskContractV3 contract, TaskCriterionV3 criterion,
            RunEventEnvelope candidate, Map<String, String> previousBindings, List<RunEventEnvelope> history) throws Exception {
        if (contract == null || !contract.reliable() || !contract.applicable()
                || !criterion.capabilityId().equals("desktop.observe") || criterion.requiredSubject().isBlank()
                || !contract.criteria().getFirst().capabilityId().equals("desktop.open")) return Optional.empty();
        int position = contract.criteria().indexOf(criterion);
        String application = contract.criteria().getFirst().target();
        if (position < 2 || previousBindings.size() != position || application.isBlank() || application.length() > 256
                || !application.equals(application.strip()) || application.codePoints().anyMatch(Character::isISOControl))
            return Optional.empty();
        for (TaskCriterionV3 value : contract.criteria()) {
            if (value.capabilityId().equals("desktop.window_candidates")) {
                if (value.targetType() != CapabilityMetadata.TargetKind.RESOURCE || !value.target().equals("desktop")
                        || value.requiredEvidence() != EffectReceiptV1.Status.OBSERVED || !value.requiredSubject().isBlank())
                    return Optional.empty();
            } else if (!value.target().equals(application) || value.targetType() != CapabilityMetadata.TargetKind.DESKTOP_APPLICATION
                    || !Set.of("desktop.open", "desktop.observe").contains(value.capabilityId())
                    || value.requiredEvidence() != (value.capabilityId().equals("desktop.open")
                        ? EffectReceiptV1.Status.ACCEPTED : EffectReceiptV1.Status.OBSERVED)
                    || value.capabilityId().equals("desktop.observe") && value.requiredSubject().isBlank()) return Optional.empty();
        }
        List<RunEventEnvelope> events = history.stream().filter(event -> event.runId().equals(candidate.runId()))
                .sorted(Comparator.comparingLong(RunEventEnvelope::sequence)).toList();
        if (events.stream().noneMatch(event -> event.sequence() == 1 && host(event, "core.run.created", 1)
                && event.payload().path("source").asText().equals("interaction"))) return Optional.empty();
        var frozenEvents = events.stream().filter(event -> event.producer().equals("framework.core")
                && Set.of("core.task.contract", "core.task.contract_revised").contains(event.type())).toList();
        if (frozenEvents.size() != 1 || frozenEvents.getFirst().schemaVersion() != 3) return Optional.empty();
        var frozen = JSON.treeToValue(frozenEvents.getFirst().payload(), TaskContractV3.class);
        if (!frozen.source().equals("definition") || !frozen.reliable() || !frozen.applicable()
                || !frozen.criteria().equals(contract.criteria())) return Optional.empty();
        InteractionTask task = JSON.readValue(frozen.originalRequest(), InteractionTask.class);
        if (task.mode() != InteractionMode.DESKTOP
                || !task.acceptanceCriterionIds().equals(frozen.criteria().stream().map(TaskCriterionV3::id).toList()))
            return Optional.empty();
        // This narrow path cannot reconcile any input, including a failed or uncertain attempt.
        for (RunEventEnvelope event : events) {
            if (host(event, "core.tool.started", 1)) {
                String tool = event.payload().path("tool").asText();
                if (!READ_TOOLS.contains(tool) && !Set.of("framework_tool_catalog", InteractionExecutionPolicy.SELECT_MODE_TOOL).contains(tool))
                    return Optional.empty();
                if (READ_TOOLS.contains(tool) && (!event.payload().path("trustedDesktopTool").isBoolean()
                        || !event.payload().path("trustedDesktopTool").booleanValue())) return Optional.empty();
            }
            if (host(event, "core.tool.receipt", 1)
                    && (event.payload().path("status").asText().equals("UNKNOWN")
                        || Set.of("MAYBE_SENT", "UNKNOWN").contains(event.payload().path("metadata").path("delivery").asText()))
                    && !DesktopReadOnlySessionRecovery.completedHostControl(events, event)) return Optional.empty();
        }
        var current = DesktopReadOnlySessionRecovery.triple(events, candidate, "SUCCEEDED");
        JsonNode identity = InteractionStageVerifier.verifiedNativeObservation(events, candidate, criterion.id(), JSON).orElse(null);
        if (current == null || identity == null
                || !DesktopReadOnlySessionRecovery.identityMatchesReceipt(identity, candidate, application)) return Optional.empty();
        List<DesktopObservationBaseline.Frame> frames = DesktopObservationBaseline.fromEvents(events);
        RunEventEnvelope previousView = null;
        long boundary = 0, capturedBoundary = 0;
        for (int index = 0; index < position; index++) {
            TaskCriterionV3 previous = contract.criteria().get(index);
            RunEventEnvelope receipt = byRef(events, previousBindings.get(previous.id()));
            if (receipt == null || receipt.sequence() <= boundary || receipt.sequence() >= candidate.sequence()) return Optional.empty();
            boundary = receipt.sequence();
            capturedBoundary = Math.max(capturedBoundary, observedAt(receipt));
            if (previous.capabilityId().equals("desktop.observe")) {
                var observed = DesktopReadOnlySessionRecovery.triple(events, receipt, "SUCCEEDED");
                var frame = frames.stream().filter(value -> value.invocationId().equals(receipt.payload().path("invocationId").asText())).findFirst().orElse(null);
                if (observed == null || frame == null || !receipt.payload().path("metadata").path("applicationId").asText().equals(application)
                        || !DesktopConditionProof.matches(previous.id(), previous.requiredSubject(), receipt.payload().path("metadata"))
                        || frame.observationId().equals(identity.path("observationId").asText())) return Optional.empty();
                capturedBoundary = Math.max(capturedBoundary, frame.capturedAtMillis());
                previousView = receipt;
            }
        }
        if (previousView == null || identity.path("capturedAtMillis").asLong() <= capturedBoundary) return Optional.empty();
        String previousSession = previousView.payload().path("metadata").path("sessionId").asText();
        String previousTarget = previousView.payload().path("metadata").path("targetId").asText();
        if (previousSession.equals(identity.path("contextId").asText())
                || previousTarget.equals(identity.path("targetId").asText())) return Optional.empty();
        for (RunEventEnvelope receipt : events) {
            if (receipt.sequence() <= boundary || receipt.sequence() >= current.start().sequence()) continue;
            var opened = explicitOpen(events, receipt, identity, application);
            if (opened == null || opened.start().sequence() <= boundary
                    || identity.path("capturedAtMillis").asLong() <= observedAt(receipt)) continue;
            for (RunEventEnvelope discovery : events) {
                if (discovery.sequence() <= previousView.sequence() || discovery.sequence() >= opened.start().sequence()) continue;
                if (candidateList(events, discovery, previousSession, previousTarget, identity, application)) return Optional.of(receipt);
            }
        }
        return Optional.empty();
    }

    private static DesktopReadOnlySessionRecovery.Triple explicitOpen(List<RunEventEnvelope> events,
            RunEventEnvelope receipt, JsonNode identity, String application) {
        var opened = DesktopReadOnlySessionRecovery.triple(events, receipt, "SUCCEEDED");
        if (opened == null || !receipt.payload().path("tool").asText().equals("desktop_session_open")
                || !receipt.payload().path("operation").asText().equals("open")
                || !receipt.payload().path("status").asText().equals("ACCEPTED")) return null;
        JsonNode args = opened.start().payload().path("arguments"), raw = opened.complete().payload().path("output");
        JsonNode metadata = receipt.payload().path("metadata");
        if (!args.path("control").isBoolean() || !raw.path("controlGranted").isBoolean()
                || !metadata.path("controlRequested").asText().equals(Boolean.toString(args.path("control").booleanValue()))
                || !metadata.path("controlGranted").asText().equals(Boolean.toString(raw.path("controlGranted").booleanValue()))
                || !raw.path("schemaVersion").isIntegralNumber() || raw.path("schemaVersion").asInt() != 1
                || !raw.path("protocol").asText().equals("computer-use") || !raw.path("kind").asText().equals("desktop.session")
                || !raw.path("sessionId").asText().equals(identity.path("contextId").asText())
                || !metadata.path("sessionId").equals(raw.path("sessionId"))
                || !metadata.path("applicationId").asText().equals(application)
                || !metadata.path("targetId").equals(identity.path("targetId"))
                || !args.path("targetId").equals(identity.path("targetId"))
                || !DesktopReadOnlySessionRecovery.sameTarget(raw.path("target"), identity, application)) return null;
        return opened;
    }

    private static boolean candidateList(List<RunEventEnvelope> events, RunEventEnvelope receipt,
            String previousSession, String previousTarget, JsonNode identity, String application) throws Exception {
        var discovered = DesktopReadOnlySessionRecovery.triple(events, receipt, "SUCCEEDED");
        if (discovered == null || !receipt.payload().path("tool").asText().equals("desktop_session_window_candidates")
                || !receipt.payload().path("operation").asText().equals("window_candidates")
                || !receipt.payload().path("status").asText().equals("OBSERVED")) return false;
        JsonNode raw = discovered.complete().payload().path("output"), metadata = receipt.payload().path("metadata");
        if (!raw.path("schemaVersion").isIntegralNumber() || raw.path("schemaVersion").asInt() != 1
                || !raw.path("protocol").asText().equals("computer-use") || !raw.path("kind").asText().equals("desktop.window_candidates")
                || !raw.path("sessionId").asText().equals(previousSession)
                || !discovered.start().payload().path("arguments").path("sessionId").asText().equals(previousSession)
                || !metadata.path("sessionId").asText().equals(previousSession)
                || !raw.path("sourceTargetId").asText().equals(previousTarget)
                || !raw.path("inventoryAvailable").isBoolean() || !raw.path("inventoryAvailable").booleanValue()
                || !raw.path("truncated").isBoolean() || raw.path("truncated").booleanValue()
                || !raw.path("inputAuthority").isBoolean() || raw.path("inputAuthority").booleanValue()
                || !raw.path("freshObservation").isBoolean() || raw.path("freshObservation").booleanValue()
                || !raw.path("candidates").isArray() || raw.path("candidates").size() > 512
                || !metadata.path("delivery").asText().equals("NOT_SENT") || !metadata.path("effect").asText().equals("NONE")
                || !metadata.path("discoveryDigest").asText().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.toString().getBytes(StandardCharsets.UTF_8))))
                || !raw.path("observedAtMillis").isIntegralNumber()
                || raw.path("observedAtMillis").asLong() < discovered.start().timestamp().toEpochMilli()
                || raw.path("observedAtMillis").asLong() > discovered.complete().timestamp().toEpochMilli()) return false;
        int selected = 0, destination = 0;
        Set<String> targets = new HashSet<>();
        for (JsonNode window : raw.path("candidates")) {
            if (!window.path("runtimeId").equals(identity.path("runtimeId"))
                    || !window.path("applicationId").asText().equals(application)
                    || !targets.add(window.path("targetId").asText())) return false;
            if (window.path("selected").isBoolean() && window.path("selected").booleanValue()) {
                var sourceIdentity = identity.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) sourceIdentity).put("targetId", previousTarget);
                if (!window.path("targetId").asText().equals(previousTarget)
                        || !window.path("surfaceId").asText().equals(previousTarget)
                        || !DesktopReadOnlySessionRecovery.sameTarget(window, sourceIdentity, application)) return false;
                selected++;
            }
            if (window.path("targetId").equals(identity.path("targetId"))
                    && window.path("surfaceId").equals(identity.path("surfaceId"))
                    && DesktopReadOnlySessionRecovery.sameTarget(window, identity, application)) destination++;
        }
        return selected == 1 && destination == 1;
    }

    private static RunEventEnvelope byRef(List<RunEventEnvelope> events, String ref) {
        var matches = events.stream().filter(event -> host(event, "core.tool.receipt", 1)
                && event.payload().path("evidenceRef").asText().equals(ref)).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static long observedAt(RunEventEnvelope event) {
        return Instant.parse(event.payload().path("observedAt").asText()).toEpochMilli();
    }

    private static boolean host(RunEventEnvelope event, String type, int schema) {
        return event.type().equals(type) && event.schemaVersion() == schema && event.producer().equals("framework.core");
    }
}
