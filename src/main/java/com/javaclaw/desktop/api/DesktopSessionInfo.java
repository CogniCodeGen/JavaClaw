package com.javaclaw.desktop.api;

/** Public session metadata; no native pointer or platform window handle is exposed. */
public record DesktopSessionInfo(String sessionId, DesktopTarget target, boolean controlGranted,
                                 boolean foregroundGranted, DesktopInputPolicy inputPolicy) {
    public DesktopSessionInfo {
        if (inputPolicy == null) throw new IllegalArgumentException("input policy is required");
        if (foregroundGranted && inputPolicy == DesktopInputPolicy.BACKGROUND_STRICT)
            throw new IllegalArgumentException("strict background session cannot grant system input");
    }

    public DesktopSessionInfo(String sessionId, DesktopTarget target, boolean controlGranted,
                              boolean foregroundGranted) {
        this(sessionId, target, controlGranted, foregroundGranted, foregroundGranted
                ? DesktopInputPolicy.SYSTEM_EXPLICIT : DesktopInputPolicy.BACKGROUND_STRICT);
    }
}
