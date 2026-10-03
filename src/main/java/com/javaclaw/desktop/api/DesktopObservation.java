package com.javaclaw.desktop.api;

import java.util.List;

/** A window frame and bounded accessibility catalog admitted under one opaque ID. */
public record DesktopObservation(String sessionId, String observationId,
                                 DesktopFrame frame, List<DesktopElement> elements,
                                 List<DesktopVisualRegion> visualRegions,
                                 String elementDiagnostics) {
    public DesktopObservation {
        if (sessionId == null || sessionId.isBlank() || observationId == null
                || observationId.isBlank() || frame == null)
            throw new IllegalArgumentException("invalid desktop observation");
        elements = elements == null ? List.of() : List.copyOf(elements);
        visualRegions = visualRegions == null ? List.of() : List.copyOf(visualRegions);
        elementDiagnostics = elementDiagnostics == null ? "" : elementDiagnostics;
    }

    public DesktopObservation(String sessionId, String observationId,
                              DesktopFrame frame, List<DesktopElement> elements,
                              List<DesktopVisualRegion> visualRegions) {
        this(sessionId, observationId, frame, elements, visualRegions, "");
    }

    public DesktopObservation(String sessionId, String observationId,
                              DesktopFrame frame, List<DesktopElement> elements) {
        this(sessionId, observationId, frame, elements, List.of(), "");
    }
}
