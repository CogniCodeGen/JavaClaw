package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.CapabilityId;

/** Compiles validated dynamic configuration into immutable plan data. */
@FunctionalInterface
public interface CapabilityCompiler {
    JsonNode compile(CapabilityId capabilityId, JsonNode mergedConfiguration, CompilationContext context);
}
