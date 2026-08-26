package com.javaclaw.application.agent;

import java.util.LinkedHashSet;
import java.util.Set;

/** Immutable per-turn decision describing the exact model-visible tool surface. */
public record ToolExposureDecision(
        boolean legacyAll,
        Set<String> bundleIds,
        Set<String> allowedGroups,
        Set<String> allowedTools,
        String reason) {

    public ToolExposureDecision {
        bundleIds = Set.copyOf(bundleIds == null ? Set.of() : bundleIds);
        allowedGroups = Set.copyOf(allowedGroups == null ? Set.of() : allowedGroups);
        allowedTools = Set.copyOf(allowedTools == null ? Set.of() : allowedTools);
        reason = reason == null ? "" : reason.strip();
    }

    public static ToolExposureDecision legacyAll(String reason) {
        return new ToolExposureDecision(true, Set.of(), Set.of(), Set.of(), reason);
    }

    public static ToolExposureDecision restricted(
            Set<String> bundles, Set<String> groups, Set<String> tools, String reason) {
        LinkedHashSet<String> safeGroups = new LinkedHashSet<>(groups);
        safeGroups.add("agents");
        safeGroups.add("skill");
        LinkedHashSet<String> safeTools = new LinkedHashSet<>(tools);
        safeTools.add("ask_user_clarification");
        safeTools.add("skill_read");
        return new ToolExposureDecision(false, bundles, safeGroups, safeTools, reason);
    }
}
