package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.EffectReceiptV1;

import java.time.Instant;
import java.util.*;

/** Acceptance-only continuity of an observed native object; never session or input authority. */
final class DesktopReadOnlySessionRecovery {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> NATIVE_INPUTS = Set.of("desktop_session_click", "desktop_session_type",
            "desktop_session_key", "desktop_session_scroll", "desktop_session_launch_application", "desktop_session_close", "desktop_session_takeover", "web_navigate");

    private DesktopReadOnlySessionRecovery() { }

    static Optional<RunEventEnvelope> reopened(TaskContractV3 contract, TaskCriterionV3 criterion,
            RunEventEnvelope candidate, Map<String, String> previousBindings, List<RunEventEnvelope> history) {
        try { return derive(contract, criterion, candidate, previousBindings, history); }
        catch (Exception invalid) { return Optional.empty(); }
    }

    private static Optional<RunEventEnvelope> derive(TaskContractV3 contract, TaskCriterionV3 criterion,
            RunEventEnvelope candidate, Map<String, String> previousBindings, List<RunEventEnvelope> history)
            throws Exception {
        if (contract == null || !contract.reliable() || !contract.applicable()
                || contract.criteria().size() < 3 || !criterion.capabilityId().equals("desktop.observe")
                || !contract.criteria().getFirst().capabilityId().equals("desktop.open")) return Optional.empty();
        String application = contract.criteria().getFirst().target();
        int position = contract.criteria().indexOf(criterion);
        if (position < 2 || previousBindings.size() != position
                || !application.equals(DesktopApplicationIdentityBindings.normalize(application))
                || application.isBlank()) return Optional.empty();
        for (int i = 0; i < contract.criteria().size(); i++) {
            TaskCriterionV3 value = contract.criteria().get(i);
            if (!value.target().equals(application) || value.targetType() != CapabilityMetadata.TargetKind.DESKTOP_APPLICATION
                    || !value.capabilityId().equals(i == 0 ? "desktop.open" : "desktop.observe")
                    || value.requiredEvidence() != (i == 0 ? EffectReceiptV1.Status.ACCEPTED : EffectReceiptV1.Status.OBSERVED)
                    || i > 0 && value.requiredSubject().isBlank()) return Optional.empty();
        }
        List<RunEventEnvelope> events = history.stream().filter(event -> event.runId().equals(candidate.runId()))
                .sorted(Comparator.comparingLong(RunEventEnvelope::sequence)).toList();
        if (events.stream().noneMatch(event -> event.sequence() == 1 && host(event, "core.run.created", 1)
                    && event.payload().path("source").asText().equals("interaction"))) return Optional.empty();
        var frozenEvents = events.stream().filter(event -> event.producer().equals("framework.core")
                && Set.of("core.task.contract", "core.task.contract_revised").contains(event.type())).toList();
        if (frozenEvents.size() != 1 || frozenEvents.getFirst().schemaVersion() != 3) return Optional.empty();
        TaskContractV3 frozen = JSON.treeToValue(frozenEvents.getFirst().payload(), TaskContractV3.class);
        if (!frozen.source().equals("definition") || !frozen.criteria().equals(contract.criteria())
                || !frozen.reliable() || !frozen.applicable()) return Optional.empty();
        InteractionTask task = JSON.readValue(frozen.originalRequest(), InteractionTask.class);
        JsonNode data = task.necessaryData();
        if (task.mode() != InteractionMode.DESKTOP || !data.path("readOnly").isBoolean()
                || !data.path("readOnly").booleanValue() || !data.path("control").isBoolean()
                || data.path("control").booleanValue() || !data.path("applicationId").asText().equals(application)
                || !task.acceptanceCriterionIds().equals(frozen.criteria().stream().map(TaskCriterionV3::id).toList()))
            return Optional.empty();
        // Even an incomplete or NOT_SENT input attempt is outside this strictly read-only recovery.
        for (RunEventEnvelope event : events) {
            if (host(event, "core.tool.started", 1)
                    && (NATIVE_INPUTS.contains(event.payload().path("tool").asText())
                        || CrossModeBusinessFence.isBrowserBusinessInput(event.payload().path("tool").asText())
                        || event.payload().path("tool").asText().equals("desktop_session_open")
                            && event.payload().path("arguments").path("control").asBoolean(false)))
                return Optional.empty();
            if (host(event, "core.tool.receipt", 1)
                    && (event.payload().path("status").asText().equals("UNKNOWN")
                        || Set.of("MAYBE_SENT", "UNKNOWN").contains(event.payload().path("metadata").path("delivery").asText()))
                    && !completedHostControl(events, event)) return Optional.empty();
        }
        RunEventEnvelope originalOpen = byRef(events, previousBindings.get(contract.criteria().getFirst().id()));
        Triple oldOpen = opened(events, originalOpen, application);
        if (oldOpen == null) return Optional.empty();
        String oldSession = originalOpen.payload().path("metadata").path("sessionId").asText();
        JsonNode originalIdentity = null;
        long previousSequence = originalOpen.sequence();
        long previousCaptureBoundary = 0;
        Set<String> observations = new HashSet<>();
        for (int i = 1; i < position; i++) {
            TaskCriterionV3 previous = contract.criteria().get(i);
            RunEventEnvelope receipt = byRef(events, previousBindings.get(previous.id()));
            if (receipt == null || receipt.sequence() <= previousSequence
                    || triple(events, receipt, "SUCCEEDED") == null) return Optional.empty();
            JsonNode identity = InteractionStageVerifier.verifiedNativeObservation(events, receipt, previous.id(), JSON).orElse(null);
            if (identity == null || !identityMatchesReceipt(identity, receipt, application)) return Optional.empty();
            if (originalIdentity == null) {
                if (!oldSession.equals(identity.path("contextId").asText())
                        || !oldOpen.complete().payload().path("output").path("target").path("targetId").asText()
                            .equals(identity.path("targetId").asText())) return Optional.empty();
                if (!sameTarget(oldOpen.complete().payload().path("output").path("target"), identity, application))
                    return Optional.empty();
                originalIdentity = identity;
            } else if (!sameObject(originalIdentity, identity)) return Optional.empty();
            if (!observations.add(identity.path("observationId").asText())) return Optional.empty();
            previousCaptureBoundary = Math.max(previousCaptureBoundary,
                    Math.max(observedAt(receipt), identity.path("capturedAtMillis").asLong()));
            previousSequence = receipt.sequence();
        }
        JsonNode currentIdentity = InteractionStageVerifier.verifiedNativeObservation(events, candidate, criterion.id(), JSON).orElse(null);
        if (originalIdentity == null || currentIdentity == null || candidate.sequence() <= previousSequence
                || !identityMatchesReceipt(currentIdentity, candidate, application)
                || !sameObject(originalIdentity, currentIdentity)
                || oldSession.equals(currentIdentity.path("contextId").asText())
                || observations.contains(currentIdentity.path("observationId").asText())
                || currentIdentity.path("capturedAtMillis").asLong() <= previousCaptureBoundary)
            return Optional.empty();
        RunEventEnvelope resumed = events.stream().filter(event -> host(event, "core.run.resumed", 1)
                && event.sequence() < candidate.sequence()).max(Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
        if (resumed == null || !exactAnsweredShutdown(events, resumed, previousBindings, contract, task)
                || events.stream().anyMatch(event -> event.sequence() > resumed.sequence()
                    && event.sequence() < candidate.sequence() && event.producer().equals("framework.core")
                    && Set.of("core.run.waiting_input", "core.run.waiting_approval", "core.run.waiting_child",
                        "core.run.waiting_event", "core.run.paused", "core.task.contract_revised").contains(event.type())))
            return Optional.empty();
        String newSession = currentIdentity.path("contextId").asText();
        for (RunEventEnvelope receipt : events) {
            if (receipt.sequence() <= resumed.sequence() || receipt.sequence() >= candidate.sequence()
                    || !host(receipt, "core.tool.receipt", 1)
                    || !receipt.payload().path("metadata").path("sessionId").asText().equals(newSession)) continue;
            Triple opened = opened(events, receipt, application);
            if (opened == null || opened.start().sequence() <= resumed.sequence()
                    || currentIdentity.path("capturedAtMillis").asLong() <= observedAt(receipt)
                    || !sameTarget(opened.complete().payload().path("output").path("target"), currentIdentity, application)) continue;
            Triple observed = triple(events, candidate, "SUCCEEDED");
            if (observed == null || observed.start().sequence() <= receipt.sequence()) continue;
            var discoveryCriterion = new TaskCriterionV3("host-readonly-recovery-discovery", "Validate host discovery",
                    "desktop.targets", CapabilityMetadata.TargetKind.RESOURCE, "desktop", EffectReceiptV1.Status.OBSERVED, "");
            for (RunEventEnvelope discovery : events) {
                if (discovery.sequence() <= resumed.sequence() || discovery.sequence() >= opened.start().sequence()
                        || !DesktopDiscoveryEvidence.matches(discoveryCriterion, discovery, events)) continue;
                Triple discovered = triple(events, discovery, "SUCCEEDED");
                if (discovered == null || discovered.start().sequence() <= resumed.sequence()) continue;
                for (JsonNode target : discovered.complete().payload().path("output").path("targets"))
                    if (sameTarget(target, currentIdentity, application)) return Optional.of(receipt);
            }
        }
        return Optional.empty();
    }

    private static boolean exactAnsweredShutdown(List<RunEventEnvelope> events, RunEventEnvelope resumed,
            Map<String, String> previousBindings, TaskContractV3 contract, InteractionTask task) {
        JsonNode command = resumed.payload().path("command");
        long sequence = command.path("interactionChildEventSequence").asLong(-1);
        if (!resumed.payload().path("commandType").asText().equals("input")
                || !command.path("interactionChildEventSequence").isIntegralNumber() || sequence < 1
                || command.path("text").asText().isBlank() || command.path("interactionCommandId").asText().isBlank()
                || !command.path("interactionTaskId").asText().equals(task.taskId())
                || !command.path("interactionRevision").isIntegralNumber()
                || command.path("interactionRevision").asLong() != task.revision())
            return false;
        var before = events.stream().filter(event -> event.sequence() < resumed.sequence()).toList();
        RunEventEnvelope question = InteractionAnswerChallenge.current(new RunId(resumed.runId()), RunState.PAUSED, before).orElse(null);
        if (question == null || question.sequence() != sequence || !host(question, "core.run.waiting_input", 1)) return false;
        boolean shutdown = before.stream().anyMatch(event -> event.sequence() > sequence
                && host(event, "core.run.paused", 1) && event.payload().path("reason").asText().equals("KERNEL_SHUTDOWN"));
        if (!shutdown) return false;
        RunEventEnvelope firstObservation = byRef(events, previousBindings.get(contract.criteria().get(1).id()));
        if (firstObservation == null || firstObservation.sequence() >= question.sequence()) return false;
        JsonNode context = question.payload().path("output");
        for (RunEventEnvelope receipt : before) {
            if (receipt.sequence() <= firstObservation.sequence() || receipt.sequence() >= question.sequence()
                    || !receipt.payload().path("tool").asText().equals("ask_user_clarification")) continue;
            Triple clarify = triple(events, receipt, "PENDING");
            if (clarify == null || !receipt.payload().path("status").asText().equals("OBSERVED")
                    || !receipt.payload().path("metadata").path("delivery").asText().equals("NOT_SENT")
                    || !clarify.complete().payload().path("waitingInput").isBoolean()
                    || !clarify.complete().payload().path("waitingInput").booleanValue()
                    || !context.equals(clarify.complete().payload().path("output"))) continue;
            JsonNode arguments = clarify.start().payload().path("arguments");
            var expected = JsonNodeFactory.instance.objectNode().put("kind", "clarify_request");
            expected.putObject("payload").put("reason", arguments.path("reason").asText().trim())
                    .put("question", arguments.path("question").asText().trim());
            if (expected.equals(context)) return true;
        }
        return false;
    }

    private static Triple opened(List<RunEventEnvelope> events, RunEventEnvelope receipt, String application) {
        Triple opened = triple(events, receipt, "SUCCEEDED");
        if (opened == null || !receipt.payload().path("tool").asText().equals("desktop_session_open")
                || !receipt.payload().path("operation").asText().equals("open")
                || !receipt.payload().path("status").asText().equals("ACCEPTED")) return null;
        JsonNode arguments = opened.start().payload().path("arguments"), raw = opened.complete().payload().path("output");
        JsonNode metadata = receipt.payload().path("metadata");
        if (!arguments.path("control").isBoolean() || arguments.path("control").booleanValue()
                || !raw.path("controlGranted").isBoolean() || raw.path("controlGranted").booleanValue()
                || !metadata.path("controlRequested").asText().equals("false")
                || !metadata.path("controlGranted").asText().equals("false")
                || raw.path("schemaVersion").asInt() != 1 || !raw.path("protocol").asText().equals("computer-use")
                || !raw.path("kind").asText().equals("desktop.session") || raw.path("sessionId").asText().isBlank()
                || !raw.path("sessionId").equals(metadata.path("sessionId"))
                || !arguments.path("targetId").equals(raw.path("target").path("targetId"))
                || !arguments.path("targetId").equals(metadata.path("targetId"))
                || !raw.path("target").path("applicationId").asText().equals(application)
                || !metadata.path("applicationId").asText().equals(application)) return null;
        return opened;
    }

    static boolean identityMatchesReceipt(JsonNode identity, RunEventEnvelope receipt, String application) {
        JsonNode metadata = receipt.payload().path("metadata");
        if (!identity.path("applicationId").asText().equals(application)
                || !metadata.path("applicationId").asText().equals(application)
                || !identity.path("targetId").equals(metadata.path("targetId"))
                || !identity.path("contextId").equals(metadata.path("sessionId"))
                || !identity.path("observationId").equals(metadata.path("observationId"))
                || identity.path("generation").asLong(-1) != metadata.path("windowGeneration").asLong(-2)) return false;
        return identity.path("runtimeId").asText().matches("desktop:process:(macos|windows):[1-9][0-9]*:[1-9][0-9]{0,19}")
                && !identity.path("surfaceId").asText().isBlank();
    }

    private static boolean sameObject(JsonNode first, JsonNode second) {
        for (String field : List.of("runtimeId", "surfaceId", "targetId", "applicationId"))
            if (!first.path(field).equals(second.path(field))) return false;
        return first.path("generation").asLong() == second.path("generation").asLong();
    }

    static boolean sameTarget(JsonNode target, JsonNode identity, String application) {
        String[] runtime = identity.path("runtimeId").asText().split(":");
        return runtime.length == 5 && target.path("providerId").asText().equals(runtime[2])
                && target.path("processId").isIntegralNumber() && target.path("processId").canConvertToLong()
                && target.path("processId").asLong() > 0 && Long.toString(target.path("processId").asLong()).equals(runtime[3])
                && target.path("targetId").equals(identity.path("targetId"))
                && target.path("applicationId").asText().equals(application)
                && target.path("visible").isBoolean() && target.path("visible").booleanValue()
                && target.path("minimized").isBoolean() && !target.path("minimized").booleanValue()
                && target.path("systemSurface").isBoolean() && !target.path("systemSurface").booleanValue();
    }

    /** Successful host controls have no physical effect adapter; their UNKNOWN is not input delivery. */
    static boolean completedHostControl(List<RunEventEnvelope> events, RunEventEnvelope receipt) {
        JsonNode metadata = receipt.payload().path("metadata");
        if (!metadata.path("delivery").asText().isEmpty() || !metadata.path("effect").asText().isEmpty()) return false;
        Triple control = triple(events, receipt, "SUCCEEDED", false);
        if (control == null || !control.start().payload().path("idempotent").isBoolean()
                || !control.start().payload().path("idempotent").booleanValue()) return false;
        String tool = receipt.payload().path("tool").asText();
        if (tool.equals("framework_tool_catalog")) return control.start().payload().path("trustedToolCatalog").isBoolean()
                && control.start().payload().path("trustedToolCatalog").booleanValue();
        if (!tool.equals(InteractionExecutionPolicy.SELECT_MODE_TOOL)) return false;
        var selected = events.stream().filter(event -> host(event, InteractionExecutionPolicy.MODE_SELECTED_EVENT, 1)
                && event.sequence() > control.start().sequence() && event.sequence() < control.complete().sequence()
                && event.payload().path("invocationId").asText().equals(receipt.payload().path("invocationId").asText())).toList();
        return selected.size() == 1 && selected.getFirst().payload().equals(control.complete().payload().path("output"))
                && selected.getFirst().payload().path("mode").asText().equals("DESKTOP")
                && selected.getFirst().payload().path("activeMode").asText().equals("DESKTOP")
                && selected.getFirst().payload().path("modeVersion").isIntegralNumber()
                && selected.getFirst().payload().path("modeVersion").canConvertToLong()
                && selected.getFirst().payload().path("modeVersion").asLong() > 0
                && selected.getFirst().payload().path("requiresFreshObservation").isBoolean()
                && selected.getFirst().payload().path("requiresFreshObservation").booleanValue()
                && control.start().payload().path("arguments").path("mode").asText().equals("DESKTOP");
    }

    static Triple triple(List<RunEventEnvelope> events, RunEventEnvelope receipt, String completedStatus) {
        return triple(events, receipt, completedStatus, true);
    }

    private static Triple triple(List<RunEventEnvelope> events, RunEventEnvelope receipt, String completedStatus,
            boolean requireNativeHost) {
        if (receipt == null || !host(receipt, "core.tool.receipt", 1)) return null;
        String invocation = receipt.payload().path("invocationId").asText(), tool = receipt.payload().path("tool").asText();
        if (invocation.isBlank()) return null;
        var stages = events.stream().filter(event -> Set.of("core.tool.started", "core.tool.completed", "core.tool.receipt")
                .contains(event.type()) && event.payload().path("invocationId").asText().equals(invocation)).toList();
        if (stages.size() != 3 || !stages.get(2).equals(receipt)) return null;
        var start = stages.get(0); var complete = stages.get(1);
        if (!host(start, "core.tool.started", 1) || !host(complete, "core.tool.completed", 2)
                || requireNativeHost && (!start.payload().path("trustedDesktopTool").isBoolean()
                    || !start.payload().path("trustedDesktopTool").booleanValue())
                || !complete.payload().path("status").asText().equals(completedStatus)
                || !complete.payload().path("errorCode").asText("").isBlank()
                || !start.payload().path("tool").asText().equals(tool) || !complete.payload().path("tool").asText().equals(tool)
                || start.sequence() >= complete.sequence() || complete.sequence() >= receipt.sequence()
                || start.timestamp().isAfter(complete.timestamp()) || complete.timestamp().isAfter(receipt.timestamp())
                || !receipt.payload().path("evidenceRef").asText().equals("core.tool.completed:" + receipt.runId() + ":" + invocation)) return null;
        String fingerprint = ToolInvocationFingerprint.create(tool, start.payload().path("arguments"));
        if (!fingerprint.equals(start.payload().path("fingerprint").asText())
                || !fingerprint.equals(receipt.payload().path("fingerprint").asText())) return null;
        long observed = observedAt(receipt);
        long upperBound = completedStatus.equals("PENDING") || !requireNativeHost ? receipt.timestamp().toEpochMilli()
                : complete.timestamp().toEpochMilli();
        return observed < start.timestamp().toEpochMilli() || observed > upperBound
                ? null : new Triple(start, complete, receipt);
    }

    private static RunEventEnvelope byRef(List<RunEventEnvelope> events, String ref) {
        if (ref == null || ref.isBlank()) return null;
        var matches = events.stream().filter(event -> host(event, "core.tool.receipt", 1)
                && event.payload().path("evidenceRef").asText().equals(ref)).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static long observedAt(RunEventEnvelope receipt) {
        return Instant.parse(receipt.payload().path("observedAt").asText()).toEpochMilli();
    }

    private static boolean host(RunEventEnvelope event, String type, int schema) {
        return event.type().equals(type) && event.schemaVersion() == schema && event.producer().equals("framework.core");
    }

    record Triple(RunEventEnvelope start, RunEventEnvelope complete, RunEventEnvelope receipt) { }
}
