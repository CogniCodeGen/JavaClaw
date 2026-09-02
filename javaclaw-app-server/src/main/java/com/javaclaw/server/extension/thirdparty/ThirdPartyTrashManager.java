package com.javaclaw.server.extension.thirdparty;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.protocol.BundleRpcContracts;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.ThirdPartyExtensionRecord;
import com.javaclaw.server.persistence.ThirdPartyExtensionRepository;
import com.javaclaw.server.persistence.ThirdPartyTrashRecord;
import com.javaclaw.server.persistence.ThirdPartyTrashRepository;

/** Bundle 卸载、可恢复 Trash、永久清除及中断恢复协调器。 */
final class ThirdPartyTrashManager {
    private final ThirdPartyBundleArchive archives;
    private final ThirdPartyBundleCompiler compiler;
    private final ThirdPartyBundleDirectories directories;
    private final ThirdPartyExtensionRepository extensions;
    private final ThirdPartyTrashRepository trash;
    private final ThirdPartyBundleRegistry registry;
    private final Set<String> reservedToolNames;
    private final Clock clock;

    ThirdPartyTrashManager(Dependencies dependencies) {
        Objects.requireNonNull(dependencies, "dependencies");
        archives = dependencies.archives();
        compiler = dependencies.compiler();
        directories = dependencies.directories();
        extensions = dependencies.extensions();
        trash = dependencies.trash();
        registry = dependencies.registry();
        reservedToolNames = dependencies.reservedToolNames();
        clock = dependencies.clock();
    }

    synchronized BundleRpcContracts.TrashEntry uninstall(String extensionId, long expectedRevision) {
        ThirdPartyTrashRecord retried =
                trash.findByRevision(extensionId, expectedRevision).orElse(null);
        if (retried != null) {
            return retried.entry();
        }
        ExtensionId id = new ExtensionId(extensionId);
        ThirdPartyExtensionRecord current =
                extensions.find(id).orElseThrow(() -> PersistenceException.invalidRequest("第三方扩展不存在"));
        String trashId = directories.trashName(extensionId, expectedRevision, current.manifestDigest());
        ThirdPartyExtensionRecord removing = extensions.beginRemoval(id, expectedRevision, trashId);
        return completeRemoval(removing);
    }

    synchronized void recoverRemoval(ThirdPartyExtensionRecord removing) {
        try {
            completeRemoval(removing);
        } catch (RuntimeException failure) {
            extensions.recordFailure(
                    removing.descriptor().id(),
                    Math.addExact(removing.failureCount(), 1),
                    clock.instant().plusSeconds(300),
                    failureMessage(failure),
                    true);
        }
    }

    synchronized List<BundleRpcContracts.TrashEntry> list() {
        return trash.list().stream().map(ThirdPartyTrashRecord::entry).toList();
    }

    synchronized BundleRpcContracts.TrashEntry read(String trashId) {
        return require(trashId).entry();
    }

    synchronized BundleRpcContracts.Bundle restore(String trashId, long expectedRevision) {
        ThirdPartyTrashRecord current = require(trashId);
        if (current.entry().revision() != expectedRevision) {
            throw PersistenceException.revisionConflict("Trash revision 已改变");
        }
        if (current.entry().state() != BundleRpcContracts.TrashState.TRASHED) {
            throw PersistenceException.invalidRequest(
                    "Trash 条目不可恢复: " + current.entry().state());
        }
        ExtensionId id = current.descriptor().id();
        long revision = extensions.nextRevision(id);
        String directory = directories.installDirectory(
                id.value(), revision, current.entry().manifestDigest());
        boolean moved = false;
        try {
            var installedRoot = directories.restoreFromTrash(trashId, directory);
            moved = true;
            VerifiedThirdPartyBundle verified =
                    archives.verifyInstalled(installedRoot, current.entry().manifestDigest());
            requireSameIdentity(current, verified);
            InstalledThirdPartyBundle installed = compiler.compile(verified, revision);
            requireNoReservedTools(installed);
            ThirdPartyTrashRecord restored = trash.restore(current, installed.descriptor(), directory);
            ThirdPartyExtensionRecord record =
                    extensions.find(id).orElseThrow(() -> new IllegalStateException("恢复后的 Bundle 目录记录缺失"));
            if (restored.entry().restoredRevision().orElseThrow() != revision) {
                throw new IllegalStateException("Trash restored revision 未持久化");
            }
            return bundle(record, verified.signingKeyFingerprint());
        } catch (Exception failure) {
            rollbackRestore(directory, trashId, moved, failure);
            throw failure instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Bundle Trash 恢复失败", failure);
        }
    }

