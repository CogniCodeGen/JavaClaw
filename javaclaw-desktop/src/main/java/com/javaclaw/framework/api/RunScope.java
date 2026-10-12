package com.javaclaw.framework.api;

import java.util.Objects;

/** Workspace, user and session isolation carried by every run. */
public record RunScope(String workspaceId, String userId, String sessionId) {
    public RunScope {
        workspaceId = required(workspaceId, "workspaceId");
        userId = required(userId, "userId");
        sessionId = required(sessionId, "sessionId");
    }

    private static String required(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
