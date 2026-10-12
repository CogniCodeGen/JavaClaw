package com.javaclaw.framework.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Durable interpretation of what the user asked the run to accomplish. */
public record TaskContractV1(
        int version,
        String originalRequest,
        String target,
        List<TaskCriterion> criteria,
        boolean applicable,
        boolean reliable,
        String source) {
    public TaskContractV1 {
        if (version != 1) throw new IllegalArgumentException("unsupported task contract version: " + version);
        originalRequest = Objects.requireNonNullElse(originalRequest, "");
        target = Objects.requireNonNullElse(target, "").trim();
        criteria = List.copyOf(Objects.requireNonNull(criteria, "criteria"));
        source = Objects.requireNonNullElse(source, "unknown").trim();
        if (source.isBlank()) source = "unknown";
        if (!applicable && !criteria.isEmpty()) throw new IllegalArgumentException("question answer has no effect criteria");
        if (reliable && applicable && criteria.isEmpty()) throw new IllegalArgumentException("reliable task needs criteria");
        var ids = new HashSet<String>();
        for (TaskCriterion criterion : criteria) {
            if (!ids.add(criterion.id())) throw new IllegalArgumentException("duplicate criterion id: " + criterion.id());
        }
    }

    public static TaskContractV1 unknown(String originalRequest) {
        return new TaskContractV1(1, originalRequest, "", List.of(), true, false, "unknown");
    }

    public static TaskContractV1 notApplicable(String originalRequest, String source) {
        return new TaskContractV1(1, originalRequest, "", List.of(), false, true, source);
    }
}
