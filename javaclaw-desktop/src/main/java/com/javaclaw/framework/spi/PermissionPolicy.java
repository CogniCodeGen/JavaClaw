package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunRequest;

@FunctionalInterface
public interface PermissionPolicy {
    PermissionSet restrict(PermissionSet current, JsonNode configuration, RunRequest request);
}
