package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.CapabilityMetadata;
import com.javaclaw.framework.api.ModelDecisionV1;
import com.javaclaw.framework.api.TaskContractV1;
import com.javaclaw.framework.api.TaskContractV2;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.api.TaskCriterion;
import com.javaclaw.framework.api.TaskCriterionV3;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.api.TaskResult;
import com.javaclaw.framework.spi.EffectReceiptV1;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.net.URI;
import java.nio.file.Path;
import com.javaclaw.util.ProjectAccessPolicy;

/** Accepts only trusted tool-boundary receipts that match each contract condition. */
public final class TaskResultEvaluator {
    private TaskResultEvaluator() { }

    /** V3 accepts only capabilities registered by the host and exact typed receipts. */
    public static TaskResult evaluateV3(TaskContractV3 contract,
            List<RunEventEnvelope> events, String stopReason,
            TrustedCapabilityRegistry capabilities) {
        if (contract == null) return TaskResult.unverified("TASK_CONTRACT_MISSING");
        if (!contract.reliable() || !contract.desktopObservationSubjectsValid())
            return TaskResult.unverified("TASK_CONTRACT_UNRELIABLE");
        if (!contract.applicable()) {
            if (events.stream().anyMatch(event -> isBusinessToolStart(
                    event.type(), event.producer(), event.payload()))) {
                return TaskResult.unverified("TOOL_USED_AFTER_NOT_APPLICABLE_CLASSIFICATION");
            }
            return TaskResult.notApplicable();
        }
        if (contract.criteria().isEmpty()
                || contract.criteria().stream().anyMatch(c -> !capabilities.supports(c))) {
            return TaskResult.unverified("TASK_CONTRACT_UNRELIABLE");
        }
        DesktopApplicationIdentityBindings identities = DesktopApplicationIdentityBindings.fromEvents(events);
        List<RunEventEnvelope> receipts = events.stream()
                .filter(event -> event.type().equals("core.tool.receipt")
                        && event.schemaVersion() == 1
                        && event.producer().equals("framework.core"))
                .toList();
        List<TaskCriterionV3> desktop = contract.criteria().stream()
                .filter(c -> capabilities.find(c.capabilityId())
                        .map(d -> d.verifierPolicy()
                                == TrustedCapabilityRegistry.VerifierPolicy.DESKTOP_LINKED_FRAME)
                        .orElse(false)).toList();
        List<RunEventEnvelope> desktopEvents = events.stream()
                .filter(event -> !event.type().equals("core.tool.receipt")
                        || event.payload().path("tool").asText("").equals("desktop_session_applications")
                        || capabilities.forReceipt(event.payload().path("tool").asText(""),
                                event.payload().path("operation").asText(""))
                                .filter(d -> d.verifierPolicy()
                                        == TrustedCapabilityRegistry.VerifierPolicy.DESKTOP_LINKED_FRAME)
                                .isPresent())
                .toList();
        TaskContractV2 viewContract = desktop.isEmpty() ? null : desktopContract(contract, capabilities);
        TaskResult view = viewContract == null ? null
                : evaluateV2(viewContract, desktopEvents, stopReason, capabilities);
        List<String> reconciledClicks = view == null
                ? List.of() : verifiedActionEvidence(viewContract, desktopEvents).stream()
                        .map(VerifiedActionEvidence::invocationId).toList();
        Set<String> linkedEvidence = view == null ? Set.of() : Set.copyOf(view.evidenceRefs());
        List<String> unmet = new ArrayList<>();
        List<TaskCriterionV3> satisfied = new ArrayList<>();
        LinkedHashMap<String, String> evidenceByCriterion = new LinkedHashMap<>();
        int cursor = -1;
        for (TaskCriterionV3 criterion : contract.criteria()) {
            int matched = -1;
            for (int index = cursor + 1; index < receipts.size(); index++) {
                RunEventEnvelope event = receipts.get(index);
                Receipt receipt = receipt(event).orElse(null);
                if (receipt == null && criterion.capabilityId().equals("desktop.click")
                        && event.payload().path("status").asText("").equals("UNKNOWN")
                        && reconciledClicks.contains(
                                event.payload().path("invocationId").asText(""))) {
                    var payload = event.payload();
                    receipt = new Receipt(payload.path("invocationId").asText(""), "click",
                            payload.path("target").asText(""), "ACCEPTED",
                            payload.path("evidenceRef").asText(""),
                            payload.path("subject").asText(""));
                }
                if (receipt != null && matchesV3(criterion, receipt, event, capabilities, identities)) {
                    if (matched < 0) matched = index;
                    // A later valid frame must win over an earlier matching receipt
                    // invalidated by a dispatched action on the same window.
                    if (!desktop.contains(criterion)
                            || linkedEvidence.contains(event.payload().path("evidenceRef").asText())) {
                        matched = index;
                        break;
                    }
                }
            }
            if (matched < 0) {
                unmet.add(criterion.description() + " [" + criterion.capabilityId()
                        + ":" + criterion.target() + "/" + criterion.requiredEvidence() + "]");
            } else {
                cursor = matched;
                satisfied.add(criterion);
                evidenceByCriterion.put(criterion.id(),
                        receipts.get(matched).payload().path("evidenceRef").asText());
            }
        }
        // Desktop proof has stronger session, frame and post-action conditions than an
        // individual receipt. Preserve each condition proven by the linked-frame verifier.
        if (!desktop.isEmpty()) {
            for (TaskCriterionV3 criterion : desktop) {
                String evidenceRef = evidenceByCriterion.get(criterion.id());
                if (evidenceRef != null && !linkedEvidence.contains(evidenceRef)) {
                    unmet.add(criterion.description() + " [DESKTOP_POST_ACTION_PROOF]");
                    satisfied.removeIf(value -> value.id().equals(criterion.id()));
                    evidenceByCriterion.remove(criterion.id());
                }
            }
        }
        List<String> completed = satisfied.stream().map(TaskCriterionV3::description).toList();
        LinkedHashSet<String> evidence = new LinkedHashSet<>(evidenceByCriterion.values());
        if (unmet.isEmpty()) return new TaskResult(TaskOutcome.VERIFIED_COMPLETE,
                List.of(), "", List.copyOf(evidence), completed);
        String reason = stopReason == null || stopReason.isBlank()
                ? "MISSING_TRUSTED_RECEIPT" : stopReason.strip();
        return new TaskResult(completed.isEmpty() ? TaskOutcome.UNVERIFIED : TaskOutcome.PARTIAL,
                unmet, reason, List.copyOf(evidence), completed);
    }

