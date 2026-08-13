package com.javaclaw.application.plugin;

import java.nio.file.Path;
import java.util.List;

/** Application boundary for installing and enabling trusted Agent Framework extensions. */
public interface AgentExtensionManagementApplicationService {

    InstallPreview preview(Path jar);

    List<AgentExtension> installed();

    /** Installs exactly the artifact whose hash was shown to the user in this preview. */
    List<AgentExtension> install(InstallPreview preview);

    List<AgentExtension> setEnabled(String extensionId, boolean enabled);

    record InstallPreview(Path path, String sha256, long sizeBytes) {
        public InstallPreview {
            path = java.util.Objects.requireNonNull(path, "path");
            sha256 = required(sha256, "sha256").toLowerCase(java.util.Locale.ROOT);
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must contain 64 hexadecimal characters");
            }
            if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes must not be negative");
        }
    }

    record AgentExtension(
            String id, String versions, String artifactHashes, boolean enabledForNewRuns) {
        public AgentExtension {
            id = required(id, "id");
            versions = required(versions, "versions");
            artifactHashes = required(artifactHashes, "artifactHashes");
        }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
