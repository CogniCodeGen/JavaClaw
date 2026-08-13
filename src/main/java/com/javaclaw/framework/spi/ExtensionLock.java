package com.javaclaw.framework.spi;

import java.util.Objects;

/** Exact immutable extension coordinate persisted with an Agent or Workflow execution plan. */
public record ExtensionLock(
        String extensionId,
        SemanticVersion version,
        String artifactSha256,
        long generation) {
    public ExtensionLock {
        extensionId = Objects.requireNonNull(extensionId, "extensionId").trim();
        version = Objects.requireNonNull(version, "version");
        artifactSha256 = Objects.requireNonNull(artifactSha256, "artifactSha256").toLowerCase();
        if (!extensionId.matches("[a-z][a-z0-9_.-]*")) {
            throw new IllegalArgumentException("invalid extension ID: " + extensionId);
        }
        if (!artifactSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("artifactSha256 must be a SHA-256 hex value");
        }
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
    }
}