    public static TaskContractV2 desktopContract(TaskContractV3 contract,
            TrustedCapabilityRegistry capabilities) {
        List<TaskCriterion> criteria = contract.criteria().stream()
                .filter(c -> capabilities.find(c.capabilityId())
                        .map(d -> d.verifierPolicy()
                                == TrustedCapabilityRegistry.VerifierPolicy.DESKTOP_LINKED_FRAME)
                        .orElse(false))
                .map(c -> new TaskCriterion(c.id(), c.description(), c.target(),
                        capabilities.find(c.capabilityId()).orElseThrow().operation(),
                        c.requiredEvidence().name(), c.requiredSubject()))
                .toList();
        return new TaskContractV2(2, contract.originalRequest(), "", criteria,
                contract.applicable(), contract.reliable() && !criteria.isEmpty()
                        && contract.desktopObservationSubjectsValid(), contract.source());
    }

    private static boolean matchesV3(TaskCriterionV3 criterion, Receipt receipt,
            RunEventEnvelope event, TrustedCapabilityRegistry capabilities,
            DesktopApplicationIdentityBindings identities) {
        var descriptor = capabilities.find(criterion.capabilityId()).orElse(null);
        var actual = capabilities.forReceipt(event.payload().path("tool").asText(""),
                event.payload().path("operation").asText("")).orElse(null);
        if (descriptor == null || actual == null || !actual.id().equals(descriptor.id())
                || criterion.targetType() != CapabilityMetadata.TargetKind.valueOf(
                        descriptor.targetKind().name())
                || !CapabilityTargetMatcher.matches(capabilities, descriptor,
                        criterion.target(), event, identities)
                || (!criterion.requiredSubject().isBlank()
                        && !criterion.requiredSubject().equalsIgnoreCase(receipt.subject())
                        && !(criterion.capabilityId().equals("desktop.observe")
                                && DesktopConditionProof.matches(criterion.id(),
                                        criterion.requiredSubject(), receipt.metadata())))) return false;
        try {
            EffectReceiptV1.Status status = EffectReceiptV1.Status.valueOf(receipt.status());
            return TrustedCapabilityRegistry.rank(status)
                    >= TrustedCapabilityRegistry.rank(criterion.requiredEvidence())
                    && TrustedCapabilityRegistry.rank(descriptor.evidenceCeiling())
                    >= TrustedCapabilityRegistry.rank(criterion.requiredEvidence());
        } catch (IllegalArgumentException invalid) { return false; }
    }

    /** A model proposal can gate completion, but cannot create evidence. */
    public static TaskResult gateWithModelDecision(TaskResult evidenceResult,
            List<RunEventEnvelope> ownEvents) {
        return TaskModelDecisionGate.gateWithModelDecision(evidenceResult, ownEvents);
    }

    public static Optional<ModelDecisionV1.Decision> latestModelDecision(
            List<RunEventEnvelope> ownEvents) {
        return TaskModelDecisionGate.latestModelDecision(ownEvents);
    }

    public static long modelDecisionBoundary(List<RunEventEnvelope> events) {
        return TaskModelDecisionGate.decisionBoundary(events);
    }

    public static TaskResult evaluate(
            TaskContractV1 contract, List<RunEventEnvelope> events, String stopReason) {
        if (contract == null) return TaskResult.unverified("TASK_CONTRACT_MISSING");
        if (!contract.applicable()) {
            // A classifier's "question only" answer cannot hide an actual tool-using run.
            if (events.stream().anyMatch(event -> isBusinessToolStart(
                    event.type(), event.producer(), event.payload()))) {
                return TaskResult.unverified("TOOL_USED_AFTER_NOT_APPLICABLE_CLASSIFICATION");
            }
            return TaskResult.notApplicable();
        }
        if (!contract.reliable() || contract.criteria().isEmpty()) {
            return TaskResult.unverified("TASK_CONTRACT_UNRELIABLE");
        }
        List<Receipt> receipts = events.stream()
                .filter(event -> event.type().equals("core.tool.receipt")
                        && event.schemaVersion() == 1
                        && event.producer().equals("framework.core"))
                .map(TaskResultEvaluator::receipt)
                .flatMap(Optional::stream)
                .toList();
        List<String> unmet = new ArrayList<>();
        List<String> completed = new ArrayList<>();
        LinkedHashSet<String> evidence = new LinkedHashSet<>();
        int lastMatchedIndex = -1;
        int satisfied = 0;
        for (TaskCriterion criterion : contract.criteria()) {
            int match = -1;
            for (int index = lastMatchedIndex + 1; index < receipts.size(); index++) {
                if (matches(criterion, receipts.get(index))) {
                    match = index;
                    break;
                }
            }
            if (match >= 0) {
                satisfied++;
                lastMatchedIndex = match;
                evidence.add(receipts.get(match).evidenceRef());
                completed.add(criterion.description());
            } else {
                unmet.add(criterion.description() + " [" + criterion.target() + ":"
                        + criterion.requiredOperation() + "/" + criterion.requiredEvidence()
                        + (criterion.requiredSubject().isBlank() ? "" : "/" + criterion.requiredSubject())
                        + "]");
            }
        }
        if (unmet.isEmpty()) {
            return new TaskResult(TaskOutcome.VERIFIED_COMPLETE, List.of(), "",
                    List.copyOf(evidence), completed);
        }
        String reason = stopReason == null ? "" : stopReason.trim();
        if (reason.isBlank()) reason = "MISSING_TRUSTED_RECEIPT";
        TaskOutcome outcome = satisfied > 0 ? TaskOutcome.PARTIAL : TaskOutcome.UNVERIFIED;
        return new TaskResult(outcome, unmet, reason, List.copyOf(evidence), completed);
    }

