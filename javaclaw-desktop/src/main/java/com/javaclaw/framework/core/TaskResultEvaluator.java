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
import com.javaclaw.framework.spi.FileContentProof;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.javaclaw.framework.core.TaskReceiptMatcher.*;
import static com.javaclaw.framework.core.DesktopTaskEvidenceEvaluator.isDesktopAction;

/** 仅接受与冻结合同逐项匹配的宿主工具收据，模型文本不产生完成证据。 */
public final class TaskResultEvaluator {
    private final ObjectMapper json;
    private final DesktopConditionProof conditionProof;
    private final DesktopTaskEvidenceEvaluator desktop;

    public TaskResultEvaluator(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
        conditionProof = new DesktopConditionProof(json);
        desktop = new DesktopTaskEvidenceEvaluator(json);
    }

    /** Internal cross-package preparation state; never changes receipt identity verification. */
    public enum DesktopApplicationIdentityStatus {
        ABSENT, COMPLETE, INCOMPLETE, INVALID, EMPTY, AMBIGUOUS, TRUNCATED
    }

    public record DesktopApplicationIdentityState(DesktopApplicationIdentityStatus status,
            String query, int nextOffset, String catalogId, String applicationId,
            List<String> evidenceRefs) {
        public DesktopApplicationIdentityState {
            java.util.Objects.requireNonNull(status);
            java.util.Objects.requireNonNull(query);
            java.util.Objects.requireNonNull(catalogId);
            java.util.Objects.requireNonNull(applicationId);
            evidenceRefs = List.copyOf(evidenceRefs);
            if (nextOffset < 0 || query.length() > 512 || applicationId.length() > 256
                    || !catalogId.isEmpty() && !catalogId.matches("[0-9a-f]{64}")
                    || evidenceRefs.size() > 16 || evidenceRefs.stream().anyMatch(ref ->
                        ref.isBlank() || ref.length() > 256)) {
                throw new IllegalArgumentException("invalid application identity preparation state");
            }
        }
    }

    /** Only the evaluator's own native catalog parser may supply fresh provider preparation. */
    public static DesktopApplicationIdentityState desktopApplicationIdentityState(
            List<RunEventEnvelope> events, String runId, String requested, long beforeSequence) {
        if (events == null || runId == null || runId.isBlank() || requested == null || requested.isBlank()
                || requested.length() > 512 || beforeSequence <= 0
                || events.stream().anyMatch(event -> !event.runId().equals(runId))) {
            return new DesktopApplicationIdentityState(DesktopApplicationIdentityStatus.INVALID,
                    "", 0, "", "", List.of());
        }
        var earlier = events.stream().filter(event -> event.sequence() < beforeSequence).toList();
        return DesktopApplicationIdentityBindings.fromEvents(earlier)
                .preparationState(earlier, runId, requested, beforeSequence);
    }

    /** V3 accepts only capabilities registered by the host and exact typed receipts. */
    public TaskResult evaluateV3(TaskContractV3 contract,
            List<RunEventEnvelope> events, String stopReason,
            TrustedCapabilityRegistry capabilities) {
        return evaluateV3(contract, events, stopReason, capabilities, new LinkedHashMap<>());
    }

    /** Uses the same ordered acceptance pass; descriptions are never criterion identities. */
    public Map<String, String> criterionEvidenceV3(TaskContractV3 contract,
            List<RunEventEnvelope> events, String stopReason, TrustedCapabilityRegistry capabilities) {
        Map<String, String> selected = new LinkedHashMap<>();
        evaluateV3(contract, events, stopReason, capabilities, selected);
        return Map.copyOf(selected);
    }

