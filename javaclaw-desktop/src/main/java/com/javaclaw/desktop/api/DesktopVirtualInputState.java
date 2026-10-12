package com.javaclaw.desktop.api;

/** Transient, owner-scoped preview feedback; never contains text or key names. */
public record DesktopVirtualInputState(String sessionId, long frameGeneration,
        double frameX, double frameY, boolean visible, int button, Phase phase, long atMillis,
        long actionId, DesktopActionResult.Delivery delivery) {
    public enum Phase { IDLE, TARGETING, POINTING, PRESSED, TYPING, FINISHED }

    public DesktopVirtualInputState {
        if (sessionId == null || sessionId.isBlank() || frameGeneration < 1
                || !Double.isFinite(frameX) || !Double.isFinite(frameY)
                || frameX < 0 || frameY < 0 || button < 0 || button > 3
                || phase == null || atMillis < 0 || actionId < 0
                || (phase == Phase.FINISHED) != (delivery != null))
            throw new IllegalArgumentException("invalid virtual input state");
    }

    /** Legacy positions are preparation feedback, never proof of a delivered press. */
    public DesktopVirtualInputState(String sessionId, long frameGeneration,
            double frameX, double frameY, boolean visible, int button, Phase phase, long atMillis) {
        this(sessionId, frameGeneration, frameX, frameY, visible, button, phase, atMillis, 0,
                phase == Phase.FINISHED ? DesktopActionResult.Delivery.MAYBE_SENT : null);
    }
}
