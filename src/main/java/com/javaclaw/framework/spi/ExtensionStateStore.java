package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunId;

/** State mutations are namespaced and tied to a run; extensions own no hidden singleton state. */
public interface ExtensionStateStore {
    ExtensionStateView view(RunId runId);

    void put(RunId runId, String extensionId, String key, int schemaVersion, JsonNode value);

    void remove(RunId runId, String extensionId, String key);

    static ExtensionStateStore disabled() {
        return new ExtensionStateStore() {
            @Override
            public ExtensionStateView view(RunId runId) {
                return new ExtensionStateView() {
                    @Override public RunId runId() { return runId; }
                    @Override public java.util.Optional<JsonNode> get(
                            String extensionId, String key) {
                        return java.util.Optional.empty();
                    }
                };
            }

            @Override
            public void put(
                    RunId runId, String extensionId, String key,
                    int schemaVersion, JsonNode value) {
                throw new UnsupportedOperationException("extension state store is unavailable");
            }

            @Override
            public void remove(RunId runId, String extensionId, String key) {
                throw new UnsupportedOperationException("extension state store is unavailable");
            }
        };
    }
}
