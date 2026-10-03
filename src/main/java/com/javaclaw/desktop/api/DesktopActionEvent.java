package com.javaclaw.desktop.api;

import java.util.Objects;

/** Sanitized per-session action progress. Text, key names, and coordinates are never included. */
public record DesktopActionEvent(String sessionId, DesktopAction.Kind kind, Phase phase,
                                 DesktopActionResult.Status result, long windowGeneration,
                                 long atMillis) {
    public enum Phase { STARTED, FINISHED }

    public DesktopActionEvent {
        if (sessionId == null || sessionId.isBlank())
            throw new IllegalArgumentException("session identity is required");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(phase, "phase");
        if ((phase == Phase.STARTED) != (result == null))
            throw new IllegalArgumentException("only a finished action has a result");
        if (windowGeneration < 1 || atMillis < 0)
            throw new IllegalArgumentException("invalid action event metadata");
    }
}
