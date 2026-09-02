package com.javaclaw.server.extension.thirdparty;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.AttachmentContent;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.protocol.BundleRpcContracts;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.ExtensionTrustKeyRepository;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.ThirdPartyExtensionRecord;
import com.javaclaw.server.persistence.ThirdPartyExtensionRepository;

/** 第三方 Bundle 的 Attachment staging、原子安装/升级、健康与实时启停。 */
final class ThirdPartyBundleManager {
    private static final int QUARANTINE_THRESHOLD = 3;

    private final AttachmentService attachments;
    private final ThirdPartyBundleArchive archives;
    private final ThirdPartyBundleDirectories directories;
    private final ThirdPartyBundleCompiler compiler;
    private final ThirdPartyWorkerClient workers;
    private final ThirdPartyExtensionRepository repository;
    private final ExtensionTrustKeyRepository trustKeys;
    private final ThirdPartyBundleRegistry registry;
    private final ThirdPartyTrashManager trash;
    private final Set<String> reservedToolNames;
    private final Clock clock;

    ThirdPartyBundleManager(Dependencies dependencies) {
        Objects.requireNonNull(dependencies, "dependencies");
        attachments = dependencies.attachments();
        archives = dependencies.archives();
        directories = dependencies.directories();
        compiler = dependencies.compiler();
        workers = dependencies.workers();
        repository = dependencies.repository();
        trustKeys = dependencies.trustKeys();
        registry = dependencies.registry();
        trash = dependencies.trash();
        reservedToolNames = dependencies.reservedToolNames();
        clock = dependencies.clock();
    }

    synchronized void recover() {
        for (ThirdPartyExtensionRecord record : repository.list()) {
            if (record.state() == ExtensionState.REMOVING) {
                trash.recoverRemoval(record);
                continue;
            }
            recoverInstalled(record);
        }
    }

    synchronized BundleRpcContracts.StageResult stage(BundleRpcContracts.AttachmentPointer pointer) {
        AttachmentContent attachment = attachments.read(AttachmentScope.global(), pointer.attachmentId());
        if (!attachment.metadata().digest().equals(pointer.digest())) {
            throw new SecurityException("Bundle Attachment digest changed");
        }
        VerifiedThirdPartyBundle staged = archives.stage(attachment);
        return stageResult(pointer, staged);
    }

    synchronized BundleRpcContracts.Bundle install(BundleRpcContracts.CommitPayload request, long expectedRevision) {
        if (expectedRevision != 0) {
            throw PersistenceException.revisionConflict("新安装 expected revision 必须为 0");
        }
        requireApproved(request);
        ThirdPartyExtensionRecord retried = repository.list().stream()
                .filter(record -> record.manifestDigest().equals(request.approvedManifestDigest()))
                .findFirst()
                .orElse(null);
        if (retried != null) {
            return bundle(retried);
        }
        VerifiedThirdPartyBundle staged = requireStaged(request);
        ExtensionId id = new ExtensionId(staged.manifest().id());
        return installVerified(staged, repository.nextRevision(id));
    }

    synchronized BundleRpcContracts.Bundle upgrade(BundleRpcContracts.CommitPayload request, long expectedRevision) {
        if (expectedRevision < 1) {
            throw PersistenceException.revisionConflict("Bundle 升级 expected revision 必须为正数");
        }
        requireApproved(request);
        VerifiedThirdPartyBundle staged = requireStaged(request);
        ExtensionId id = new ExtensionId(staged.manifest().id());
        ThirdPartyExtensionRecord current =
                repository.find(id).orElseThrow(() -> PersistenceException.invalidRequest("待升级 Bundle 不存在"));
        requireRevision(current, expectedRevision);
        long revision = Math.incrementExact(expectedRevision);
        InstalledThirdPartyBundle replacement = compiler.compile(staged, revision);
        requireNoReservedTools(replacement);
        return switchRevision(current, replacement);
    }

