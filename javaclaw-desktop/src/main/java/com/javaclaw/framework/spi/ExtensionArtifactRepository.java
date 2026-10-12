package com.javaclaw.framework.spi;

import java.util.List;

/** Metadata store for immutable trusted extension JARs retained for run recovery. */
public interface ExtensionArtifactRepository {
    void authorize(ExtensionArtifactRecord artifact);

    /** Validates every coordinate, then authorizes the complete JAR in one transaction. */
    void authorizeAll(List<ExtensionArtifactRecord> artifacts);

    List<ExtensionArtifactRecord> authorizedArtifacts();

    int setEnabledForNewRuns(String extensionId, boolean enabled);

    boolean revoke(String extensionId, SemanticVersion version, String artifactSha256);

    /** Revokes all supplied coordinates atomically. */
    void revokeAll(List<ExtensionArtifactRecord> artifacts);
}
