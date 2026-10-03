package com.javaclaw.desktop.api;

public record DesktopSessionState(String sessionId, Kind kind, String detail, long atMillis) {
    public enum Kind { LIVE, PAUSED, FOREGROUND_REQUIRED, FOREGROUND_READY, CLOSED }

    public DesktopSessionState {
        detail = detail == null ? "" : detail;
    }
}
