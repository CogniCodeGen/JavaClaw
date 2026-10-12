package com.javaclaw.framework.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Host-observed surface identity. It is history, never fresh observation or input authority.
 * The legacy causedByInvocationId component names the source invocation for OBSERVED_AFTER;
 * in that case it records observation order and does not assert creation causality.
 */
public record InteractionSurfaceEvent(
        String eventId, Instant observedAt, Mode mode, Kind kind,
        String runtimeId, String contextId, String surfaceId, String documentId,
        String logicalTargetId, String applicationId, long generation, long contentRevision,
        String relatedSurfaceId, Relation relation, RelationProof relationProof,
        String observedDuringInvocationId, String urlOrigin, String urlHash,
        String causedByInvocationId, String sourceSurfaceId, CauseProof causeProof) {
    public enum Mode { BROWSER, DESKTOP }
    public enum Kind { CONTEXT_OPENED, CONTEXT_CLOSED, PAGE_OPENED, PAGE_NAVIGATED, PAGE_CLOSED,
        PAGE_CREATED, PAGE_POPUP_MATCHED, SURFACE_OBSERVED, SURFACE_CHECKPOINT, SURFACE_CLOSED,
        WINDOW_DISCOVERED, WINDOW_OPENED, WINDOW_HIDDEN, WINDOW_SHOWN, WINDOW_UNAVAILABLE, WINDOW_CLOSED }
    public enum Relation { OPENER, PARENT, UNKNOWN }
    public enum RelationProof { HOST_PROVEN, UNKNOWN }
    /** OBSERVED_AFTER records order and source, never that the source created the window. */
    public enum CauseProof { DIRECT_CREATE, EXPECTED_POPUP_MATCH, OBSERVED_AFTER, UNKNOWN }

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
        } else if (relatedSurfaceId.isBlank() || relatedSurfaceId.equals(surfaceId)
                || relation == Relation.OPENER && mode != Mode.BROWSER
                || relation == Relation.PARENT && mode != Mode.DESKTOP) {
            throw new IllegalArgumentException("a distinct host-proven opener or native parent is required");
        }
        if (desktopWindowKind(kind) && mode != Mode.DESKTOP)
            throw new IllegalArgumentException("native window lifecycle requires desktop mode");
        observedDuringInvocationId = bounded(observedDuringInvocationId, 512);
        urlOrigin = bounded(urlOrigin, 1024);
        urlHash = bounded(urlHash, 128);
        causedByInvocationId = bounded(causedByInvocationId, 512);
        sourceSurfaceId = bounded(sourceSurfaceId, 256);
        causeProof = causeProof == null ? CauseProof.UNKNOWN : causeProof;
        if (causeProof == CauseProof.UNKNOWN) {
            causedByInvocationId = "";
            sourceSurfaceId = "";
        } else if (causedByInvocationId.isBlank()
                || causeProof == CauseProof.DIRECT_CREATE && (mode != Mode.BROWSER || kind != Kind.PAGE_CREATED)
                || causeProof == CauseProof.EXPECTED_POPUP_MATCH && (mode != Mode.BROWSER
                    || kind != Kind.PAGE_POPUP_MATCHED || sourceSurfaceId.isBlank()
                    || !sourceSurfaceId.equals(relatedSurfaceId)
                    || relation != Relation.OPENER || relationProof != RelationProof.HOST_PROVEN)
                || causeProof == CauseProof.OBSERVED_AFTER && (mode != Mode.DESKTOP
                    || kind != Kind.WINDOW_DISCOVERED || sourceSurfaceId.isBlank()
                    || sourceSurfaceId.equals(surfaceId))) {
            throw new IllegalArgumentException("host page proof or desktop after-observation source is required");
        }
    }

    private static boolean desktopWindowKind(Kind kind) {
        return switch (kind) {
            case WINDOW_DISCOVERED, WINDOW_OPENED, WINDOW_HIDDEN, WINDOW_SHOWN, WINDOW_UNAVAILABLE, WINDOW_CLOSED -> true;
            default -> false;
        };
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

    /** A newly discovered candidate observed after a dispatched input, without creation causality. */
    public InteractionSurfaceEvent withObservedAfter(String invocationId, String sourceId) {
        return new InteractionSurfaceEvent(eventId, observedAt, mode, kind, runtimeId, contextId,
                surfaceId, documentId, logicalTargetId, applicationId, generation, contentRevision,
                relatedSurfaceId, relation, relationProof, observedDuringInvocationId, urlOrigin, urlHash,
                invocationId, sourceId, CauseProof.OBSERVED_AFTER);
    }
}
