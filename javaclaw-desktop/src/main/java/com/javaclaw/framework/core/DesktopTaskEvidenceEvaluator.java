package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.core.TaskResultEvaluator.VerifiedActionEvidence;
import com.javaclaw.framework.core.TaskResultEvaluator.VerifiedCheckpointEvidence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.javaclaw.framework.core.TaskReceiptMatcher.*;
import static com.javaclaw.framework.core.TaskResultEvaluator.isBusinessToolStart;

/** 校验有序桌面收据、同帧绑定及操作后的新观察，独立于模型完成申请。 */
final class DesktopTaskEvidenceEvaluator {
    private final DesktopConditionProof conditionProof;
    private final TaskReceiptMatcher matcher;

    DesktopTaskEvidenceEvaluator(ObjectMapper json) {
        conditionProof = new DesktopConditionProof(json);
        matcher = new TaskReceiptMatcher(json);
    }

    /** 桌面验收要求精确会话与窗口绑定，并在每次输入后取得新帧。 */
    public TaskResult evaluateV2(
            TaskContractV2 contract, List<RunEventEnvelope> events, String stopReason) {
        return evaluateV2(contract, events, stopReason, TrustedCapabilityRegistry.builtins());
    }

    TaskResult evaluateV2(TaskContractV2 contract,
            List<RunEventEnvelope> events, String stopReason,
            TrustedCapabilityRegistry capabilities) {
        return evaluateV2(contract, events, stopReason, capabilities, null);
    }

    TaskResult evaluateV2(TaskContractV2 contract,
            List<RunEventEnvelope> events, String stopReason,
            TrustedCapabilityRegistry capabilities, Map<String, String> evidenceBindings) {
        return evaluateV2(contract, events, stopReason, capabilities, evidenceBindings, Set.of());
    }

