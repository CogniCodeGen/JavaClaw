package com.javaclaw.framework.builtin.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;

/** The sole mutation boundary for correction, distillation and habit workflows. */
public interface MemoryMutationGateway {
    JsonNode applyCorrection(RunId runId, RunRequest request, String userInput, String previousReply);

    JsonNode protectOutput(RunId runId, RunRequest request, JsonNode output);

    void distill(RunId runId, RunRequest request, JsonNode completedOutput);

}
