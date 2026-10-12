package com.javaclaw.desktop.api;

/** Host observation of a window lifecycle. It never grants input or proves action causality. */
public record DesktopWindowTrackingEvent(long sequence, String eventId, long observedAtMillis,
        String providerId, String runtimeId, String surfaceId, String targetId, String applicationId,
        String parentTargetId, String relationProof, Kind kind, String sessionId,
        String sourceInvocationId, String sourceTargetId, String sourceSurfaceId, Association association) {
    public enum Kind { DISCOVERED, OPENED, HIDDEN, SHOWN, UNAVAILABLE, CLOSED }
    public enum Association { NONE, OBSERVED_AFTER }

    public DesktopWindowTrackingEvent {
        if (sequence < 1 || observedAtMillis < 1 || eventId == null || eventId.isBlank()
                || providerId == null || providerId.isBlank() || targetId == null || targetId.isBlank()
                || kind == null || association == null)
            throw new IllegalArgumentException("invalid desktop window tracking event");
        runtimeId = value(runtimeId); surfaceId = value(surfaceId); applicationId = value(applicationId);
        parentTargetId = value(parentTargetId); relationProof = value(relationProof);
        sessionId = value(sessionId); sourceInvocationId = value(sourceInvocationId);
        sourceTargetId = value(sourceTargetId); sourceSurfaceId = value(sourceSurfaceId);
        if (!relationProof.equals("NATIVE_PARENT") || parentTargetId.isBlank()) {
            relationProof = "UNKNOWN"; parentTargetId = "";
        }
        if (association == Association.OBSERVED_AFTER && (kind != Kind.DISCOVERED
                || sessionId.isBlank() || sourceInvocationId.isBlank() || sourceTargetId.isBlank()
                || sourceSurfaceId.isBlank() || surfaceId.isBlank() || sourceSurfaceId.equals(surfaceId)))
            throw new IllegalArgumentException("observed-after association needs distinct actual surfaces");
    }

    private static String value(String value) { return value == null ? "" : value; }
}
