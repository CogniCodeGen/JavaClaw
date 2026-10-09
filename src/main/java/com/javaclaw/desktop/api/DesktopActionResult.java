package com.javaclaw.desktop.api;

/**
 * ACCEPTED confirms platform input admission, not the requested application outcome.
 * UNKNOWN means dispatch may be incomplete or unconfirmed; callers must not auto-retry.
 * VERIFIED is a low-level control readback and also requires later task observation.
 */
public record DesktopActionResult(Status status, String detail, long windowGeneration,
                                  Mode mode, Reason reason, boolean dispatchAttempted,
                                  String observationId, NextStep nextStep) {
    public enum Status { VERIFIED, UNKNOWN, UNSUPPORTED, STALE_FRAME, DENIED, FAILED, ACCEPTED }
    public enum Delivery { NOT_SENT, MAYBE_SENT, SENT }
    public enum Mode { NONE, BACKGROUND_SEMANTIC, FOREGROUND_SYNTHETIC }
    public enum Reason { NONE, NO_SEMANTIC_PATH, DELIVERY_UNCERTAIN, STALE_OBSERVATION,
        ACCESS_DENIED, INVALID_TARGET, PLATFORM_FAILURE, SESSION_CONTROL_REQUIRED,
        SYSTEM_INPUT_REQUIRED, NO_PROGRESS, POLICY_BLOCKED, UNSUPPORTED_ACTION, TARGET_ACTIVE }
    public enum NextStep { NONE, OBSERVE, RECONCILE, CHECK_PERMISSIONS, OPEN_SESSION }

    public DesktopActionResult {
        if (status == null) throw new IllegalArgumentException("status is required");
        detail = detail == null ? "" : detail;
        mode = mode == null ? Mode.NONE : mode;
        reason = reason == null ? Reason.NONE : reason;
        observationId = observationId == null ? "" : observationId;
        nextStep = nextStep == null ? NextStep.NONE : nextStep;
        if (status == Status.UNSUPPORTED && dispatchAttempted)
            throw new IllegalArgumentException("unsupported input cannot have been dispatched");
        if (status == Status.ACCEPTED && !dispatchAttempted)
            throw new IllegalArgumentException("accepted input must have been dispatched");
    }

    public DesktopActionResult(Status status, String detail, long windowGeneration) {
        this(status, detail, windowGeneration, Mode.NONE, switch (status) {
            case VERIFIED, ACCEPTED -> Reason.NONE;
            case UNKNOWN -> Reason.DELIVERY_UNCERTAIN;
            case UNSUPPORTED -> Reason.NO_SEMANTIC_PATH;
            case STALE_FRAME -> Reason.STALE_OBSERVATION;
            case DENIED -> Reason.ACCESS_DENIED;
            case FAILED -> Reason.PLATFORM_FAILURE;
        }, status == Status.UNKNOWN || status == Status.VERIFIED || status == Status.ACCEPTED,
                "", NextStep.NONE);
    }

    /** A transport classification shared by tool payloads and trusted receipts. */
    public Delivery delivery() {
        if (status == Status.ACCEPTED || status == Status.VERIFIED) return Delivery.SENT;
        return status == Status.UNKNOWN || dispatchAttempted ? Delivery.MAYBE_SENT : Delivery.NOT_SENT;
    }

    public DesktopActionResult withContext(Mode mode, String observationId, NextStep nextStep) {
        return new DesktopActionResult(status, detail, windowGeneration, mode, reason,
                dispatchAttempted, observationId, nextStep);
    }
}
