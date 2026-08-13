package com.javaclaw.framework.extension;

import com.javaclaw.framework.spi.ExtensionArtifactRecord;
import com.javaclaw.framework.spi.ExtensionArtifactRepository;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Transaction-like application service for trusted extension authorization and publication. */
public final class TrustedExtensionService {
    private final TrustedExtensionInstaller installer;
    private final ExtensionArtifactRepository repository;
    private final ExtensionManager manager;

    public TrustedExtensionService(
            TrustedExtensionInstaller installer,
            ExtensionArtifactRepository repository,
            ExtensionManager manager) {
        this.installer = Objects.requireNonNull(installer, "installer");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    public synchronized ExtensionRegistrySnapshot install(
            Path jar, boolean hostPermissionConfirmed) {
        List<ExtensionArtifact> additions = installer.install(jar, hostPermissionConfirmed);
        return publish(additions);
    }

    public synchronized ExtensionRegistrySnapshot install(
            Path jar, String approvedSha256, boolean hostPermissionConfirmed) {
        List<ExtensionArtifact> additions = installer.install(
                jar, approvedSha256, hostPermissionConfirmed);
        return publish(additions);
    }

    private ExtensionRegistrySnapshot publish(List<ExtensionArtifact> additions) {
        try {
            return manager.publishAdding(additions);
        } catch (RuntimeException failure) {
            try {
                repository.revokeAll(records(additions));
            } catch (RuntimeException revokeFailure) {
                failure.addSuppressed(revokeFailure);
            }
            try {
                ExtensionArtifact.closeClassLoaders(additions);
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public TrustedExtensionInstaller.InstallPreview preview(Path jar) {
        return installer.preview(jar);
    }

    public List<ExtensionArtifactRecord> installed() {
        return repository.authorizedArtifacts();
    }

    public ExtensionRegistrySnapshot disable(String extensionId) {
        List<ExtensionArtifactRecord> records = repository.authorizedArtifacts().stream()
                .filter(record -> record.extensionId().equals(extensionId)).toList();
        if (records.isEmpty()) {
            throw new IllegalArgumentException(
                    "only installed external extensions can be disabled: " + extensionId);
        }
        boolean wasEnabled = !manager.currentSnapshot().disabledExtensionIds()
                .contains(extensionId);
        ExtensionRegistrySnapshot snapshot = manager.disableForNewRuns(extensionId);
        try {
            requireUpdated(repository.setEnabledForNewRuns(extensionId, false), extensionId);
            return snapshot;
        } catch (RuntimeException failure) {
            if (wasEnabled) {
                try {
                    manager.enableForNewRuns(extensionId);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    public ExtensionRegistrySnapshot enable(String extensionId) {
        List<ExtensionArtifactRecord> records = repository.authorizedArtifacts().stream()
                .filter(record -> record.extensionId().equals(extensionId)).toList();
        if (records.isEmpty()) {
            throw new IllegalArgumentException(
                    "external extension is not authorized: " + extensionId);
        }
        boolean wasDisabled = manager.currentSnapshot().disabledExtensionIds()
                .contains(extensionId);
        ExtensionRegistrySnapshot snapshot = manager.enableForNewRuns(extensionId);
        try {
            requireUpdated(repository.setEnabledForNewRuns(extensionId, true), extensionId);
            return snapshot;
        } catch (RuntimeException failure) {
            if (wasDisabled) {
                try {
                    manager.disableForNewRuns(extensionId);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    private static void requireUpdated(int count, String extensionId) {
        if (count <= 0) throw new IllegalStateException(
                "external extension authorization disappeared: " + extensionId);
    }

    private static List<ExtensionArtifactRecord> records(List<ExtensionArtifact> artifacts) {
        java.time.Instant now = java.time.Instant.now();
        return artifacts.stream().map(artifact -> new ExtensionArtifactRecord(
                artifact.extension().descriptor().id(),
                artifact.extension().descriptor().version(), artifact.artifactSha256(),
                java.nio.file.Path.of("."),
                true, true, now)).toList();
    }
}
