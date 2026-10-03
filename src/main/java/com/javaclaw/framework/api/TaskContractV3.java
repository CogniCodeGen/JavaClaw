package com.javaclaw.framework.api;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Version 3 contract: capability IDs and typed targets replace operation-word guesses. */
public record TaskContractV3(int version, String originalRequest,
                             List<TaskCriterionV3> criteria, boolean applicable,
                             boolean reliable, String source,
                             List<String> reasonCodes, List<String> unresolvedInputs,
                             DesktopObservationPolicy desktopObservationPolicy) {
    /** Chosen by the host; model output cannot reduce the observation evidence requirement. */
    public enum DesktopObservationPolicy { LEGACY_WINDOW, REQUIRED_SUBJECT }

    public TaskContractV3 {
        if (version != 3) throw new IllegalArgumentException("unsupported task contract version");
        originalRequest = Objects.requireNonNullElse(originalRequest, "");
        criteria = List.copyOf(Objects.requireNonNull(criteria, "criteria"));
        source = Objects.requireNonNullElse(source, "unknown");
        reasonCodes = boundedDiagnostics(reasonCodes, 16, 64, true);
        unresolvedInputs = boundedDiagnostics(unresolvedInputs, 12, 512, false);
        desktopObservationPolicy = Objects.requireNonNullElse(desktopObservationPolicy,
                DesktopObservationPolicy.LEGACY_WINDOW);
        if (criteria.size() > 12 || !applicable && !criteria.isEmpty()
                || reliable && applicable && criteria.isEmpty()) {
            throw new IllegalArgumentException("invalid task contract criteria");
        }
        HashSet<String> ids = new HashSet<>();
        for (TaskCriterionV3 criterion : criteria) {
            if (!ids.add(criterion.id())) {
                throw new IllegalArgumentException("duplicate criterion id: " + criterion.id());
            }
        }
    }

    /** Keeps existing definitions and persisted contracts compatible with version 3. */
    public TaskContractV3(int version, String originalRequest, List<TaskCriterionV3> criteria,
            boolean applicable, boolean reliable, String source) {
        this(version, originalRequest, criteria, applicable, reliable, source, List.of(), List.of());
    }

    /** Contracts persisted before the subject policy retain their declared window-level behavior. */
    public TaskContractV3(int version, String originalRequest, List<TaskCriterionV3> criteria,
            boolean applicable, boolean reliable, String source,
            List<String> reasonCodes, List<String> unresolvedInputs) {
        this(version, originalRequest, criteria, applicable, reliable, source,
                reasonCodes, unresolvedInputs, DesktopObservationPolicy.LEGACY_WINDOW);
    }

    public boolean desktopObservationSubjectsValid() {
        return desktopObservationPolicy != DesktopObservationPolicy.REQUIRED_SUBJECT
                || criteria.stream().filter(criterion -> criterion.capabilityId().equals("desktop.observe"))
                        .allMatch(criterion -> !criterion.requiredSubject().isBlank());
    }

    public static TaskContractV3 unknown(String request) {
        return unknown(request, "UNKNOWN_CONTRACT");
    }

    public static TaskContractV3 unknown(String request, String reasonCode) {
        return new TaskContractV3(3, request, List.of(), true, false, "unknown",
                List.of(reasonCode), List.of());
    }

    private static List<String> boundedDiagnostics(List<String> values, int limit,
            int maxLength, boolean codes) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> bounded = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            String normalized = value.strip();
            if (codes && !normalized.matches("[A-Z][A-Z0-9_]{0,63}")) continue;
            bounded.add(normalized.substring(0, Math.min(normalized.length(), maxLength)));
            if (bounded.size() == limit) break;
        }
        return List.copyOf(bounded);
    }
}
