package com.javaclaw.framework.api;

import java.util.Objects;

public record ThreadStartRequest(RunScope scope, String title, ThreadConfiguration configuration,
                                 RunScope parentScope, TurnId parentTurnId) {
    public ThreadStartRequest {
        scope = Objects.requireNonNull(scope, "scope");
        title = title == null || title.isBlank() ? "新的对话" : title.strip();
        configuration = configuration == null ? ThreadConfiguration.DEFAULT : configuration;
        if (parentTurnId != null && parentScope == null)
            throw new IllegalArgumentException("parent turn requires a parent thread");
        if (parentScope != null && (!scope.workspaceId().equals(parentScope.workspaceId())
                || !scope.userId().equals(parentScope.userId()) || scope.equals(parentScope))) {
            throw new IllegalArgumentException("parent must be a different thread in the same user workspace");
        }
    }
    public static ThreadStartRequest root(RunScope scope, String title) {
        return new ThreadStartRequest(scope, title, ThreadConfiguration.DEFAULT, null, null);
    }
}
