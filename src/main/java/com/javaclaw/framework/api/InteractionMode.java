package com.javaclaw.framework.api;

/** Requested interaction domain; AUTO and HYBRID still require one active backend per step. */
public enum InteractionMode {
    AUTO,
    BROWSER,
    DESKTOP,
    HYBRID;

    public boolean executable() {
        return this == BROWSER || this == DESKTOP;
    }
}
