package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunId;

import java.util.Optional;

/** Read side of per-run, extension-namespaced state. */
public interface ExtensionStateView {
    RunId runId();

    Optional<JsonNode> get(String extensionId, String key);
}
