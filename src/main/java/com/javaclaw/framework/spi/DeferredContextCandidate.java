package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.PermissionSet;

import java.util.Objects;

/** Metadata visible to a context selector before any candidate body is loaded. */
public record DeferredContextCandidate(
        String id, String version, String summary, PermissionSet requiredPermissions,
        DeferredContextUse use) {
    public DeferredContextCandidate(String id, String version, String summary,
                                    PermissionSet requiredPermissions) {
        this(id, version, summary, requiredPermissions, DeferredContextUse.REFERENCE);
    }

    public DeferredContextCandidate {
        id = required(id, "id");
        version = required(version, "version");
        summary = Objects.requireNonNullElse(summary, "").strip();
        requiredPermissions = Objects.requireNonNull(requiredPermissions, "requiredPermissions");
        use = Objects.requireNonNullElse(use, DeferredContextUse.REFERENCE);
    }

    private static String required(String value, String label) {
        String result = Objects.requireNonNull(value, label).strip();
        if (result.isEmpty()) throw new IllegalArgumentException(label + " must not be blank");
        return result;
    }
}