    synchronized BundleRpcContracts.Bundle enable(String extensionId, long expectedRevision) {
        ExtensionId id = new ExtensionId(extensionId);
        ThirdPartyExtensionRecord current = require(id);
        if (current.state() == ExtensionState.ENABLED) {
            requireRevision(current, expectedRevision);
            return bundle(current);
        }
        ThirdPartyExtensionRecord starting = repository.transition(
                id,
                expectedRevision,
                Set.of(ExtensionState.INSTALLED, ExtensionState.DISABLED, ExtensionState.QUARANTINED),
                ExtensionState.STARTING);
        try {
            InstalledThirdPartyBundle installed = load(starting);
            workers.health(installed);
            repository.clearFailures(id);
            registry.put(installed);
            return bundle(repository.transition(
                    id, expectedRevision, Set.of(ExtensionState.STARTING), ExtensionState.ENABLED));
        } catch (Exception failure) {
            recordFailure(starting, failure, true);
            throw new IllegalStateException("第三方扩展健康检查失败", failure);
        }
    }

    synchronized BundleRpcContracts.Bundle disable(String extensionId, long expectedRevision) {
        ExtensionId id = new ExtensionId(extensionId);
        ThirdPartyExtensionRecord current = require(id);
        if (current.state() == ExtensionState.DISABLED) {
            requireRevision(current, expectedRevision);
            return bundle(current);
        }
        ThirdPartyExtensionRecord disabled = repository.transition(
                id,
                expectedRevision,
                Set.of(
                        ExtensionState.INSTALLED,
                        ExtensionState.STARTING,
                        ExtensionState.ENABLED,
                        ExtensionState.QUARANTINED),
                ExtensionState.DISABLED);
        registry.remove(id);
        return bundle(disabled);
    }

    synchronized BundleRpcContracts.Bundle probe(String extensionId, long expectedRevision) {
        ExtensionId id = new ExtensionId(extensionId);
        ThirdPartyExtensionRecord current = require(id);
        requireRevision(current, expectedRevision);
        try {
            workers.health(load(current));
            repository.clearFailures(id);
            return bundle(require(id));
        } catch (Exception failure) {
            recordFailure(current, failure, current.failureCount() + 1 >= QUARANTINE_THRESHOLD);
            registry.remove(id);
            throw new IllegalStateException("第三方扩展健康检查失败", failure);
        }
    }

    List<BundleRpcContracts.Bundle> list() {
        return repository.list().stream().map(this::bundle).toList();
    }

    BundleRpcContracts.Bundle read(String extensionId) {
        return bundle(require(new ExtensionId(extensionId)));
    }

    void recordInvocationFailure(ThirdPartyExtensionRecord current, Exception failure) {
        recordFailure(current, failure, current.failureCount() + 1 >= QUARANTINE_THRESHOLD);
    }

    private BundleRpcContracts.Bundle installVerified(VerifiedThirdPartyBundle staged, long revision) {
        requireNoReservedTools(compiler.compile(staged, revision));
        String directory = directories.installDirectory(staged.manifest().id(), revision, staged.manifestDigest());
        try {
            VerifiedThirdPartyBundle installedVerified =
                    archives.verifyInstalled(directories.install(staged.root(), directory), staged.manifestDigest());
            InstalledThirdPartyBundle bundle = compiler.compile(installedVerified, revision);
            ThirdPartyExtensionRecord record = repository.install(
                    bundle.descriptor(),
                    bundle.manifestDigest(),
                    bundle.manifest().signingKeyId(),
                    directory,
                    ThirdPartyPermissionPolicy.review(bundle.manifest()));
            return bundle(record);
        } catch (Exception failure) {
            purgeFailedInstall(directory, failure);
            throw runtime("第三方扩展安装失败", failure);
        }
    }

    private BundleRpcContracts.Bundle switchRevision(
            ThirdPartyExtensionRecord current, InstalledThirdPartyBundle replacement) {
        ExtensionId id = current.descriptor().id();
        long expectedRevision = current.descriptor().revision();
        ExtensionState originalState = current.state();
        ExtensionState targetState =
                originalState == ExtensionState.ENABLED ? ExtensionState.ENABLED : ExtensionState.DISABLED;
        repository.transition(
                id,
                expectedRevision,
                Set.of(
                        ExtensionState.INSTALLED,
                        ExtensionState.DISABLED,
                        ExtensionState.ENABLED,
                        ExtensionState.QUARANTINED),
                ExtensionState.STARTING);
        registry.remove(id);
        String newDirectory = directories.installDirectory(
                id.value(), replacement.descriptor().revision(), replacement.manifestDigest());
        String oldTrash = directories.trashName(id.value(), expectedRevision, current.manifestDigest());
        boolean oldMoved = false;
        try {
            VerifiedThirdPartyBundle installedVerified = archives.verifyInstalled(
                    directories.install(replacement.root(), newDirectory), replacement.manifestDigest());
            InstalledThirdPartyBundle installedReplacement =
                    compiler.compile(installedVerified, replacement.descriptor().revision());
            workers.health(installedReplacement);
            directories.moveToTrash(current.installDirectory(), oldTrash);
            oldMoved = true;
            ThirdPartyExtensionRecord upgraded = repository.upgrade(
                    id,
                    expectedRevision,
                    new ThirdPartyExtensionRepository.Upgrade(
                            installedReplacement.descriptor(),
                            installedReplacement.manifestDigest(),
                            installedReplacement.manifest().signingKeyId(),
                            newDirectory,
                            ThirdPartyPermissionPolicy.review(installedReplacement.manifest()),
                            targetState));
            trash.recordSuperseded(current, oldTrash);
            if (targetState == ExtensionState.ENABLED) {
                registry.put(installedReplacement);
            }
            return bundle(upgraded);
        } catch (Exception failure) {
            rollbackUpgrade(current, originalState, newDirectory, oldTrash, oldMoved, failure);
            throw runtime("第三方 Bundle 原子升级失败", failure);
        }
    }