    /** Schema 2 requires exact session/window binding and a fresh frame after every desktop action. */
    public static TaskResult evaluateV2(
            TaskContractV2 contract, List<RunEventEnvelope> events, String stopReason) {
        return evaluateV2(contract, events, stopReason, TrustedCapabilityRegistry.builtins());
    }

    private static TaskResult evaluateV2(TaskContractV2 contract,
            List<RunEventEnvelope> events, String stopReason,
            TrustedCapabilityRegistry capabilities) {
        if (contract == null) return TaskResult.unverified("TASK_CONTRACT_MISSING");
        if (!contract.applicable()) {
            if (events.stream().anyMatch(event -> isBusinessToolStart(
                    event.type(), event.producer(), event.payload()))) {
                return TaskResult.unverified("TOOL_USED_AFTER_NOT_APPLICABLE_CLASSIFICATION");
            }
            return TaskResult.notApplicable();
        }
        if (!contract.reliable() || contract.criteria().isEmpty()) {
            return TaskResult.unverified("TASK_CONTRACT_UNRELIABLE");
        }
        boolean desktop = contract.criteria().stream().anyMatch(c ->
                c.requiredOperation().equalsIgnoreCase("open")
                        || c.requiredOperation().equalsIgnoreCase("launch_application"))
                || events.stream().anyMatch(event -> event.type().equals("core.tool.receipt")
                        && event.schemaVersion() == 1
                        && event.producer().equals("framework.core")
                        && capabilities.forReceipt(
                                event.payload().path("tool").asText(""),
                                event.payload().path("operation").asText(""))
                                .map(descriptor -> descriptor.verifierPolicy()
                                        == TrustedCapabilityRegistry.VerifierPolicy.DESKTOP_LINKED_FRAME)
                                .orElse(false)
                        && contract.criteria().stream().anyMatch(c ->
                                c.requiredOperation().equalsIgnoreCase(
                                        event.payload().path("operation").asText(""))));
        if (!desktop) {
            return evaluate(new TaskContractV1(1, contract.originalRequest(), contract.target(),
                    contract.criteria(), contract.applicable(), contract.reliable(), contract.source()),
                    events, stopReason);
        }
        List<RunEventEnvelope> receipts = events.stream()
                .filter(event -> event.type().equals("core.tool.receipt")
                        && event.schemaVersion() == 1
                        && event.producer().equals("framework.core"))
                .toList();
        DesktopApplicationIdentityBindings identities = DesktopApplicationIdentityBindings.fromEvents(events);
        List<String> unmet = new ArrayList<>();
        List<String> completed = new ArrayList<>();
        LinkedHashSet<String> evidence = new LinkedHashSet<>();
        String session = "";
        String targetId = "";
        int matched = -1;
        int openedAt = -1;
        for (TaskCriterion criterion : contract.criteria()) {
            int found = -1;
            int foundBaseline = -1;
            for (int index = matched + 1; index < receipts.size(); index++) {
                RunEventEnvelope event = receipts.get(index);
                Receipt current = receipt(event).orElse(null);
                if (current == null && criterion.requiredOperation().equalsIgnoreCase("click")
                        && criterion.requiredEvidence().equals("ACCEPTED")
                        && event.payload().path("operation").asText("").equals("click")
                        && event.payload().path("status").asText("").equals("UNKNOWN")
                        && event.payload().path("metadata").path("dispatchAttempted")
                                .asText("").equals("true")
                        && futureViewConfirms(contract, receipts, index, event, identities, capabilities)) {
                    var payload = event.payload();
                    current = new Receipt(payload.path("invocationId").asText(""), "click",
                            payload.path("target").asText(""), "ACCEPTED",
                            payload.path("evidenceRef").asText(""), "",
                            payload.path("metadata").path("applicationId").asText(""));
                }
                if (current == null) continue;
                if (!matches(criterion, current, event, identities, capabilities)) continue;
                String operation = normalized(current.operation());
                var metadata = event.payload().path("metadata");
                if (operation.equals("open")) {
                    if (metadata.path("sessionId").asText("").isBlank()
                            || metadata.path("targetId").asText("").isBlank()) continue;
                } else if (operation.equals("observe") || operation.equals("snapshot")) {
                    if (session.isBlank()) {
                        // An observe-only task can use the current frame itself as its
                        // baseline, provided the requested view has structured proof.
                        if (contract.criteria().stream().anyMatch(c ->
                                isDesktopAction(c.requiredOperation()))
                                || criterion.requiredSubject().isBlank()
                                || metadata.path("sessionId").asText("").isBlank()
                                || metadata.path("targetId").asText("").isBlank()
                                || !validFrame(metadata)
                                || !DesktopConditionProof.hasViewEvidence(criterion.id(),
                                        criterion.requiredSubject(), metadata)) continue;
                        foundBaseline = index;
                    } else {
                        if (!session.equals(metadata.path("sessionId").asText(""))
                                || !targetId.equals(metadata.path("targetId").asText(""))
                                || (openedAt >= 0 && (!receipts.get(openedAt).runId().equals(event.runId())
                                        || !sameApplication(receipts.get(openedAt), event)))
                                || !validFrame(metadata)
                                || (!criterion.requiredSubject().isBlank()
                                        && !DesktopConditionProof.hasViewEvidence(criterion.id(),
                                                criterion.requiredSubject(), metadata))
                                || !freshAfterActions(receipts, openedAt, index, session, targetId,
                                        metadata, !criterion.requiredSubject().isBlank())) continue;
                    }
                    // A later action on this exact window invalidates a prior view result.
                    if (laterDesktopAction(receipts, index,
                            metadata.path("sessionId").asText(""),
                            metadata.path("targetId").asText(""))) continue;
                } else if (isDesktopAction(operation)) {
                    int prior = priorObservedFrame(receipts, index, event);
                    if (prior < 0) continue;
                    if (session.isBlank()) {
                        // The prior observation is a trusted, non-criterion receipt.
                        // It must be the exact frame the action claims to use.
                        foundBaseline = prior;
                    } else if (!session.equals(metadata.path("sessionId").asText(""))
                            || !targetId.equals(metadata.path("targetId").asText(""))
                            || metadata.path("observationId").asText("").isBlank()) continue;
                }
                found = index;
                break;
            }
            if (found < 0) {
                unmet.add(criterion.description() + " [" + criterion.target() + ":"
                        + criterion.requiredOperation() + "/" + criterion.requiredEvidence()
                        + (criterion.requiredSubject().isBlank() ? "" : "/" + criterion.requiredSubject())
                        + "]");
                continue;
            }
            RunEventEnvelope event = receipts.get(found);
            var metadata = event.payload().path("metadata");
            if (criterion.requiredOperation().equalsIgnoreCase("open")) {
                session = metadata.path("sessionId").asText("");
                targetId = metadata.path("targetId").asText("");
                openedAt = found;
            } else if (foundBaseline >= 0 && session.isBlank()) {
                session = metadata.path("sessionId").asText("");
                targetId = metadata.path("targetId").asText("");
                openedAt = foundBaseline;
            }
            matched = found;
            completed.add(criterion.description());
            evidence.add(event.payload().path("evidenceRef").asText());
        }
        if (unmet.isEmpty()) return new TaskResult(TaskOutcome.VERIFIED_COMPLETE, List.of(), "",
                List.copyOf(evidence), completed);
        String reason = stopReason == null || stopReason.isBlank()
                ? "MISSING_LINKED_POST_ACTION_EVIDENCE" : stopReason.strip();
        return new TaskResult(completed.isEmpty() ? TaskOutcome.UNVERIFIED : TaskOutcome.PARTIAL,
                unmet, reason, List.copyOf(evidence), completed);
    }

