package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.InteractionMode;
import java.util.List;
import java.util.Objects;

/** A closed input set whose original observation predicate changed FALSE to TRUE. */
public record InteractionStageProofV1(int schemaVersion, InteractionMode mode,
        long contractSequence, String contractSha256, String observationCriterionId,
        String beforeInvocationId, String afterInvocationId, List<String> actionInvocationIds) {
    public InteractionStageProofV1 {
        if (schemaVersion != 1 || contractSequence < 1) throw new IllegalArgumentException("invalid stage version");
        Objects.requireNonNull(mode);
        if (!mode.executable()) throw new IllegalArgumentException("stage requires one backend");
        if (contractSha256 == null || !contractSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid contract hash");
        observationCriterionId = required(observationCriterionId);
        beforeInvocationId = required(beforeInvocationId);
        afterInvocationId = required(afterInvocationId);
        actionInvocationIds = List.copyOf(actionInvocationIds);
        if (actionInvocationIds.isEmpty() || actionInvocationIds.size() > 128
                || actionInvocationIds.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 512)
                || actionInvocationIds.stream().distinct().count() != actionInvocationIds.size())
            throw new IllegalArgumentException("invalid stage input set");
    }
    private static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 512) throw new IllegalArgumentException("invalid stage identity");
        return value;
    }
}
