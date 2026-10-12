package com.javaclaw.framework.api;

import java.time.Instant;
import java.util.List;

/** Bounded audit projection. Evidence references do not renew observation or clear unknown effects. */
public record InteractionHistory(RunScope logicalMainScope, List<Entry> entries, boolean truncated) {
    public InteractionHistory { entries = List.copyOf(entries); }

    public record Entry(String eventId, RunId ownerRunId, long eventSequence, Instant observedAt,
                        InteractionSurfaceEvent.Mode mode, String kind, String invocationId,
                        String tool, String status, InteractionSurfaceEvent surface, String evidenceRef,
                        List<InteractionSurfaceEvent> associatedSurfaces) {
        public Entry { associatedSurfaces = List.copyOf(associatedSurfaces == null ? List.of() : associatedSurfaces); }
        public Entry(String eventId, RunId ownerRunId, long eventSequence, Instant observedAt,
                     InteractionSurfaceEvent.Mode mode, String kind, String invocationId,
                     String tool, String status, InteractionSurfaceEvent surface, String evidenceRef) {
            this(eventId, ownerRunId, eventSequence, observedAt, mode, kind, invocationId, tool, status,
                    surface, evidenceRef, List.of());
        }
        public Entry withAssociatedSurfaces(List<InteractionSurfaceEvent> surfaces) {
            return new Entry(eventId, ownerRunId, eventSequence, observedAt, mode, kind, invocationId,
                    tool, status, surface, evidenceRef, surfaces);
        }
    }
}
