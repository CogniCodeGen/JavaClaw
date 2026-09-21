package com.javaclaw.framework.api;

import java.util.Map;

/** Durable defaults. Host policies remain an immutable upper bound. */
public record ThreadConfiguration(
        String modelPolicyRef, String workingDirectory, String sandboxPolicy,
        PermissionSet permissions, RunBudget budget, Map<String, String> projectInstructions) {
    public static final ThreadConfiguration DEFAULT = new ThreadConfiguration(
            "", "", "host", PermissionSet.UNRESTRICTED, RunBudget.UNBOUNDED, Map.of());
    public ThreadConfiguration {
        modelPolicyRef = modelPolicyRef == null ? "" : modelPolicyRef.strip();
        workingDirectory = workingDirectory == null ? "" : workingDirectory.strip();
        sandboxPolicy = sandboxPolicy == null || sandboxPolicy.isBlank() ? "host" : sandboxPolicy;
        if (!sandboxPolicy.equals("host")) throw new IllegalArgumentException("unsupported sandbox policy");
        permissions = permissions == null ? PermissionSet.NONE : permissions;
        budget = budget == null ? RunBudget.UNBOUNDED : budget;
        projectInstructions = java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(projectInstructions == null ? Map.of() : projectInstructions));
    }
}
