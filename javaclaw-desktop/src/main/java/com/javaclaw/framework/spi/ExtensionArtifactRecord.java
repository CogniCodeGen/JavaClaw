package com.javaclaw.framework.spi;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Durable authorization record for one trusted system-extension artifact. */
public record ExtensionArtifactRecord(
        String extensionId,
        SemanticVersion version,
        String artifactSha256,
        Path artifactPath,
        boolean authorized,
        boolean enabledForNewRuns,
        Instant cachedAt) {
    public ExtensionArtifactRecord {
        extensionId = Objects.requireNonNull(extensionId, "extensionId");
        version = Objects.requireNonNull(version, "version");
        artifactSha256 = Objects.requireNonNull(artifactSha256, "artifactSha256");
        artifactPath = Objects.requireNonNull(artifactPath, "artifactPath")
                .toAbsolutePath().normalize();
        cachedAt = Objects.requireNonNull(cachedAt, "cachedAt");
    }
}