    private void rollbackUpgrade(
            ThirdPartyExtensionRecord current,
            ExtensionState originalState,
            String newDirectory,
            String oldTrash,
            boolean oldMoved,
            Exception original) {
        ExtensionId id = current.descriptor().id();
        if (repository
                .find(id)
                .filter(record ->
                        record.descriptor().revision() != current.descriptor().revision())
                .isPresent()) {
            registry.remove(id);
            repository.find(id).ifPresent(record -> recordFailure(record, original, true));
            return;
        }
        try {
            directories.purgeInstalled(newDirectory);
            if (oldMoved) {
                directories.restoreFromTrash(oldTrash, current.installDirectory());
            }
            repository.transition(id, current.descriptor().revision(), Set.of(ExtensionState.STARTING), originalState);
            if (originalState == ExtensionState.ENABLED) {
                registry.put(load(current));
            }
        } catch (Exception rollbackFailure) {
            original.addSuppressed(rollbackFailure);
            repository.find(id).ifPresent(record -> recordFailure(record, rollbackFailure, true));
        }
    }

    private void recoverInstalled(ThirdPartyExtensionRecord record) {
        try {
            InstalledThirdPartyBundle installed = load(record);
            if (record.state() == ExtensionState.STARTING) {
                recordFailure(record, new IllegalStateException("server stopped during Bundle transition"), true);
            } else if (record.state() == ExtensionState.ENABLED) {
                workers.health(installed);
                repository.clearFailures(record.descriptor().id());
                registry.put(installed);
            }
        } catch (Exception failure) {
            recordFailure(record, failure, true);
        }
    }

    private InstalledThirdPartyBundle load(ThirdPartyExtensionRecord record) {
        VerifiedThirdPartyBundle verified =
                archives.verifyInstalled(directories.installed(record.installDirectory()), record.manifestDigest());
        if (!verified.manifest().signingKeyId().equals(record.signingKeyId())) {
            throw new SecurityException("installed signing key identity changed");
        }
        InstalledThirdPartyBundle installed =
                compiler.compile(verified, record.descriptor().revision());
        requireNoReservedTools(installed);
        if (!installed.descriptor().equals(record.descriptor())) {
            throw new SecurityException("installed descriptor differs from catalog");
        }
        return installed;
    }

    private VerifiedThirdPartyBundle requireStaged(BundleRpcContracts.CommitPayload request) {
        VerifiedThirdPartyBundle staged = archives.verifyStaged(request.stagingId());
        if (!staged.manifestDigest().equals(request.approvedManifestDigest())) {
            throw new SecurityException("approved manifest digest changed after review");
        }
        return staged;
    }