    private static boolean validFrame(com.fasterxml.jackson.databind.JsonNode metadata) {
        if (metadata.path("observationId").asText("").isBlank()) return false;
        try {
            java.util.UUID.fromString(metadata.path("observationId").asText());
            return metadata.path("windowGeneration").asLong(-1) >= 0
                    && metadata.path("contentRevision").asLong(-1) >= 0
                    && metadata.path("capturedAtMillis").asLong(-1) > 0;
        } catch (RuntimeException invalid) { return false; }
    }

    /** The action must name the last trusted frame of this exact Run/session/window. */
    private static int priorObservedFrame(List<RunEventEnvelope> receipts, int actionIndex,
                                          RunEventEnvelope action) {
        var actionMetadata = action.payload().path("metadata");
        String session = actionMetadata.path("sessionId").asText("");
        String targetId = actionMetadata.path("targetId").asText("");
        String observationId = actionMetadata.path("observationId").asText("");
        long generation = actionMetadata.path("windowGeneration").asLong(-1);
        if (session.isBlank() || targetId.isBlank() || generation < 0) return -1;
        try { java.util.UUID.fromString(observationId); }
        catch (RuntimeException invalid) { return -1; }
        for (int index = actionIndex - 1; index >= 0; index--) {
            RunEventEnvelope candidate = receipts.get(index);
            if (!candidate.runId().equals(action.runId())) continue;
            var metadata = candidate.payload().path("metadata");
            if (!session.equals(metadata.path("sessionId").asText(""))
                    || !targetId.equals(metadata.path("targetId").asText(""))) continue;
            if (!sameApplication(candidate, action)) return -1;
            String operation = candidate.payload().path("operation").asText("");
            if (undispatchedDesktopFailure(candidate) && !invalidatedDesktopObservation(candidate)) continue;
            if (isDesktopAction(operation) || operation.equals("open")) return -1;
            if (!operation.equals("observe")
                    || !candidate.payload().path("tool").asText("")
                            .equals("desktop_session_observe")) continue;
            return candidate.payload().path("status").asText("").equals("OBSERVED")
                    && validFrame(metadata)
                    && observationId.equals(metadata.path("observationId").asText(""))
                    && generationMatches(generation, metadata)
                    && sameApplicationTarget(candidate, action)
                    && metadata.path("capturedAtMillis").asLong(0) <= observedAtMillis(action)
                    ? index : -1;
        }
        return -1;
    }

    private static boolean futureViewConfirms(TaskContractV2 contract,
            List<RunEventEnvelope> receipts, int actionIndex, RunEventEnvelope action,
            DesktopApplicationIdentityBindings identities, TrustedCapabilityRegistry capabilities) {
        var actionMeta = action.payload().path("metadata");
        String session = actionMeta.path("sessionId").asText("");
        String targetId = actionMeta.path("targetId").asText("");
        String oldObservation = actionMeta.path("observationId").asText("");
        long generation = actionMeta.path("windowGeneration").asLong(-1);
        if (session.isBlank() || targetId.isBlank() || oldObservation.isBlank()
                || generation < 0 || priorObservedFrame(receipts, actionIndex, action) < 0) return false;
        for (int index = actionIndex + 1; index < receipts.size(); index++) {
            RunEventEnvelope observed = receipts.get(index);
            Receipt observation = receipt(observed).orElse(null);
            if (observation == null || !observation.operation().equals("observe")) continue;
            var frame = observed.payload().path("metadata");
            if (!session.equals(frame.path("sessionId").asText(""))
                    || !targetId.equals(frame.path("targetId").asText(""))
                    || !sameApplication(action, observed)
                    || !generationMatches(generation, frame)
                    || !validFrame(frame)
                    || frame.path("viewEvidence").asText("").isBlank()
                    || oldObservation.equals(frame.path("observationId").asText(""))
                    || frame.path("capturedAtMillis").asLong(0) <= observedAtMillis(action)
                    || laterDesktopAction(receipts, index, session, targetId)) continue;
            if (contract.criteria().stream().anyMatch(c -> c.requiredOperation().equalsIgnoreCase("observe")
                    && !c.requiredSubject().isBlank()
                    && matches(c, observation, observed, identities, capabilities, false))) return true;
        }
        return false;
    }

