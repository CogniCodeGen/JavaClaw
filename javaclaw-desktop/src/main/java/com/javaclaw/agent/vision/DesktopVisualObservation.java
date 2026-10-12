package com.javaclaw.agent.vision;

import java.util.List;

/** Descriptions stay untrusted; validated condition evidence must still be bound to a live frame. */
public record DesktopVisualObservation(
        String summary,
        String visibleText,
        List<DesktopVisualTarget> targets,
        DesktopVisualActiveView activeView,
        List<DesktopVisualConditionEvidence> conditionEvidence,
        List<DesktopVisualConditionResult> conditionResults) {

    public DesktopVisualObservation {
        targets = List.copyOf(targets == null ? List.of() : targets);
        conditionEvidence = List.copyOf(conditionEvidence == null ? List.of() : conditionEvidence);
        conditionResults = List.copyOf(conditionResults == null ? List.of() : conditionResults);
    }

    public DesktopVisualObservation(String summary, String visibleText,
                                    List<DesktopVisualTarget> targets,
                                    DesktopVisualActiveView activeView,
                                    List<DesktopVisualConditionEvidence> conditionEvidence) {
        this(summary, visibleText, targets, activeView, conditionEvidence, List.of());
    }

    public DesktopVisualObservation(String summary, String visibleText,
                                    List<DesktopVisualTarget> targets,
                                    DesktopVisualActiveView activeView) {
        this(summary, visibleText, targets, activeView, List.of());
    }

    public DesktopVisualObservation(String summary, String visibleText,
                                    List<DesktopVisualTarget> targets) {
        this(summary, visibleText, targets, null, List.of());
    }
}
