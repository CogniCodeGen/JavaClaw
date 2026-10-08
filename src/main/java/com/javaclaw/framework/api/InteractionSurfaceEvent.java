package com.javaclaw.framework.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Host-observed surface identity. It is history, never fresh observation or input authority. */
public record InteractionSurfaceEvent(
        String eventId, Instant observedAt, Mode mode, Kind kind,
        String runtimeId, String contextId, String surfaceId, String documentId,
        String logicalTargetId, String applicationId, long generation, long contentRevision,
        String relatedSurfaceId, Relation relation, RelationProof relationProof,
        String observedDuringInvocationId, String urlOrigin, String urlHash,
        String causedByInvocationId, String sourceSurfaceId, CauseProof causeProof) {
    public enum Mode { BROWSER, DESKTOP }
    public enum Kind { CONTEXT_OPENED, CONTEXT_CLOSED, PAGE_OPENED, PAGE_NAVIGATED, PAGE_CLOSED,
        PAGE_CREATED, PAGE_POPUP_MATCHED, SURFACE_OBSERVED, SURFACE_CHECKPOINT, SURFACE_CLOSED }
    public enum Relation { OPENER, UNKNOWN }
    public enum RelationProof { HOST_PROVEN, UNKNOWN }
    public enum CauseProof { DIRECT_CREATE, EXPECTED_POPUP_MATCH, UNKNOWN }

    public InteractionSurfaceEvent(String eventId, Instant observedAt, Mode mode, Kind kind,
            String runtimeId, String contextId, String surfaceId, String documentId,
            String logicalTargetId, String applicationId, long generation, long contentRevision,
            String relatedSurfaceId, Relation relation, RelationProof relationProof,
            String observedDuringInvocationId, String urlOrigin, String urlHash) {
        this(eventId, observedAt, mode, kind, runtimeId, contextId, surfaceId, documentId, logicalTargetId,
                applicationId, generation, contentRevision, relatedSurfaceId, relation, relationProof,
                observedDuringInvocationId, urlOrigin, urlHash, "", "", CauseProof.UNKNOWN);
    }

    public InteractionSurfaceEvent {
        eventId = bounded(eventId, 128);
        if (eventId.isBlank()) eventId = UUID.randomUUID().toString();
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        mode = Objects.requireNonNull(mode, "mode");
        kind = Objects.requireNonNull(kind, "kind");
        runtimeId = bounded(runtimeId, 256);
        contextId = bounded(contextId, 256);
        surfaceId = bounded(surfaceId, 256);
        documentId = bounded(documentId, 256);
        logicalTargetId = bounded(logicalTargetId, 256);
        applicationId = bounded(applicationId, 512);
        if (generation < 0 || contentRevision < 0) throw new IllegalArgumentException("negative surface generation");
        relatedSurfaceId = bounded(relatedSurfaceId, 256);
        relation = Objects.requireNonNull(relation, "relation");
        relationProof = Objects.requireNonNull(relationProof, "relationProof");
        if (relation == Relation.UNKNOWN || relationProof == RelationProof.UNKNOWN) {
            relatedSurfaceId = "";
            relation = Relation.UNKNOWN;
            relationProof = RelationProof.UNKNOWN;
        } else if (relatedSurfaceId.isBlank() || mode != Mode.BROWSER) {
            throw new IllegalArgumentException("only an observed browser opener has a proven relationship");
        }
        observedDuringInvocationId = bounded(observedDuringInvocationId, 512);
        urlOrigin = bounded(urlOrigin, 1024);
        urlHash = bounded(urlHash, 128);
        causedByInvocationId = bounded(causedByInvocationId, 512);
        sourceSurfaceId = bounded(sourceSurfaceId, 256);
        causeProof = causeProof == null ? CauseProof.UNKNOWN : causeProof;
        if (causeProof == CauseProof.UNKNOWN) {
            causedByInvocationId = "";
            sourceSurfaceId = "";
        } else if (mode != Mode.BROWSER || causedByInvocationId.isBlank()
                || causeProof == CauseProof.DIRECT_CREATE && kind != Kind.PAGE_CREATED
                || causeProof == CauseProof.EXPECTED_POPUP_MATCH && (kind != Kind.PAGE_POPUP_MATCHED
                    || sourceSurfaceId.isBlank() || !sourceSurfaceId.equals(relatedSurfaceId)
                    || relation != Relation.OPENER || relationProof != RelationProof.HOST_PROVEN)) {
            throw new IllegalArgumentException("page creation or preregistered opener proof is required");
        }
    }

    private static String bounded(String value, int maximum) {
        value = value == null ? "" : value;
        if (value.length() > maximum) throw new IllegalArgumentException("surface identity is too long");
        return value;
    }

    public InteractionSurfaceEvent withObservedDuringInvocation(String invocationId) {
        return new InteractionSurfaceEvent(eventId, observedAt, mode, kind, runtimeId, contextId,
                surfaceId, documentId, logicalTargetId, applicationId, generation, contentRevision,
                relatedSurfaceId, relation, relationProof, invocationId, urlOrigin, urlHash,
                invocationId.isBlank() ? "" : causedByInvocationId,
                invocationId.isBlank() ? "" : sourceSurfaceId,
                invocationId.isBlank() ? CauseProof.UNKNOWN : causeProof);
    }

    public InteractionSurfaceEvent withDirectCreation(String invocationId, String sourceId) {
        return new InteractionSurfaceEvent(eventId, observedAt, mode, Kind.PAGE_CREATED, runtimeId, contextId,
                surfaceId, documentId, logicalTargetId, applicationId, generation, contentRevision,
                relatedSurfaceId, relation, relationProof, observedDuringInvocationId, urlOrigin, urlHash,
                invocationId, sourceId, CauseProof.DIRECT_CREATE);
    }

    public InteractionSurfaceEvent withExpectedPopup(String invocationId, String sourceId) {
        return new InteractionSurfaceEvent(eventId, observedAt, mode, Kind.PAGE_POPUP_MATCHED, runtimeId, contextId,
                surfaceId, documentId, logicalTargetId, applicationId, generation, contentRevision,
                relatedSurfaceId, relation, relationProof, observedDuringInvocationId, urlOrigin, urlHash,
                invocationId, sourceId, CauseProof.EXPECTED_POPUP_MATCH);
    }
}
