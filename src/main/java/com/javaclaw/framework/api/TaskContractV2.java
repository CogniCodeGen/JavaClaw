package com.javaclaw.framework.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** New-run contract whose desktop criteria require linked post-action frame proof. */
public record TaskContractV2(
        int version,
        String originalRequest,
        String target,
        List<TaskCriterion> criteria,
        boolean applicable,
        boolean reliable,
        String source) {
    public TaskContractV2 {
        if (version != 2) throw new IllegalArgumentException("unsupported task contract version: " + version);
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

    public static TaskContractV2 unknown(String request) {
        return new TaskContractV2(2, request, "", List.of(), true, false, "unknown");
    }
}