    private TaskResult evaluateV3(TaskContractV3 contract,
            List<RunEventEnvelope> events, String stopReason,
            TrustedCapabilityRegistry capabilities, Map<String, String> selectedEvidence) {
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
        Set<String> historicalViewIds = historicalDesktopViewIds(contract, capabilities);
        List<String> reconciledClicks = viewContract == null
                ? List.of() : verifiedActionEvidence(viewContract, desktopEvents).stream()
                        .map(VerifiedActionEvidence::invocationId).toList();
        Map<String, String> desktopEvidenceBindings = new LinkedHashMap<>();
        Map<String, RunEventEnvelope> selectedDesktopReceipts = new LinkedHashMap<>();
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
                if (receipt != null && matchesV3(criterion, receipt, event, capabilities, identities, events)) {
                    // Validate this exact ordered candidate against the previously chosen
                    // desktop receipts, not an independent earlier matching view chain.
                    if (desktop.contains(criterion) && !linkedDesktopCandidate(
                            contract, viewContract, criterion, event, desktopEvidenceBindings,
                            evidenceByCriterion, desktopEvents, events, stopReason, capabilities, historicalViewIds)) continue;
                    matched = index;
                    break;
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
                if (desktop.contains(criterion)) {
                    desktopEvidenceBindings.put(criterion.id(),
                            receipts.get(matched).payload().path("evidenceRef").asText());
                    selectedDesktopReceipts.put(criterion.id(), receipts.get(matched));
                }
            }
        }
        // An uncertain click also needs the stronger demonstrated-transition proof
        // from this same selected chain, not a different unbound view chain.
        if (selectedDesktopReceipts.values().stream().anyMatch(event ->
                event.payload().path("operation").asText("").equals("click")
                        && event.payload().path("status").asText("").equals("UNKNOWN"))) {
            Set<String> boundClicks = this.desktop.verifiedActionEvidence(viewContract, desktopEvents,
                    desktopEvidenceBindings).stream().map(VerifiedActionEvidence::invocationId)
                    .collect(java.util.stream.Collectors.toSet());
            for (TaskCriterionV3 criterion : desktop) {
                RunEventEnvelope selected = selectedDesktopReceipts.get(criterion.id());
                if (selected == null || !selected.payload().path("operation").asText("").equals("click")
                        || !selected.payload().path("status").asText("").equals("UNKNOWN")) continue;
                if (!boundClicks.contains(selected.payload().path("invocationId").asText(""))) {
                    unmet.add(criterion.description() + " [DESKTOP_POST_ACTION_PROOF]");
                    satisfied.removeIf(value -> value.id().equals(criterion.id()));
                    evidenceByCriterion.remove(criterion.id());
                }
            }
        }
        selectedEvidence.putAll(evidenceByCriterion);
        List<String> completed = satisfied.stream().map(TaskCriterionV3::description).toList();
        LinkedHashSet<String> evidence = new LinkedHashSet<>(evidenceByCriterion.values());
        if (unmet.isEmpty()) return new TaskResult(TaskOutcome.VERIFIED_COMPLETE,
                List.of(), "", List.copyOf(evidence), completed);
        String reason = stopReason == null || stopReason.isBlank()
                ? "MISSING_TRUSTED_RECEIPT" : stopReason.strip();
        return new TaskResult(completed.isEmpty() ? TaskOutcome.UNVERIFIED : TaskOutcome.PARTIAL,
                unmet, reason, List.copyOf(evidence), completed);
    }

    private boolean linkedDesktopCandidate(TaskContractV3 originalContract, TaskContractV2 viewContract,
            TaskCriterionV3 criterion, RunEventEnvelope candidate,
            Map<String, String> previousBindings, Map<String, String> previousOrderedBindings, List<RunEventEnvelope> events,
            List<RunEventEnvelope> completeHistory, String stopReason, TrustedCapabilityRegistry capabilities,
            Set<String> historicalViewIds) {
        String evidenceRef = candidate.payload().path("evidenceRef").asText("");
        if (viewContract == null || evidenceRef.isBlank()) return false;
        Map<String, String> bindings = new LinkedHashMap<>(previousBindings);
        bindings.put(criterion.id(), evidenceRef);
        // Keep all real events: dispatched inputs and failed/uncertain actions must
        // continue to invalidate stale frames even when they are not criteria. Keep
        // future view conditions too, for the existing uncertain-click proof gate.
        if (desktop.evaluateV2(viewContract, events, stopReason, capabilities, bindings, historicalViewIds)
                .evidenceRefs().contains(evidenceRef)) return true;
        // A process restart destroys RAM sessions. Re-link only a proved read-only recovery
        // to the same actual native object, using the actual new open and original predicates.
        var reopened = DesktopReadOnlySessionRecovery.reopened(originalContract, criterion,
                candidate, previousBindings, completeHistory, json);
        // A different actual window needs its own explicit open, native candidate identity,
        // complete subject proof and ordered read-only history, rather than the first window's session.
        if (reopened.isEmpty()) reopened = DesktopExplicitWindowObservation.opened(originalContract, criterion,
                candidate, previousOrderedBindings, completeHistory, json);
        if (reopened.isEmpty()) return false;
        TaskCriterion open = viewContract.criteria().getFirst();
        TaskCriterion observation = viewContract.criteria().stream()
                .filter(value -> value.id().equals(criterion.id())).findFirst().orElse(null);
        if (observation == null) return false;
        var recovered = new TaskContractV2(2, viewContract.originalRequest(), viewContract.target(),
                List.of(open, observation), true, true, viewContract.source());
        return desktop.evaluateV2(recovered, events, stopReason, capabilities,
                Map.of(open.id(), reopened.get().payload().path("evidenceRef").asText(),
                        criterion.id(), evidenceRef), Set.of()).evidenceRefs().contains(evidenceRef);
    }

    /** Only an explicitly ordered V3 input chain can request an earlier view as historical evidence. */
    private static Set<String> historicalDesktopViewIds(TaskContractV3 contract,
            TrustedCapabilityRegistry capabilities) {
        Set<String> historical = new LinkedHashSet<>();
        List<TaskCriterionV3> criteria = contract.criteria();
        for (int index = 0; index < criteria.size(); index++) {
            TaskCriterionV3 view = criteria.get(index);
            if (!isDesktopView(desktopOperation(view, capabilities))) continue;
            int lastAction = -1;
            for (int later = index + 1; later < criteria.size(); later++) {
                TaskCriterionV3 next = criteria.get(later);
                if (sameDeclaredDesktopTarget(view, next)
                        && isDesktopAction(desktopOperation(next, capabilities))) lastAction = later;
            }
            if (lastAction < 0) continue;
            for (int later = lastAction + 1; later < criteria.size(); later++) {
                TaskCriterionV3 finalView = criteria.get(later);
                if (sameDeclaredDesktopTarget(view, finalView)
                        && isDesktopView(desktopOperation(finalView, capabilities))) {
                    historical.add(view.id());
                    break;
                }
            }
        }
        return Set.copyOf(historical);
    }

    private static String desktopOperation(TaskCriterionV3 criterion,
            TrustedCapabilityRegistry capabilities) {
        return capabilities.find(criterion.capabilityId())
                .filter(descriptor -> descriptor.verifierPolicy()
                        == TrustedCapabilityRegistry.VerifierPolicy.DESKTOP_LINKED_FRAME)
                .map(descriptor -> normalized(descriptor.operation())).orElse("");
    }

    private static boolean sameDeclaredDesktopTarget(TaskCriterionV3 first, TaskCriterionV3 second) {
        return first.targetType() == second.targetType()
                && normalized(first.target()).equals(normalized(second.target()));
    }

    private static boolean isDesktopView(String operation) {
        return operation.equals("observe") || operation.equals("snapshot");
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

    private boolean matchesV3(TaskCriterionV3 criterion, Receipt receipt,
            RunEventEnvelope event, TrustedCapabilityRegistry capabilities,
            DesktopApplicationIdentityBindings identities, List<RunEventEnvelope> events) {
        var descriptor = capabilities.find(criterion.capabilityId()).orElse(null);
        var actual = capabilities.forReceipt(event.payload().path("tool").asText(""),
                event.payload().path("operation").asText("")).orElse(null);
        if (descriptor == null || actual == null || !actual.id().equals(descriptor.id())
                || criterion.targetType() != CapabilityMetadata.TargetKind.valueOf(
                        descriptor.targetKind().name())
                || !CapabilityTargetMatcher.matches(capabilities, descriptor,
                        criterion, event, identities)
                || (DesktopDiscoveryEvidence.supports(criterion.capabilityId())
                        ? !DesktopDiscoveryEvidence.matches(criterion, event, events)
                        : !subjectMatchesV3(criterion, receipt))) return false;
        try {
            EffectReceiptV1.Status status = EffectReceiptV1.Status.valueOf(receipt.status());
            return TrustedCapabilityRegistry.rank(status)
                    >= TrustedCapabilityRegistry.rank(criterion.requiredEvidence())
                    && TrustedCapabilityRegistry.rank(descriptor.evidenceCeiling())
                    >= TrustedCapabilityRegistry.rank(criterion.requiredEvidence());
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private boolean subjectMatchesV3(TaskCriterionV3 criterion, Receipt receipt) {
        if (criterion.capabilityId().equals("mcp.secure_input.cancel")) {
            return !criterion.requiredSubject().isBlank()
                    && criterion.requiredSubject().equals(receipt.subject());
        }
        if (criterion.capabilityId().equals("browser.observe")) {
            return (criterion.requiredSubject().isBlank()
                        || com.javaclaw.framework.spi.BrowserReceiptProof.contentMatches(
                            criterion.requiredSubject(), receipt.metadata()))
                    && (criterion.requiredTextFragments().isEmpty()
                        || com.javaclaw.framework.spi.BrowserReceiptProof.contentMatchesAll(
                            criterion.requiredTextFragments(), receipt.metadata()));
        }
        if (criterion.requiredSubject().isBlank()) return true;
        if (criterion.capabilityId().equals("file.write")
                || criterion.capabilityId().equals("file.read")) {
            // File content is case-sensitive and must come from the host's actual observation.
            return FileContentProof.matches(criterion.requiredSubject(), receipt.metadata());
        }
        return criterion.requiredSubject().equalsIgnoreCase(receipt.subject())
                || (criterion.capabilityId().equals("desktop.observe")
                        && conditionProof.matches(criterion.id(),
                                criterion.requiredSubject(), receipt.metadata()));
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
                .map(TaskReceiptMatcher::receipt)
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

    /** 使用相同配置验收桌面链，不将模型文字当成收据。 */
    public TaskResult evaluateV2(TaskContractV2 contract, List<RunEventEnvelope> events, String stopReason) {
        return desktop.evaluateV2(contract, events, stopReason);
    }

    public List<VerifiedActionEvidence> verifiedActionEvidence(TaskContractV2 contract, List<RunEventEnvelope> events) {
        return desktop.verifiedActionEvidence(contract, events);
    }

    public List<VerifiedCheckpointEvidence> verifiedCheckpointEvidence(TaskContractV2 contract, List<RunEventEnvelope> events) {
        return desktop.verifiedCheckpointEvidence(contract, events);
    }

    public List<VerifiedCheckpointEvidence> verifiedBusinessCheckpointEvidence(TaskContractV2 contract, List<RunEventEnvelope> events) {
        return desktop.verifiedBusinessCheckpointEvidence(contract, events);
    }

    public record VerifiedActionEvidence(String invocationId, String sessionId,
            String targetId, String actionObservationId, String evidenceObservationId) { }

    public record VerifiedCheckpointEvidence(VerifiedActionEvidence proof,
            String clickCriterionId, String viewCriterionId, String requiredSubject,
            String observationEvidenceRef) { }

    static boolean isBusinessToolStart(String type, String producer,
                                       com.fasterxml.jackson.databind.JsonNode payload) {
        if (!"core.tool.started".equals(type) || !"framework.core".equals(producer)) return false;
        return payload == null || (!payload.path("trustedContextRead").asBoolean(false)
                && !payload.path("trustedToolCatalog").asBoolean(false)
                && !isInteractionDelegationControl(payload)
                && !isKnowledgeAnswerRead(payload));
    }

    /** 委派先由宿主验证合同，实际业务由子任务执行；控制请求本身不改变问答分类。 */
    private static boolean isInteractionDelegationControl(com.fasterxml.jackson.databind.JsonNode payload) {
        var trusted = payload.path("trustedInteractionDelegate");
        return trusted.isBoolean() && trusted.booleanValue()
                && InteractionExecutionPolicy.DELEGATE_TOOL.equals(payload.path("tool").asText(""));
    }

    /** Exact host KB reads supply answer material, without relaxing declared host criteria. */
    private static boolean isKnowledgeAnswerRead(com.fasterxml.jackson.databind.JsonNode payload) {
        if (!payload.path("trustedDesktopTool").isBoolean()
                || !payload.path("trustedDesktopTool").booleanValue()
                || !payload.path("idempotent").isBoolean()
                || !payload.path("idempotent").booleanValue()
                || !com.javaclaw.framework.spi.ToolEffectPolicy.LEGACY.name().equals(
                        payload.path("effectPolicy").asText(""))) return false;
        String name = payload.path("tool").asText("");
        return com.javaclaw.agent.ToolRiskRegistry.matchesHostImplementation(
                name, com.javaclaw.agent.expert.KnowledgeExpert.class)
                && com.javaclaw.agent.ToolRiskRegistry.isKnownHostReadOnly(name);
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

}
