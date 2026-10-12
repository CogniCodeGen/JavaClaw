package com.javaclaw.desktop.api;

import java.util.List;

/** Read-only inventory from one bounded host wait, not a new capture or input baseline. */
public record DesktopWindowCandidates(String sessionId, String sourceTargetId, String sourceInvocationId,
        List<DesktopWindowCandidate> candidates, long observedAtMillis, long waitedMillis,
        boolean timedOut, boolean inventoryAvailable, boolean truncated) {
    public DesktopWindowCandidates { candidates = List.copyOf(candidates); }
}
