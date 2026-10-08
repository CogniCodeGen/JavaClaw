package com.javaclaw.desktop.api;

/** Last native-captured actual window identity, separate from the consent-bound logical target. */
public record DesktopSurfaceSnapshot(String providerId, String runtimeId, String surfaceId,
                                     String logicalTargetId, String applicationId,
                                     long generation, long contentRevision, long observedAtMillis) {
    public DesktopSurfaceSnapshot {
        if (providerId == null || runtimeId == null || surfaceId == null || logicalTargetId == null
                || applicationId == null || generation < 0 || contentRevision < 0 || observedAtMillis < 0)
            throw new IllegalArgumentException("invalid desktop surface identity");
    }
    // Native ABI 6 has no public owner/parent proof. Same PID is never a parent relationship.
}