    private static boolean freshAfterActions(List<RunEventEnvelope> receipts, int openedAt,
                                             int observationIndex, String session, String targetId,
                                             com.fasterxml.jackson.databind.JsonNode frame,
                                             boolean viewStateProof) {
        String observationId = frame.path("observationId").asText("");
        long generation = frame.path("windowGeneration").asLong(-1);
        for (int index = openedAt + 1; index < observationIndex; index++) {
            RunEventEnvelope event = receipts.get(index);
            String operation = event.payload().path("operation").asText("");
            if (!isDesktopAction(operation) || undispatchedDesktopFailure(event)) continue;
            var metadata = event.payload().path("metadata");
            if (!session.equals(metadata.path("sessionId").asText(""))
                    || !targetId.equals(metadata.path("targetId").asText(""))) continue;
            if (!sameApplication(receipts.get(observationIndex), event)) return false;
            String status = event.payload().path("status").asText("");
            if ((!status.equals("ACCEPTED") && !(viewStateProof && status.equals("UNKNOWN")))
                    || priorObservedFrame(receipts, index, event) < 0
                    || !generationMatches(generation, metadata)
                    || observationId.equals(metadata.path("observationId").asText(""))
                    || frame.path("capturedAtMillis").asLong(0)
                            <= observedAtMillis(event)) return false;
        }
        return true;
    }

    private static long observedAtMillis(RunEventEnvelope event) {
        try { return java.time.Instant.parse(event.payload().path("observedAt").asText()).toEpochMilli(); }
        catch (RuntimeException invalid) { return Long.MAX_VALUE; }
    }

    private static boolean generationMatches(long generation,
                                             com.fasterxml.jackson.databind.JsonNode metadata) {
        return generation == metadata.path("windowGeneration").asLong(-1);
    }

    private static boolean sameApplication(RunEventEnvelope first, RunEventEnvelope second) {
        String firstId = DesktopApplicationIdentityBindings.normalize(
                first.payload().path("metadata").path("applicationId").asText(""));
        String secondId = DesktopApplicationIdentityBindings.normalize(
                second.payload().path("metadata").path("applicationId").asText(""));
        return firstId.isBlank() || secondId.isBlank() || firstId.equals(secondId);
    }

    private static boolean sameApplicationTarget(RunEventEnvelope first, RunEventEnvelope second) {
        String firstId = DesktopApplicationIdentityBindings.normalize(
                first.payload().path("metadata").path("applicationId").asText(""));
        String secondId = DesktopApplicationIdentityBindings.normalize(
                second.payload().path("metadata").path("applicationId").asText(""));
        if (!firstId.isBlank() && !secondId.isBlank()) return firstId.equals(secondId);
        return normalized(first.payload().path("target").asText(""))
                .equals(normalized(second.payload().path("target").asText("")));
    }

    private static boolean laterDesktopAction(List<RunEventEnvelope> receipts, int observationIndex,
                                               String session, String targetId) {
        for (int index = observationIndex + 1; index < receipts.size(); index++) {
            RunEventEnvelope event = receipts.get(index);
            if (!isDesktopAction(event.payload().path("operation").asText(""))
                    || undispatchedDesktopFailure(event) && !invalidatedDesktopObservation(event)) continue;
            var metadata = event.payload().path("metadata");
            if (session.equals(metadata.path("sessionId").asText(""))
                    && targetId.equals(metadata.path("targetId").asText(""))) return true;
        }
        return false;
    }

    private static boolean isDesktopAction(String operation) {
        return java.util.Set.of("click", "type", "key", "scroll").contains(normalized(operation));
    }

    /** Only a complete host rejection proves that this input could not change the window. */
    private static boolean undispatchedDesktopFailure(RunEventEnvelope event) {
        var payload = event.payload();
        var metadata = payload.path("metadata");
        return isDesktopAction(payload.path("operation").asText(""))
                && payload.path("status").asText("").equals("FAILED")
                && metadata.path("dispatchAttempted").asText("").equals("false")
                && metadata.path("delivery").asText("").equals("NOT_SENT");
    }

    /** Unknown causes, stale frames, and existing uncertain inputs keep the old-frame barrier. */
    private static boolean invalidatedDesktopObservation(RunEventEnvelope event) {
        var metadata = event.payload().path("metadata");
        return metadata.path("desktopStatus").asText("").equals("STALE_FRAME")
                || !Set.of("NO_SEMANTIC_PATH", "ACCESS_DENIED", "PLATFORM_FAILURE",
                        "SESSION_CONTROL_REQUIRED")
                        .contains(metadata.path("reasonCode").asText(""));
    }

