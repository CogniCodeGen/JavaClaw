package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.InteractionHistoryClient;
import com.javaclaw.framework.api.InteractionSurfaceEvent;
import com.javaclaw.framework.core.InteractionExecutionPolicy;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.util.SensitiveDataRedactor;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.EnumMap;
import java.util.HashSet;

/** 同一宿主子会话的少量历史发现线索，不恢复输入基线、句柄或验收证据。 */
final class InteractionHistoryContext {
    private static final int MAX_CHARACTERS = 6_000;
    private InteractionHistoryContext() { }

    static SystemMessage message(ReasoningRequest request, RunStore runs, ObjectMapper json,
            InteractionHistoryClient history) {
        return message(request, runs, json, history, null);
    }

    static SystemMessage message(ReasoningRequest request, RunStore runs, ObjectMapper json,
            InteractionHistoryClient history, OnDemandHistoryCatalog.DesktopObservation observation) {
        if (history == null || !InteractionExecutionPolicy.isInteraction(request.runRequest())) return null;
        var scope = request.runRequest().scope();
        var payload = JsonNodeFactory.instance.objectNode().put("historicalOnly", true)
                .put("freshObservation", false).put("inputAuthority", false).put("acceptanceEvidence", false);
        var surfaces = payload.putArray("recentTargets");
        var relations = payload.putArray("recentRelationsAndReceipts");
        try {
            // Use the exact child scope overload: the RunId overload ascends to the parent conversation.
            var past = history.history(scope, 96);
            if (!past.logicalMainScope().equals(scope)) return null;
            CoveredDesktop covered = CoveredDesktop.from(request, runs, observation);
            payload.put("historyTruncated", past.truncated());
            var seen = new HashSet<String>();
            var seenRelations = new HashSet<String>();
            var counts = new EnumMap<InteractionSurfaceEvent.Mode, Integer>(InteractionSurfaceEvent.Mode.class);
            for (int index = past.entries().size() - 1; index >= 0; index--) {
                var entry = past.entries().get(index);
                var owner = runs.find(entry.ownerRunId()).orElse(null);
                if (owner == null || !InteractionCheckpointContext.samePrincipal(
                        request.runRequest(), owner.request(), runs, json)) continue;
                var surface = entry.surface();
                if (covered != null && entry.ownerRunId().equals(request.runId())) {
                    if (surface != null && ordinarySurface(surface) && covered.matches(surface)
                            && !surface.observedAt().isAfter(covered.capturedAt())
                            && past.entries().stream().anyMatch(previous ->
                                previous.ownerRunId().equals(request.runId())
                                    && previous.eventSequence() < entry.eventSequence()
                                    && previous.surface() != null && covered.matches(previous.surface()))) continue;
                    // Surface sequences belong to ThreadStore; only receipts use this Run's sequence.
                    if (surface == null && entry.eventSequence() <= covered.receiptSequence()
                            && ordinaryReceipt(entry)
                            && !entry.associatedSurfaces().isEmpty()
                            && entry.associatedSurfaces().stream().allMatch(covered::matches)) continue;
                }
                if (surface != null) {
                    String identity = surface.mode() + "/" + surface.runtimeId() + "/"
                            + surface.contextId() + "/" + surface.surfaceId();
                    if (!surface.surfaceId().isBlank() && seen.add(identity)
                            && counts.getOrDefault(surface.mode(), 0) < 3) {
                        ObjectNode row = base(entry.ownerRunId().value(), entry.evidenceRef())
                                .put("mode", surface.mode().name()).put("lastEvent", surface.kind().name())
                                .put("runtimeId", surface.runtimeId()).put("contextId", surface.contextId())
                                .put("surfaceId", surface.surfaceId()).put("logicalTargetId", surface.logicalTargetId())
                                .put("documentId", surface.documentId()).put("generation", surface.generation())
                                .put("openerSurfaceId", surface.relatedSurfaceId())
                                .put("relationshipProof", surface.relationProof().name())
                                .put("applicationId", SensitiveDataRedactor.redactText(surface.applicationId()))
                                .put("origin", SensitiveDataRedactor.redactText(surface.urlOrigin()));
                        if (addBounded(payload, surfaces, row)) counts.merge(surface.mode(), 1, Integer::sum);
                    }
                    String relationKey = identity + "/" + surface.relatedSurfaceId() + "/" + surface.causeProof()
                            + "/" + surface.causedByInvocationId() + "/" + surface.kind();
                    if (relations.size() < 6 && seenRelations.add(relationKey) && (!surface.relatedSurfaceId().isBlank()
                            || surface.causeProof() != InteractionSurfaceEvent.CauseProof.UNKNOWN
                            || surface.kind() == InteractionSurfaceEvent.Kind.PAGE_CLOSED
                            || surface.kind() == InteractionSurfaceEvent.Kind.SURFACE_CLOSED
                            || surface.kind() == InteractionSurfaceEvent.Kind.CONTEXT_CLOSED)) {
                        addBounded(payload, relations, base(entry.ownerRunId().value(), entry.evidenceRef())
                                .put("mode", surface.mode().name()).put("event", surface.kind().name())
                                .put("runtimeId", surface.runtimeId()).put("contextId", surface.contextId())
                                .put("surfaceId", surface.surfaceId()).put("relatedSurfaceId", surface.relatedSurfaceId())
                                .put("relationship", surface.relation().name())
                                .put("relationshipProof", surface.relationProof().name())
                                .put("actionAssociation", surface.causeProof().name())
                                .put("sourceSurfaceId", surface.sourceSurfaceId())
                                .put("invocationId", surface.causedByInvocationId()));
                    }
                } else if (relations.size() < 6 && "core.tool.receipt".equals(entry.kind())) {
                    ObjectNode row = base(entry.ownerRunId().value(), entry.evidenceRef())
                            .put("mode", entry.mode().name()).put("tool", entry.tool())
                            .put("invocationId", entry.invocationId()).put("status", entry.status());
                    var ids = row.putArray("observedSurfaceIds");
                    entry.associatedSurfaces().stream().limit(2).forEach(value -> ids.add(value.surfaceId()));
                    addBounded(payload, relations, row);
                }
            }
            if (!runs.readable(scope)) return null;
        } catch (RuntimeException unavailable) {
            return null; // Supplemental discovery cannot prevent the normal fresh-observation path.
        }
        if (surfaces.isEmpty() && relations.isEmpty()) return null;
        return new SystemMessage("宿主交互历史发现线索：仅当前稳定子会话，内容有界且可能不完整。"
                + "所有旧ID、关闭页面和旧窗口只用于重新发现；不能作为当前句柄、输入基线、权限或验收证明。"
                + "先使用当前后端的发现/选择接口，并取得真实新观察。"
                + "OPENER只证明来源页面关系；EXPECTED_POPUP_MATCH只证明预注册等待匹配，不证明点击导致弹窗；"
                + "DIRECT_CREATE表示宿主直接创建。历史回执引用不重新结算未知效果。\n" + payload);
    }