    private BundleRpcContracts.Bundle bundle(ThirdPartyExtensionRecord record) {
        String fingerprint = trustKeys
                .find(record.signingKeyId())
                .orElseThrow(() -> new SecurityException("Bundle signing Trust Key 不存在"))
                .metadata()
                .fingerprint();
        BundleRpcContracts.HealthState healthState =
                switch (record.state()) {
                    case DISABLED -> BundleRpcContracts.HealthState.DISABLED;
                    case QUARANTINED -> BundleRpcContracts.HealthState.QUARANTINED;
                    default -> record.healthState();
                };
        var health = new BundleRpcContracts.Health(
                healthState, record.failureCount(), record.lastHealthAt(), record.nextRetryAt(), record.lastFailure());
        ExtensionDescriptor descriptor = record.descriptor();
        return new BundleRpcContracts.Bundle(
                descriptor.id().value(),
                descriptor.displayName(),
                descriptor.version(),
                descriptor.revision(),
                record.state().name(),
                record.manifestDigest(),
                record.signingKeyId(),
                fingerprint,
                descriptor.contributionKinds().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),
                record.permissions(),
                health);
    }

    static ExtensionRpcContracts.Summary genericSummary(ThirdPartyExtensionRecord record) {
        ExtensionDescriptor descriptor = record.descriptor();
        return new ExtensionRpcContracts.Summary(
                descriptor.id().value(),
                descriptor.displayName(),
                descriptor.version(),
                descriptor.revision(),
                record.state().name(),
                descriptor.requirements().trust().name(),
                descriptor.contributionKinds().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()));
    }

    private ThirdPartyExtensionRecord require(ExtensionId id) {
        return repository.find(id).orElseThrow(() -> PersistenceException.invalidRequest("第三方扩展不存在"));
    }

    private void recordFailure(ThirdPartyExtensionRecord current, Exception failure, boolean quarantine) {
        int count = Math.addExact(current.failureCount(), 1);
        Duration delay = Duration.ofSeconds(Math.min(300, 1L << Math.min(count, 8)));
        repository.recordFailure(
                current.descriptor().id(), count, Instant.now(clock).plus(delay), failureMessage(failure), quarantine);
    }

    private void requireNoReservedTools(InstalledThirdPartyBundle bundle) {
        bundle.tools().keySet().stream()
                .filter(reservedToolNames::contains)
                .findFirst()
                .ifPresent(name -> {
                    throw new IllegalArgumentException("tool name is already reserved: " + name);
                });
    }

    private void purgeFailedInstall(String installDirectory, Exception original) {
        try {
            directories.purgeInstalled(installDirectory);
        } catch (IOException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void requireApproved(BundleRpcContracts.CommitPayload request) {
        if (!request.stagingId().equals(request.approvedManifestDigest())) {
            throw new SecurityException("approved manifest digest differs from staging identity");
        }
    }

    private static void requireRevision(ThirdPartyExtensionRecord record, long expectedRevision) {
        if (record.descriptor().revision() != expectedRevision) {
            throw PersistenceException.revisionConflict("第三方扩展 revision 已改变");
        }
    }

    private static String failureMessage(Exception failure) {
        String message = failure.getMessage();
        String combined = message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + ": " + message;
        return combined.length() <= 1000 ? combined : combined.substring(0, 1000);
    }

    private static BundleRpcContracts.StageResult stageResult(
            BundleRpcContracts.AttachmentPointer attachment, VerifiedThirdPartyBundle staged) {
        ThirdPartyBundleManifest manifest = staged.manifest();
        return new BundleRpcContracts.StageResult(
                staged.manifestDigest(),
                attachment,
                staged.manifestDigest(),
                manifest.id(),
                manifest.displayName(),
                manifest.version(),
                manifest.signingKeyId(),
                staged.signingKeyFingerprint(),
                manifest.contributionKinds().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),
                ThirdPartyPermissionPolicy.review(manifest));
    }

    private static RuntimeException runtime(String message, Exception failure) {
        return failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(message, failure);
    }

    record Dependencies(
            AttachmentService attachments,
            ThirdPartyBundleArchive archives,
            ThirdPartyBundleDirectories directories,
            ThirdPartyBundleCompiler compiler,
            ThirdPartyWorkerClient workers,
            ThirdPartyExtensionRepository repository,
            ExtensionTrustKeyRepository trustKeys,
            ThirdPartyBundleRegistry registry,
            ThirdPartyTrashManager trash,
            Set<String> reservedToolNames,
            Clock clock) {
        Dependencies {
            Objects.requireNonNull(attachments, "attachments");
            Objects.requireNonNull(archives, "archives");
            Objects.requireNonNull(directories, "directories");
            Objects.requireNonNull(compiler, "compiler");
            Objects.requireNonNull(workers, "workers");
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(trustKeys, "trustKeys");
            Objects.requireNonNull(registry, "registry");
            Objects.requireNonNull(trash, "trash");
            reservedToolNames = Set.copyOf(reservedToolNames);
            Objects.requireNonNull(clock, "clock");
        }
    }
}