    /**
     * A final view can establish the requested state even when click delivery was uncertain.
     * This proof is deliberately separate from task completion and must be persisted before
     * any input-replay barrier is cleared. Only an unknown click is eligible here.
     */
    public static List<VerifiedActionEvidence> verifiedActionEvidence(
            TaskContractV2 contract, List<RunEventEnvelope> events) {
        if (contract == null) return List.of();
        TaskResult result = evaluateV2(contract, events, "");
        if (result.outcome() != TaskOutcome.VERIFIED_COMPLETE) {
            return List.of();
        }
        List<TaskCriterion> views = contract.criteria().stream()
                .filter(c -> c.requiredOperation().equalsIgnoreCase("observe")
                        && !c.requiredSubject().isBlank()).toList();
        if (views.isEmpty()) return List.of();
        DesktopApplicationIdentityBindings identities = DesktopApplicationIdentityBindings.fromEvents(events);
        List<RunEventEnvelope> receipts = events.stream()
                .filter(e -> e.type().equals("core.tool.receipt") && e.schemaVersion() == 1
                        && e.producer().equals("framework.core"))
                .toList();
        List<VerifiedActionEvidence> proofs = new ArrayList<>();
        for (int observeIndex = 0; observeIndex < receipts.size(); observeIndex++) {
            RunEventEnvelope observed = receipts.get(observeIndex);
            Receipt observedReceipt = receipt(observed).orElse(null);
            if (observedReceipt == null || !observedReceipt.operation().equals("observe")
                    || !result.evidenceRefs().contains(observedReceipt.evidenceRef())
                    || views.stream().noneMatch(c -> matches(c, observedReceipt, observed,
                            identities, TrustedCapabilityRegistry.builtins(), false))) continue;
            var frame = observed.payload().path("metadata");
            if (!validFrame(frame) || frame.path("viewEvidence").asText("").isBlank()) continue;
            String session = frame.path("sessionId").asText("");
            String targetId = frame.path("targetId").asText("");
            if (session.isBlank() || targetId.isBlank()
                    || laterDesktopAction(receipts, observeIndex, session, targetId)) continue;
            int confirmedOpen = -1;
            for (int index = 0; index < observeIndex; index++) {
                RunEventEnvelope candidate = receipts.get(index);
                var metadata = candidate.payload().path("metadata");
                if (candidate.payload().path("operation").asText("").equals("open")
                        && result.evidenceRefs().contains(
                                candidate.payload().path("evidenceRef").asText(""))
                        && session.equals(metadata.path("sessionId").asText(""))
                        && targetId.equals(metadata.path("targetId").asText(""))) {
                    confirmedOpen = index;
                }
            }
            for (int actionIndex = 0; actionIndex < observeIndex; actionIndex++) {
                if (confirmedOpen >= 0 && actionIndex <= confirmedOpen) continue;
                RunEventEnvelope action = receipts.get(actionIndex);
                var payload = action.payload();
                var metadata = payload.path("metadata");
                if (!payload.path("operation").asText("").equals("click")
                        || !payload.path("status").asText("").equals("UNKNOWN")
                        || !metadata.path("dispatchAttempted").asText("").equals("true")
                        || metadata.path("observationId").asText("").isBlank()
                        || payload.path("invocationId").asText("").isBlank()
                        || !session.equals(metadata.path("sessionId").asText(""))
                        || !targetId.equals(metadata.path("targetId").asText(""))
                        || !sameApplication(action, observed)
                        || !generationMatches(frame.path("windowGeneration").asLong(-1), metadata)
                        || frame.path("observationId").asText("")
                                .equals(metadata.path("observationId").asText(""))
                        || frame.path("capturedAtMillis").asLong(0) <= observedAtMillis(action)) {
                    continue;
                }
                int beforeIndex = priorObservedFrame(receipts, actionIndex, action);
                if (beforeIndex < 0) continue;
                RunEventEnvelope before = receipts.get(beforeIndex);
                // The requested page must be a demonstrated transition. A final
                // page that was already visible, or an unclassified prior frame,
                // cannot establish what an uncertain click actually did.
                if (before.payload().path("metadata").path("viewEvidence").asText("").isBlank()
                        || before.payload().path("subject").asText("").isBlank()
                        || normalized(before.payload().path("subject").asText(""))
                                .equals(normalized(observedReceipt.subject()))
                        || interveningDesktopAction(receipts, actionIndex, observeIndex,
                                targetId)) continue;
                proofs.add(new VerifiedActionEvidence(payload.path("invocationId").asText(""),
                        session, targetId, metadata.path("observationId").asText(""),
                        frame.path("observationId").asText("")));
            }
        }
        return List.copyOf(proofs);
    }

    private static boolean interveningDesktopAction(List<RunEventEnvelope> receipts,
            int actionIndex, int observationIndex, String targetId) {
        for (int index = actionIndex + 1; index < observationIndex; index++) {
            RunEventEnvelope event = receipts.get(index);
            if (!isDesktopAction(event.payload().path("operation").asText(""))
                    || undispatchedDesktopFailure(event) && !invalidatedDesktopObservation(event)) continue;
            var metadata = event.payload().path("metadata");
            if (targetId.equals(metadata.path("targetId").asText(""))) return true;
        }
        return false;
    }

    public record VerifiedActionEvidence(String invocationId, String sessionId,
                                         String targetId, String actionObservationId,
                                         String evidenceObservationId) { }

