package com.javaclaw.desktop.api;

import java.util.List;

/** Non-destructive bounded history. Advance a consumer cursor only after durable persistence succeeds. */
public record DesktopWindowTrackingSnapshot(List<DesktopWindowTrackingEvent> events,
        long firstAvailableSequence, long nextSequence, boolean hasMore, boolean truncated) {
    public DesktopWindowTrackingSnapshot { events = List.copyOf(events); }
}
