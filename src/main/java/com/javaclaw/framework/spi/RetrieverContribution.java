package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunRequest;

import java.util.List;

@FunctionalInterface
public interface RetrieverContribution {
    List<JsonNode> retrieve(String query, RunRequest request);
}