    /**
     * A partial Run may clear an uncertain click only when its already declared, adjacent
     * view condition has become true. This is narrower than terminal task acceptance:
     * a view elsewhere in the contract cannot validate an unrelated click.
     */
    public static List<VerifiedCheckpointEvidence> verifiedCheckpointEvidence(
            TaskContractV2 contract, List<RunEventEnvelope> events) {
        if (contract == null || !contract.applicable() || !contract.reliable()) return List.of();
        DesktopApplicationIdentityBindings identities = DesktopApplicationIdentityBindings.fromEvents(events);
        List<RunEventEnvelope> receipts = events.stream()
                .filter(e -> e.type().equals("core.tool.receipt") && e.schemaVersion() == 1
                        && e.producer().equals("framework.core"))
                .toList();
        List<VerifiedCheckpointEvidence> verified = new ArrayList<>();
        for (int criterionIndex = 0; criterionIndex + 1 < contract.criteria().size(); criterionIndex++) {
            TaskCriterion click = contract.criteria().get(criterionIndex);
            TaskCriterion view = contract.criteria().get(criterionIndex + 1);
            if (!normalized(click.requiredOperation()).equals("click")
                    || !click.requiredEvidence().equals("ACCEPTED")
                    || !normalized(view.requiredOperation()).equals("observe")
                    || !view.requiredEvidence().equals("OBSERVED")
                    || view.requiredSubject().isBlank()
                    || !normalized(click.target()).equals(normalized(view.target()))) continue;
            for (int actionIndex = 0; actionIndex < receipts.size(); actionIndex++) {
                RunEventEnvelope action = receipts.get(actionIndex);
                var payload = action.payload();
                var actionMeta = payload.path("metadata");
                if (!payload.path("tool").asText("").equals("desktop_session_click")
                        || !payload.path("operation").asText("").equals("click")
                        || !payload.path("status").asText("").equals("UNKNOWN")
                        || !actionMeta.path("delivery").asText("").equals("MAYBE_SENT")
                        || !actionMeta.path("dispatchAttempted").asText("").equals("true")
                        || !identities.matches(click.target(), payload.path("target").asText(""),
                                actionMeta.path("applicationId").asText(""),
                                action.runId(), action.sequence())
                        || payload.path("invocationId").asText("").isBlank()) continue;
                int beforeIndex = priorObservedFrame(receipts, actionIndex, action);
                if (beforeIndex < 0) continue;
                RunEventEnvelope before = receipts.get(beforeIndex);
                var beforeMeta = before.payload().path("metadata");
                if (beforeMeta.path("viewEvidence").asText("").isBlank()
                        || normalized(before.payload().path("subject").asText("")).isBlank()
                        || normalized(before.payload().path("subject").asText(""))
                                .equals(normalized(view.requiredSubject()))) continue;
                // Every earlier contract condition must have been established before this input.
                if (criterionIndex > 0) {
                    TaskContractV2 prefix = new TaskContractV2(2, contract.originalRequest(),
                            contract.target(), contract.criteria().subList(0, criterionIndex),
                            true, true, contract.source());
                    List<RunEventEnvelope> preceding = events.stream()
                            .filter(e -> e.runId().equals(action.runId())
                                    && e.sequence() < action.sequence()).toList();
                    if (evaluateV2(prefix, preceding, "").outcome()
                            != TaskOutcome.VERIFIED_COMPLETE) continue;
                }
                String session = actionMeta.path("sessionId").asText("");
                String targetId = actionMeta.path("targetId").asText("");
                String oldObservation = actionMeta.path("observationId").asText("");
                long generation = actionMeta.path("windowGeneration").asLong(-1);
                for (int observeIndex = actionIndex + 1; observeIndex < receipts.size(); observeIndex++) {
                    RunEventEnvelope observed = receipts.get(observeIndex);
                    if (!observed.runId().equals(action.runId())) continue;
                    var observationPayload = observed.payload();
                    var frame = observationPayload.path("metadata");
                    if (isDesktopAction(observationPayload.path("operation").asText(""))
                            && targetId.equals(frame.path("targetId").asText(""))) break;
                    Receipt observation = receipt(observed).orElse(null);
                    if (observation == null || !observation.operation().equals("observe")
                            || !observationPayload.path("tool").asText("")
                                    .equals("desktop_session_observe")
                            || !matches(view, observation, observed, identities,
                                    TrustedCapabilityRegistry.builtins(), false)
                            || !validFrame(frame)
                            || frame.path("viewEvidence").asText("").isBlank()
                            || !session.equals(frame.path("sessionId").asText(""))
                            || !targetId.equals(frame.path("targetId").asText(""))
                            || !sameApplication(action, observed)
                            || !generationMatches(generation, frame)
                            || oldObservation.equals(frame.path("observationId").asText(""))
                            || frame.path("capturedAtMillis").asLong(0) <= observedAtMillis(action)
                            || observedAtMillis(observed) <= observedAtMillis(action)
                            || laterDesktopAction(receipts, observeIndex, session, targetId)) continue;
                    verified.add(new VerifiedCheckpointEvidence(
                            new VerifiedActionEvidence(payload.path("invocationId").asText(""),
                                    session, targetId, oldObservation,
                                    frame.path("observationId").asText("")),
                            click.id(), view.id(), view.requiredSubject(),
                            observation.evidenceRef()));
                    break;
                }
            }
        }
        return List.copyOf(verified);
    }

    public record VerifiedCheckpointEvidence(VerifiedActionEvidence proof,
            String clickCriterionId, String viewCriterionId, String requiredSubject,
            String observationEvidenceRef) { }

    static boolean isBusinessToolStart(String type, String producer,
                                       com.fasterxml.jackson.databind.JsonNode payload) {
        if (!"core.tool.started".equals(type) || !"framework.core".equals(producer)) return false;
        return payload == null || (!payload.path("trustedContextRead").asBoolean(false)
                && !payload.path("trustedToolCatalog").asBoolean(false));
    }

    /** Uses the durable contract, including revisions explicitly triggered by human clarification. */
    public static Optional<TaskContractV1> latestContract(
            List<RunEventEnvelope> events, ObjectMapper json) {
        return TaskResultEventReader.latestContract(events, json);
    }

    public static Optional<TaskContractV2> latestContractV2(
            List<RunEventEnvelope> events, ObjectMapper json) {
        return TaskResultEventReader.latestContractV2(events, json);
    }

    public static Optional<TaskContractV3> latestContractV3(
            List<RunEventEnvelope> events, ObjectMapper json) {
        return TaskResultEventReader.latestContractV3(events, json);
    }

    public static Optional<TaskResult> latestOutcome(
            List<RunEventEnvelope> events, ObjectMapper json) {
        return TaskResultEventReader.latestOutcome(events, json);
    }

