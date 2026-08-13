package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;

public interface StateMigrator {
    String extensionId();

    int fromVersion();

    int toVersion();

    JsonNode migrate(JsonNode state);
}
