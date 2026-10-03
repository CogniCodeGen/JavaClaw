package com.javaclaw.desktop.api;

/** Public session metadata; no native pointer or platform window handle is exposed. */
public record DesktopSessionInfo(String sessionId, DesktopTarget target, boolean controlGranted,
                                 boolean foregroundGranted) {}