    private static Optional<Receipt> receipt(RunEventEnvelope event) {
        var payload = event.payload();
        String operation = payload.path("operation").asText("").trim();
        String target = payload.path("target").asText("").trim();
        String status = payload.path("status").asText("").trim().toUpperCase(Locale.ROOT);
        String evidenceRef = payload.path("evidenceRef").asText("").trim();
        String invocationId = payload.path("invocationId").asText("").trim();
        String subject = payload.path("subject").asText("").trim();
        if (operation.isBlank() || target.isBlank() || evidenceRef.isBlank() || invocationId.isBlank()) {
            return Optional.empty();
        }
        if (!status.equals("ACCEPTED") && !status.equals("OBSERVED") && !status.equals("VERIFIED")) {
            return Optional.empty();
        }
        try { java.time.Instant.parse(payload.path("observedAt").asText()); }
        catch (RuntimeException failure) { return Optional.empty(); }
        return Optional.of(new Receipt(invocationId, operation, target, status, evidenceRef,
                subject, payload.path("metadata").path("applicationId").asText(""),
                payload.path("metadata")));
    }

    private static boolean matches(TaskCriterion criterion, Receipt receipt,
            RunEventEnvelope event, DesktopApplicationIdentityBindings identities,
            TrustedCapabilityRegistry capabilities) {
        return matches(criterion, receipt, event, identities, capabilities, true);
    }

    private static boolean matches(TaskCriterion criterion, Receipt receipt,
            RunEventEnvelope event, DesktopApplicationIdentityBindings identities,
            TrustedCapabilityRegistry capabilities, boolean allowConditionProof) {
        var descriptor = capabilities.forReceipt(event.payload().path("tool").asText(""),
                event.payload().path("operation").asText("")).orElse(null);
        boolean desktop = descriptor != null && descriptor.targetKind()
                == TrustedCapabilityRegistry.TargetKind.DESKTOP_APPLICATION;
        boolean target = desktop ? identities.matches(criterion.target(), receipt.target(),
                receipt.applicationId(), event.runId(), event.sequence()) : targetMatches(criterion, receipt);
        return target
                && normalized(criterion.requiredOperation()).equals(normalized(receipt.operation()))
                && (criterion.requiredSubject().isBlank()
                        || normalized(criterion.requiredSubject()).equals(normalized(receipt.subject()))
                        || (allowConditionProof && desktop && descriptor.id().equals("desktop.observe")
                                && DesktopConditionProof.matches(criterion.id(),
                                        criterion.requiredSubject(), receipt.metadata())))
                && rank(receipt.status()) >= rank(criterion.requiredEvidence());
    }

    private static boolean matches(TaskCriterion criterion, Receipt receipt) {
        return targetMatches(criterion, receipt)
                && normalized(criterion.requiredOperation()).equals(normalized(receipt.operation()))
                && (criterion.requiredSubject().isBlank()
                        || normalized(criterion.requiredSubject()).equals(normalized(receipt.subject())))
                && rank(receipt.status()) >= rank(criterion.requiredEvidence());
    }

    private static String normalized(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean targetMatches(TaskCriterion criterion, Receipt receipt) {
        if (normalized(criterion.target()).equals(normalized(receipt.target()))) return true;
        if (!receipt.applicationId().isBlank()
                && Set.of("launch_application", "open", "observe", "snapshot", "click",
                        "type", "key", "scroll").contains(normalized(criterion.requiredOperation()))
                && normalized(criterion.target()).equals(normalized(receipt.applicationId()))) {
            return true;
        }
        if (receipt.target().startsWith("http://") || receipt.target().startsWith("https://")) {
            try {
                URI actual = URI.create(receipt.target());
                URI expected = URI.create(criterion.target());
                if (expected.getHost() != null) {
                    if (expected.getRawQuery() != null || expected.getRawFragment() != null) return false;
                    return normalized(expected.getScheme()).equals(normalized(actual.getScheme()))
                            && normalized(expected.getHost()).equals(normalized(actual.getHost()))
                            && expected.getPort() == actual.getPort()
                            && normalized(path(expected)).equals(normalized(path(actual)));
                }
                // V2 has no target type. A bare host cannot be distinguished from
                // ordinary content, so only an explicit URL can match a URL receipt.
                return false;
            } catch (RuntimeException ignored) { return false; }
        }
        if (SetOfFileOperations.contains(criterion.requiredOperation())) {
            try {
                Path actual = Path.of(receipt.target());
                Path expected = Path.of(criterion.target());
                if (actual.isAbsolute() && !expected.isAbsolute()) {
                    Path resolved = ProjectAccessPolicy.requireProjectFilePath(
                            ProjectAccessPolicy.projectRoot().resolve(expected));
                    return resolved.equals(actual.toAbsolutePath().normalize());
                }
            } catch (RuntimeException ignored) { return false; }
        }
        return false;
    }

    private static String path(URI uri) {
        return uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
    }

    private static final class SetOfFileOperations {
        private static final java.util.Set<String> VALUES = java.util.Set.of(
                "write", "copy", "move", "mkdir", "read", "list", "delete");
        static boolean contains(String operation) {
            return VALUES.contains(normalized(operation));
        }
    }

    private static int rank(String status) {
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "ACCEPTED" -> 1;
            case "OBSERVED" -> 2;
            case "VERIFIED" -> 3;
            default -> 0;
        };
    }

    private record Receipt(String invocationId, String operation, String target,
                           String status, String evidenceRef, String subject,
                           String applicationId, com.fasterxml.jackson.databind.JsonNode metadata) {
        private Receipt(String invocationId, String operation, String target,
                String status, String evidenceRef, String subject, String applicationId) {
            this(invocationId, operation, target, status, evidenceRef, subject,
                    applicationId, com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        }
        private Receipt(String invocationId, String operation, String target,
                String status, String evidenceRef, String subject) {
            this(invocationId, operation, target, status, evidenceRef, subject, "");
        }
    }
}
