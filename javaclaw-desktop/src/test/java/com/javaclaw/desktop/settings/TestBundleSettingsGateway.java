package com.javaclaw.desktop.settings;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.protocol.BundleRpcContracts;

/** Bundle、Trust Key 与 Trash Presenter 测试使用的纯内存 SDK 边界。 */
final class TestBundleSettingsGateway implements BundleSettingsGateway {
    static final Instant NOW = Instant.parse("2026-09-01T01:00:00Z");
    static final String ATTACHMENT_DIGEST = "a".repeat(64);
    static final String MANIFEST_DIGEST = "b".repeat(64);
    static final String FINGERPRINT = "c".repeat(64);
    static final String STAGING_DIGEST = "d".repeat(64);

    final List<BundleRpcContracts.Bundle> bundles = new ArrayList<>();
    final List<BundleRpcContracts.TrustKey> trustKeys = new ArrayList<>();
    final List<BundleRpcContracts.TrashEntry> trash = new ArrayList<>();
    BundleRpcContracts.StageResult staging = stage("com.example.new", "Example New", "1.0.0");
    RuntimeException nextFailure;

    @Override
    public CompletionStage<List<BundleRpcContracts.Bundle>> bundles() {
        return completed(List.copyOf(bundles));
    }

    @Override
    public CompletionStage<BundleRpcContracts.StageResult> stageBundle(Path archive) {
        RuntimeException failure = takeFailure();
        if (failure != null) {
            return failed(failure);
        }
        return completed(staging);
    }

    @Override
    public CompletionStage<BundleRpcContracts.Bundle> installBundle(BundleRpcContracts.StageResult source) {
        RuntimeException failure = takeFailure();
        if (failure != null) {
            return failed(failure);
        }
        BundleRpcContracts.Bundle installed = bundle(source, 1, "INSTALLED");
        bundles.add(installed);
        return completed(installed);
    }

    @Override
    public CompletionStage<BundleRpcContracts.Bundle> upgradeBundle(
            BundleRpcContracts.StageResult source, BundleRpcContracts.Bundle current) {
        RuntimeException failure = takeFailure();
        if (failure != null) {
            return failed(failure);
        }
        BundleRpcContracts.Bundle upgraded = bundle(source, current.revision() + 1, "DISABLED");
        replace(bundles, current, upgraded);
        return completed(upgraded);
    }

    @Override
    public CompletionStage<BundleRpcContracts.Bundle> probeBundle(BundleRpcContracts.Bundle current) {
        RuntimeException failure = takeFailure();
        if (failure != null) {
            return failed(failure);
        }
        BundleRpcContracts.Bundle probed = copy(
                current,
                current.state(),
                new BundleRpcContracts.Health(
                        BundleRpcContracts.HealthState.HEALTHY,
                        0,
                        Optional.of(NOW),
                        Optional.empty(),
                        Optional.empty()));
        replace(bundles, current, probed);
        return completed(probed);
    }

    @Override
    public CompletionStage<BundleRpcContracts.Bundle> setBundleEnabled(
            BundleRpcContracts.Bundle current, boolean enabled) {
        RuntimeException failure = takeFailure();
        if (failure != null) {
            return failed(failure);
        }
        String lifecycle = enabled ? "ENABLED" : "DISABLED";
        BundleRpcContracts.Bundle updated = copy(current, lifecycle, current.health());
        replace(bundles, current, updated);
        return completed(updated);
    }

    @Override
    public CompletionStage<BundleRpcContracts.TrashEntry> uninstallBundle(BundleRpcContracts.Bundle current) {
        RuntimeException failure = takeFailure();
        if (failure != null) {
            return failed(failure);
        }
        bundles.remove(current);
        BundleRpcContracts.TrashEntry removed = trash(current, "trash-" + current.id());
        trash.add(removed);
        return completed(removed);
    }

    @Override
    public CompletionStage<List<BundleRpcContracts.TrustKey>> trustKeys() {
        return completed(List.copyOf(trustKeys));
    }

    @Override
    public CompletionStage<TrustKeyImportDraft> prepareTrustKey(Path publicKey) {
        RuntimeException failure = takeFailure();
        if (failure != null) {
            return failed(failure);
        }
        AttachmentMetadata attachment =
                new AttachmentMetadata(ATTACHMENT_DIGEST, BundleRpcContracts.PUBLIC_KEY_MEDIA_TYPE, 44, NOW);
        return completed(new TrustKeyImportDraft(publicKey.getFileName().toString(), attachment, FINGERPRINT));
    }

    @Override
    public CompletionStage<BundleRpcContracts.TrustKey> importTrustKey(String keyId, TrustKeyImportDraft draft) {
        RuntimeException failure = takeFailure();
        if (failure != null) {
            return failed(failure);
        }
        BundleRpcContracts.TrustKey key = new BundleRpcContracts.TrustKey(
                keyId,
                draft.fingerprint(),
                draft.attachment().digest(),
                1,
                BundleRpcContracts.TrustState.ACTIVE,
                NOW,
                NOW);
        trustKeys.add(key);
        return completed(key);
    }

    @Override
    public CompletionStage<BundleRpcContracts.TrustKey> revokeTrustKey(BundleRpcContracts.TrustKey current) {
        RuntimeException failure = takeFailure();
        if (failure != null) {
            return failed(failure);
        }
        BundleRpcContracts.TrustKey revoked = new BundleRpcContracts.TrustKey(
                current.id(),
                current.fingerprint(),
                current.attachmentDigest(),
                current.revision() + 1,
                BundleRpcContracts.TrustState.REVOKED,
                current.createdAt(),
                NOW.plusSeconds(1));
        replace(trustKeys, current, revoked);
        return completed(revoked);
    }