    TaskResult evaluateV2(TaskContractV2 contract,
            List<RunEventEnvelope> events, String stopReason,
            TrustedCapabilityRegistry capabilities, Map<String, String> evidenceBindings,
            Set<String> historicalViewIds) {
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
            return TaskResultEvaluator.evaluate(new TaskContractV1(1, contract.originalRequest(), contract.target(),
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
                if (evidenceBindings != null && !event.payload().path("evidenceRef").asText("")
                        .equals(evidenceBindings.get(criterion.id()))) continue;
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
                if (!matcher.matches(criterion, current, event, identities, capabilities)) continue;
                String operation = normalized(current.operation());
                var metadata = event.payload().path("metadata");
                if (operation.equals("open")) {
                    if (metadata.path("sessionId").asText("").isBlank()
                            || metadata.path("targetId").asText("").isBlank()) continue;
                } else if (operation.equals("observe") || operation.equals("snapshot")) {
                    if (session.isBlank()) {
                        // 纯观察任务可使用当前帧作为基线，但请求视图必须有结构化证明。
                        if (contract.criteria().stream().anyMatch(c ->
                                isDesktopAction(c.requiredOperation()))
                                || criterion.requiredSubject().isBlank()
                                || metadata.path("sessionId").asText("").isBlank()
                                || metadata.path("targetId").asText("").isBlank()
                                || !validFrame(metadata)
                                || !conditionProof.hasViewEvidence(criterion.id(),
                                        criterion.requiredSubject(), metadata)) continue;
                        foundBaseline = index;
                    } else {
                        if (!session.equals(metadata.path("sessionId").asText(""))
                                || !targetId.equals(metadata.path("targetId").asText(""))
                                || (openedAt >= 0 && (!receipts.get(openedAt).runId().equals(event.runId())
                                        || !sameApplication(receipts.get(openedAt), event)))
                                || !validFrame(metadata)
                                || (!criterion.requiredSubject().isBlank()
                                        && !conditionProof.hasViewEvidence(criterion.id(),
                                                criterion.requiredSubject(), metadata))
                                || !freshAfterActions(receipts, openedAt, index, session, targetId,
                                        metadata, !criterion.requiredSubject().isBlank())) continue;
                    }
                    // 最终视图必须仍然有效；显式有序的较早视图只记录自己的帧，后续条件仍需真实收据。
                    if (!historicalViewIds.contains(criterion.id()) && laterDesktopAction(receipts, index,
                            metadata.path("sessionId").asText(""),
                            metadata.path("targetId").asText(""))) continue;
                } else if (isDesktopAction(operation)) {
                    int prior = priorObservedFrame(receipts, index, event);
                    if (prior < 0) continue;
                    if (session.isBlank()) {
                        // 前序观察可不是合同条件，但必须是可信收据且对应输入声明使用的精确帧。
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

    /** 输入必须指向当前 Run、会话和窗口最近一次可信观察帧。 */
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

    private boolean futureViewConfirms(TaskContractV2 contract,
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
                    && matcher.matches(c, observation, observed, identities, capabilities, false))) return true;
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

    static boolean isDesktopAction(String operation) {
        return java.util.Set.of("click", "type", "key", "scroll").contains(normalized(operation));
    }

    /** 只有完整宿主拒绝才能证明该输入不可能改变窗口。 */
    private static boolean undispatchedDesktopFailure(RunEventEnvelope event) {
        var payload = event.payload();
        var metadata = payload.path("metadata");
        return isDesktopAction(payload.path("operation").asText(""))
                && payload.path("status").asText("").equals("FAILED")
                && metadata.path("dispatchAttempted").asText("").equals("false")
                && metadata.path("delivery").asText("").equals("NOT_SENT");
    }

    /** 未知原因、过期帧和未决输入均保留旧帧闸门。 */
    private static boolean invalidatedDesktopObservation(RunEventEnvelope event) {
        var metadata = event.payload().path("metadata");
        return metadata.path("desktopStatus").asText("").equals("STALE_FRAME")
                || !Set.of("NO_SEMANTIC_PATH", "ACCESS_DENIED", "PLATFORM_FAILURE",
                        "SESSION_CONTROL_REQUIRED")
                        .contains(metadata.path("reasonCode").asText(""));
    }

    /**
     * 最终视图可核验未知点击后的请求状态，但这与任务完成独立。
     * 解除输入重放闸门之前必须持久化证明；此路径仅适用于未知点击。
     */
    public List<VerifiedActionEvidence> verifiedActionEvidence(
            TaskContractV2 contract, List<RunEventEnvelope> events) {
        return verifiedActionEvidence(contract, events, null);
    }

    List<VerifiedActionEvidence> verifiedActionEvidence(
            TaskContractV2 contract, List<RunEventEnvelope> events,
            Map<String, String> evidenceBindings) {
        if (contract == null) return List.of();
        TaskResult result = evaluateV2(contract, events, "",
                TrustedCapabilityRegistry.builtins(), evidenceBindings);
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
                    || views.stream().noneMatch(c -> matcher.matches(c, observedReceipt, observed,
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
                // 必须证明发生了页面转移；本来已可见的终页或无法分类的前帧都不能证明未知点击的效果。
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

    /**
     * 未完成 Run 只可用预先声明、相邻且已成立的视图条件核销未知点击。
     * 该范围窄于终态验收，合同其他位置的视图不能核验无关点击。
     */
    public List<VerifiedCheckpointEvidence> verifiedCheckpointEvidence(
            TaskContractV2 contract, List<RunEventEnvelope> events) {
        return verifiedCheckpointEvidence(contract, events, false);
    }

    /** 已接受投递仍须满足预声明的业务后置条件；此操作不是未知效果核销。 */
    public List<VerifiedCheckpointEvidence> verifiedBusinessCheckpointEvidence(
            TaskContractV2 contract, List<RunEventEnvelope> events) {
        return verifiedCheckpointEvidence(contract, events, true);
    }

    private List<VerifiedCheckpointEvidence> verifiedCheckpointEvidence(
            TaskContractV2 contract, List<RunEventEnvelope> events, boolean allowAccepted) {
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
                        || !(allowAccepted
                            ? payload.path("status").asText("").equals("ACCEPTED")
                                && actionMeta.path("delivery").asText("").equals("SENT")
                                && actionMeta.path("effect").asText("").equals("UNKNOWN")
                            : payload.path("status").asText("").equals("UNKNOWN")
                                && actionMeta.path("delivery").asText("").equals("MAYBE_SENT"))
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
                // 所有更早合同条件都必须在该输入之前已经成立。
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
                            || !matcher.matches(view, observation, observed, identities,
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

}