    synchronized BundleRpcContracts.TrashEntry purge(String trashId, long expectedRevision) {
        ThirdPartyTrashRecord current = require(trashId);
        if (current.entry().revision() != expectedRevision) {
            throw PersistenceException.revisionConflict("Trash revision 已改变");
        }
        if (current.entry().state() == BundleRpcContracts.TrashState.PURGED) {
            return current.entry();
        }
        if (current.entry().state() != BundleRpcContracts.TrashState.TRASHED) {
            throw PersistenceException.invalidRequest("仅 TRASHED 条目可以永久清除");
        }
        try {
            directories.purgeTrash(trashId);
            return trash.purge(trashId, expectedRevision).entry();
        } catch (IOException failure) {
            throw new IllegalStateException("Bundle Trash 永久清除失败", failure);
        }
    }

    synchronized void recordSuperseded(ThirdPartyExtensionRecord current, String trashId) {
        trash.recordSuperseded(current, trashId);
    }

    private BundleRpcContracts.TrashEntry completeRemoval(ThirdPartyExtensionRecord removing) {
        try {
            String trashId = removing.pendingTrashName().orElseThrow();
            directories.moveToTrash(removing.installDirectory(), trashId);
            ThirdPartyTrashRecord completed = trash.completeRemoval(removing, trashId);
            registry.remove(removing.descriptor().id());
            return completed.entry();
        } catch (IOException failure) {
            throw new IllegalStateException("第三方扩展无法移入 Trash", failure);
        }
    }

    private ThirdPartyTrashRecord require(String trashId) {
        return trash.find(trashId).orElseThrow(() -> PersistenceException.invalidRequest("Trash 条目不存在"));
    }

    private void rollbackRestore(String directory, String trashId, boolean moved, Exception original) {
        if (!moved
                || extensions
                        .find(new ExtensionId(require(trashId).entry().extensionId()))
                        .isPresent()) {
            return;
        }
        try {
            directories.moveToTrash(directory, trashId);
        } catch (Exception rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private void requireNoReservedTools(InstalledThirdPartyBundle bundle) {
        bundle.tools().keySet().stream()
                .filter(reservedToolNames::contains)
                .findFirst()
                .ifPresent(name -> {
                    throw new IllegalArgumentException("tool name is already reserved: " + name);
                });
    }

    private static void requireSameIdentity(ThirdPartyTrashRecord current, VerifiedThirdPartyBundle verified) {
        if (!current.entry().extensionId().equals(verified.manifest().id())
                || !current.entry().signingKeyId().equals(verified.manifest().signingKeyId())
                || !current.entry().manifestDigest().equals(verified.manifestDigest())) {
            throw new SecurityException("Trash Bundle 身份或签名内容已改变");
        }
    }

    private static BundleRpcContracts.Bundle bundle(ThirdPartyExtensionRecord record, String fingerprint) {
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
                new BundleRpcContracts.Health(
                        BundleRpcContracts.HealthState.DISABLED,
                        record.failureCount(),
                        record.lastHealthAt(),
                        record.nextRetryAt(),
                        record.lastFailure()));
    }

    private static String failureMessage(Exception failure) {
        String message = failure.getMessage();
        String combined = message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + ": " + message;
        return combined.length() <= 1000 ? combined : combined.substring(0, 1000);
    }

    record Dependencies(
            ThirdPartyBundleArchive archives,
            ThirdPartyBundleCompiler compiler,
            ThirdPartyBundleDirectories directories,
            ThirdPartyExtensionRepository extensions,
            ThirdPartyTrashRepository trash,
            ThirdPartyBundleRegistry registry,
            Set<String> reservedToolNames,
            Clock clock) {
        Dependencies {
            Objects.requireNonNull(archives, "archives");
            Objects.requireNonNull(compiler, "compiler");
            Objects.requireNonNull(directories, "directories");
            Objects.requireNonNull(extensions, "extensions");
            Objects.requireNonNull(trash, "trash");
            Objects.requireNonNull(registry, "registry");
            reservedToolNames = Set.copyOf(reservedToolNames);
            Objects.requireNonNull(clock, "clock");
        }
    }
}
