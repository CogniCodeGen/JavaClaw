package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Small, current host capability facts associated with an authorized tool set.
 * Reads must be side-effect free and must not prompt, grant permissions, or cache
 * mutable permission conclusions. This context is informational, not authorization.
 */
@FunctionalInterface
public interface ToolRuntimeContextProvider {
    List<JsonNode> currentContext();
}