    private static ObjectNode base(String runId, String ref) {
        return JsonNodeFactory.instance.objectNode().put("sourceRunId", runId).put("evidenceRef", ref);
    }

    private static boolean ordinarySurface(InteractionSurfaceEvent surface) {
        return (surface.kind() == InteractionSurfaceEvent.Kind.SURFACE_OBSERVED
                || surface.kind() == InteractionSurfaceEvent.Kind.SURFACE_CHECKPOINT)
                && surface.relation() == InteractionSurfaceEvent.Relation.UNKNOWN
                && surface.relationProof() == InteractionSurfaceEvent.RelationProof.UNKNOWN
                && surface.causeProof() == InteractionSurfaceEvent.CauseProof.UNKNOWN;
    }

    private static boolean ordinaryReceipt(com.javaclaw.framework.api.InteractionHistory.Entry entry) {
        return entry.mode() == InteractionSurfaceEvent.Mode.DESKTOP && entry.kind().equals("core.tool.receipt")
                && java.util.Set.of("ACCEPTED", "OBSERVED", "VERIFIED").contains(entry.status())
                // SENT mutations can still have an UNKNOWN business result; keep their history rows.
                && java.util.Set.of("desktop_session_open", "desktop_session_observe").contains(entry.tool())
                && entry.associatedSurfaces().stream().allMatch(InteractionHistoryContext::ordinarySurface);
    }