    @Override
    public CompletionStage<List<BundleRpcContracts.TrashEntry>> bundleTrash() {
        return completed(List.copyOf(trash));
    }

    @Override
    public CompletionStage<BundleRpcContracts.Bundle> restoreBundle(BundleRpcContracts.TrashEntry current) {
        RuntimeException failure = takeFailure();
        if (failure != null) {
            return failed(failure);
        }
        BundleRpcContracts.Bundle restored = bundle(current.extensionId(), current.version(), 2, "DISABLED");
        bundles.add(restored);
        replace(trash, current, trashState(current, BundleRpcContracts.TrashState.RESTORED, Optional.of(2L)));
        return completed(restored);
    }

    @Override
    public CompletionStage<BundleRpcContracts.TrashEntry> purgeBundle(
            BundleRpcContracts.TrashEntry current, String confirmation) {
        RuntimeException failure = takeFailure();
        if (failure != null) {
            return failed(failure);
        }
        if (!("PURGE " + current.trashId()).equals(confirmation)) {
            return failed(new IllegalArgumentException("危险确认不匹配"));
        }
        BundleRpcContracts.TrashEntry purged = new BundleRpcContracts.TrashEntry(
                current.trashId(),
                current.extensionId(),
                current.version(),
                current.revision(),
                current.manifestDigest(),
                current.signingKeyId(),
                BundleRpcContracts.TrashState.PURGED,
                current.removedAt(),
                current.restoredRevision(),
                Optional.of(NOW.plusSeconds(2)));
        replace(trash, current, purged);
        return completed(purged);
    }

    static BundleRpcContracts.Bundle bundle(String id, String version, long revision, String state) {
        return new BundleRpcContracts.Bundle(
                id,
                "Example Bundle",
                version,
                revision,
                state,
                MANIFEST_DIGEST,
                "release-key",
                FINGERPRINT,
                Set.of("TOOL"),
                permissions(),
                health());
    }

    static BundleRpcContracts.StageResult stage(String id, String name, String version) {
        return new BundleRpcContracts.StageResult(
                STAGING_DIGEST,
                new BundleRpcContracts.AttachmentPointer(ATTACHMENT_DIGEST, ATTACHMENT_DIGEST),
                MANIFEST_DIGEST,
                id,
                name,
                version,
                "release-key",
                FINGERPRINT,
                Set.of("TOOL"),
                permissions());
    }

    static BundleRpcContracts.TrustKey activeKey() {
        return new BundleRpcContracts.TrustKey(
                "release-key", FINGERPRINT, ATTACHMENT_DIGEST, 1, BundleRpcContracts.TrustState.ACTIVE, NOW, NOW);
    }

    static BundleRpcContracts.TrashEntry trash(BundleRpcContracts.Bundle bundle, String trashId) {
        return new BundleRpcContracts.TrashEntry(
                trashId,
                bundle.id(),
                bundle.version(),
                bundle.revision(),
                bundle.manifestDigest(),
                bundle.signingKeyId(),
                BundleRpcContracts.TrashState.TRASHED,
                NOW,
                Optional.empty(),
                Optional.empty());
    }

    private static BundleRpcContracts.Bundle bundle(
            BundleRpcContracts.StageResult source, long revision, String state) {
        return new BundleRpcContracts.Bundle(
                source.extensionId(),
                source.displayName(),
                source.version(),
                revision,
                state,
                source.manifestDigest(),
                source.signingKeyId(),
                source.signingKeyFingerprint(),
                source.contributionKinds(),
                source.permissions(),
                health());
    }

    private static BundleRpcContracts.Bundle copy(
            BundleRpcContracts.Bundle source, String state, BundleRpcContracts.Health health) {
        return new BundleRpcContracts.Bundle(
                source.id(),
                source.displayName(),
                source.version(),
                source.revision(),
                state,
                source.manifestDigest(),
                source.signingKeyId(),
                source.signingKeyFingerprint(),
                source.contributionKinds(),
                source.permissions(),
                health);
    }

    private static BundleRpcContracts.TrashEntry trashState(
            BundleRpcContracts.TrashEntry source,
            BundleRpcContracts.TrashState state,
            Optional<Long> restoredRevision) {
        return new BundleRpcContracts.TrashEntry(
                source.trashId(),
                source.extensionId(),
                source.version(),
                source.revision(),
                source.manifestDigest(),
                source.signingKeyId(),
                state,
                source.removedAt(),
                restoredRevision,
                Optional.empty());
    }

    private static BundleRpcContracts.PermissionReview permissions() {
        return new BundleRpcContracts.PermissionReview(
                true,
                true,
                false,
                Set.of("api.example.test"),
                Set.of(443),
                true,
                "bundle-worker",
                Duration.ofSeconds(30),
                64 * 1024 * 1024,
                1024 * 1024,
                1,
                32);
    }

    private static BundleRpcContracts.Health health() {
        return new BundleRpcContracts.Health(
                BundleRpcContracts.HealthState.NOT_PROBED, 0, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private RuntimeException takeFailure() {
        RuntimeException failure = nextFailure;
        nextFailure = null;
        return failure;
    }

    private static <T> void replace(List<T> values, T current, T updated) {
        values.set(values.indexOf(current), updated);
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static <T> CompletionStage<T> failed(RuntimeException failure) {
        return CompletableFuture.failedFuture(failure);
    }
}
