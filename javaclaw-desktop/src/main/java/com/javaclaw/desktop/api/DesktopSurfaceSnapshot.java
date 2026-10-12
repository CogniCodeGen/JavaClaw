package com.javaclaw.desktop.api;

/** Last native-captured actual window identity, separate from the consent-bound logical target. */
public record DesktopSurfaceSnapshot(String providerId, String runtimeId, String surfaceId,
                                     String logicalTargetId, String applicationId,
                                     long generation, long contentRevision, long observedAtMillis,
                                     String parentTargetId, String relationProof) {
    public DesktopSurfaceSnapshot {
        if (providerId == null || runtimeId == null || surfaceId == null || logicalTargetId == null
                || applicationId == null || generation < 0 || contentRevision < 0 || observedAtMillis < 0)
            throw new IllegalArgumentException("invalid desktop surface identity");
        parentTargetId = parentTargetId == null ? "" : parentTargetId;
        relationProof = DesktopTarget.NATIVE_PARENT.equals(relationProof) && !parentTargetId.isBlank()
                && !parentTargetId.equals(surfaceId) ? DesktopTarget.NATIVE_PARENT : DesktopTarget.UNKNOWN;
        if (DesktopTarget.UNKNOWN.equals(relationProof)) parentTargetId = "";
    }

    public DesktopSurfaceSnapshot(String providerId, String runtimeId, String surfaceId,
            String logicalTargetId, String applicationId, long generation, long contentRevision,
            long observedAtMillis) {
        this(providerId, runtimeId, surfaceId, logicalTargetId, applicationId, generation,
                contentRevision, observedAtMillis, "", DesktopTarget.UNKNOWN);
    }
    // A process instance groups windows; it never proves their parent relationship.
}
