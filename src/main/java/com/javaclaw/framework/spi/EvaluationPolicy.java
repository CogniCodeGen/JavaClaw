package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunEventEnvelope;

import java.util.List;

public interface EvaluationPolicy {
    String id();

    JsonNode evaluate(List<RunEventEnvelope> events, JsonNode output, ModelTaskGateway models);
}
