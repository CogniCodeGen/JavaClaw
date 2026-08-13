package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunId;

@FunctionalInterface
public interface OutputGuard {
    JsonNode validate(JsonNode output, RunRequest request, RunId runId);
}
