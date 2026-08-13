package com.javaclaw.framework.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.CapabilityId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Optional representative values used for codec, schema, compiler and advisor round trips. */
public record ExtensionContractSamples(
        Map<CapabilityId, JsonNode> capabilityConfigurations,
        Map<String, JsonNode> eventPayloads,
        Map<String, Object> stateValues) {

    public ExtensionContractSamples {
        capabilityConfigurations = jsonCopies(capabilityConfigurations);
        eventPayloads = jsonCopies(eventPayloads);
        stateValues = Map.copyOf(stateValues == null ? Map.of() : stateValues);
    }

    public static ExtensionContractSamples empty() {
        return new ExtensionContractSamples(Map.of(), Map.of(), Map.of());
    }

    private static <K> Map<K, JsonNode> jsonCopies(Map<K, JsonNode> source) {
        LinkedHashMap<K, JsonNode> copied = new LinkedHashMap<>();
        (source == null ? Map.<K, JsonNode>of() : source).forEach((key, value) ->
                copied.put(Objects.requireNonNull(key, "sample key"),
                        Objects.requireNonNull(value, "sample value").deepCopy()));
        return Map.copyOf(copied);
    }
}
