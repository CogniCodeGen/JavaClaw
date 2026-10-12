package com.javaclaw.desktop.api;

/** A current discoverable target; selecting it still requires an explicit owner-scoped open. */
public record DesktopWindowCandidate(DesktopTarget target, String runtimeId, String surfaceId,
        boolean selected, boolean observedAfterAction) { }