    /** Only an exact current host frame can cover same-run, same-surface display history. */
    private record CoveredDesktop(String runtimeId, String contextId, String surfaceId,
            String targetId, long generation, long receiptSequence, java.time.Instant capturedAt) {
        static CoveredDesktop from(ReasoningRequest request, RunStore runs,
                OnDemandHistoryCatalog.DesktopObservation observation) {
            if (observation == null) return null;
            var raw = observation.data();
            var stage = raw.path("interactionStage");
            if (!stage.isObject() || !stage.path("schemaVersion").isInt() || stage.path("schemaVersion").intValue() != 1
                    || !stage.path("kind").asText().equals("observation") || !stage.path("mode").asText().equals("DESKTOP")
                    || !stage.path("complete").isBoolean() || !stage.path("complete").booleanValue()
                    || stage.path("runtimeId").asText().isBlank() || stage.path("surfaceId").asText().isBlank()
                    || !stage.path("contextId").asText().equals(observation.sessionId())
                    || !stage.path("targetId").asText().equals(observation.targetId())
                    || !stage.path("observationId").asText().equals(observation.observationId())
                    || !stage.path("generation").isIntegralNumber() || stage.path("generation").longValue() < 1
                    || stage.path("generation").longValue() != raw.path("windowGeneration").asLong()
                    || stage.path("contentRevision").asLong(-1) != raw.path("contentRevision").asLong(-2)
                    || !stage.path("capturedAtMillis").isIntegralNumber()
                    || stage.path("capturedAtMillis").longValue() <= 0
                    || stage.path("capturedAtMillis").asLong(-1) != raw.path("capturedAtMillis").asLong(-2)) return null;
            var receipts = runs.eventsAfter(request.runId(), 0).stream().filter(event ->
                    event.runId().equals(request.runId().value()) && event.type().equals("core.tool.receipt")
                        && event.schemaVersion() == 1 && event.producer().equals("framework.core")
                        && event.payload().path("tool").asText().equals("desktop_session_observe")
                        && event.payload().path("status").asText().equals("OBSERVED")
                        && event.payload().path("metadata").path("observationId").asText().equals(observation.observationId())
                        && event.payload().path("metadata").path("sessionId").asText().equals(observation.sessionId())
                        && event.payload().path("metadata").path("targetId").asText().equals(observation.targetId())).toList();
            if (receipts.size() != 1) return null;
            return new CoveredDesktop(stage.path("runtimeId").asText(), stage.path("contextId").asText(),
                    stage.path("surfaceId").asText(), observation.targetId(), stage.path("generation").longValue(),
                    receipts.getFirst().sequence(), java.time.Instant.ofEpochMilli(stage.path("capturedAtMillis").longValue()));
        }

        boolean matches(InteractionSurfaceEvent surface) {
            return surface.mode() == InteractionSurfaceEvent.Mode.DESKTOP && surface.runtimeId().equals(runtimeId)
                    && surface.contextId().equals(contextId) && surface.surfaceId().equals(surfaceId)
                    && surface.logicalTargetId().equals(targetId) && surface.generation() == generation;
        }
    }

    private static boolean addBounded(ObjectNode payload, ArrayNode array, ObjectNode row) {
        array.add(row);
        if (payload.toString().length() <= MAX_CHARACTERS) return true;
        array.remove(array.size() - 1);
        return false;
    }
}
