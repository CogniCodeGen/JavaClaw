package com.javaclaw.agent.vision;

/** A view name supported by separate visible heading and main-content regions. */
public record DesktopVisualActiveView(
        String label,
        DesktopVisualTarget heading,
        DesktopVisualTarget content,
        double confidence) { }
