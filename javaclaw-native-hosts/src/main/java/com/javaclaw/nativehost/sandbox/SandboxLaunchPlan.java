package com.javaclaw.nativehost.sandbox;

import java.util.List;
import java.util.Map;

/** 平台 backend 生成的无 shell 启动计划。 */
record SandboxLaunchPlan(
        String backend,
        List<String> command,
        Map<String, String> environment,
        int trustedDescendantProcesses,
        boolean nativeTreeLimits) {
    SandboxLaunchPlan {
        command = List.copyOf(command);
        environment = Map.copyOf(environment);
        if (trustedDescendantProcesses < 0) {
            throw new IllegalArgumentException("trustedDescendantProcesses must not be negative");
        }
    }
}
