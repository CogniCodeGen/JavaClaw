package com.javaclaw.framework.builtin.memory;

import com.javaclaw.framework.api.RunRequest;

/** Adapter over the EclipseStore graph/vector recall implementation. */
@FunctionalInterface
public interface MemoryRecallGateway {
    String recall(RunRequest request, String query, int topK);
}
