package com.javaclaw.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BundleWireContractsCoverageTest {
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    private final CanonicalJson json = new CanonicalJson();

    @Test
    void Bundle管理请求和快照保持完整Wire契约() {
        BundleRpcContracts.AttachmentPointer attachment = new BundleRpcContracts.AttachmentPointer(DIGEST_A, DIGEST_A);
        BundleRpcContracts.Health health = new BundleRpcContracts.Health(
                BundleRpcContracts.HealthState.HEALTHY, 0, Optional.of(NOW), Optional.empty(), Optional.empty());
        BundleRpcContracts.Bundle bundle = new BundleRpcContracts.Bundle(
                "demo.extension",
                "Demo",
                "5.0.0",
                3,
                "ENABLED",
                DIGEST_A,
                "release-key",
                DIGEST_B,
                Set.of("TOOL", "VIEW"),
                permissions(),
                health);

        assertRoundTrip(new BundleRpcContracts.StagePayload(attachment), BundleRpcContracts.StagePayload.class);
        assertRoundTrip(
                new BundleRpcContracts.CommitPayload(DIGEST_A, DIGEST_B), BundleRpcContracts.CommitPayload.class);
        assertRoundTrip(new BundleRpcContracts.BundlePayload(bundle.id()), BundleRpcContracts.BundlePayload.class);
        assertEquals(bundle, assertRoundTrip(bundle, BundleRpcContracts.Bundle.class));
        assertEquals(
                List.of(bundle),
                assertRoundTrip(
                                new BundleRpcContracts.BundleListResult(List.of(bundle)),
                                BundleRpcContracts.BundleListResult.class)
                        .bundles());
        assertEquals(
                bundle,
                assertRoundTrip(new BundleRpcContracts.BundleResult(bundle), BundleRpcContracts.BundleResult.class)
                        .bundle());
    }

    @Test
    void TrustKey与Trash管理请求和结果保持完整Wire契约() {
        BundleRpcContracts.AttachmentPointer attachment = new BundleRpcContracts.AttachmentPointer(DIGEST_A, DIGEST_A);
        BundleRpcContracts.TrustKey key = new BundleRpcContracts.TrustKey(
                "release-key", DIGEST_A, DIGEST_B, 2, BundleRpcContracts.TrustState.ACTIVE, NOW, NOW);
        BundleRpcContracts.TrashEntry entry = new BundleRpcContracts.TrashEntry(
                "trash-one",
                "demo.extension",
                "5.0.0",
                2,
                DIGEST_A,
                key.id(),
                BundleRpcContracts.TrashState.TRASHED,
                NOW,
                Optional.empty(),
                Optional.empty());

        assertRoundTrip(new BundleRpcContracts.TrustKeyPayload(key.id()), BundleRpcContracts.TrustKeyPayload.class);
        assertRoundTrip(
                new BundleRpcContracts.TrustKeyImportPayload(key.id(), attachment),
                BundleRpcContracts.TrustKeyImportPayload.class);
        assertEquals(
                List.of(key),
                assertRoundTrip(
                                new BundleRpcContracts.TrustKeyListResult(List.of(key)),
                                BundleRpcContracts.TrustKeyListResult.class)
                        .keys());
        assertEquals(
                key,
                assertRoundTrip(new BundleRpcContracts.TrustKeyResult(key), BundleRpcContracts.TrustKeyResult.class)
                        .key());
        assertRoundTrip(new BundleRpcContracts.TrashPayload(entry.trashId()), BundleRpcContracts.TrashPayload.class);
        assertEquals(
                List.of(entry),
                assertRoundTrip(
                                new BundleRpcContracts.TrashListResult(List.of(entry)),
                                BundleRpcContracts.TrashListResult.class)
                        .entries());
        assertEquals(
                entry,
                assertRoundTrip(new BundleRpcContracts.TrashResult(entry), BundleRpcContracts.TrashResult.class)
                        .entry());
    }

    @Test
    void Bundle资源限制版本摘要与标识拒绝非法输入() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BundleRpcContracts.PermissionReview(
                        true, false, true, Set.of(), Set.of(), true, "worker", Duration.ofSeconds(1), 1, 1, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BundleRpcContracts.PermissionReview(
                        true, false, false, Set.of(), Set.of(), true, "worker", Duration.ZERO, 1, 1, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BundleRpcContracts.Health(
                        BundleRpcContracts.HealthState.BACKING_OFF,
                        -1,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> bundle(0));
        assertThrows(IllegalArgumentException.class, () -> new BundleRpcContracts.BundlePayload(" "));
        assertThrows(
                IllegalArgumentException.class, () -> new BundleRpcContracts.CommitPayload("not-a-digest", DIGEST_A));
        assertThrows(IllegalArgumentException.class, () -> new BundleRpcContracts.TrustKeyPayload("bad/key"));
    }

    private BundleRpcContracts.Bundle bundle(long revision) {
        return new BundleRpcContracts.Bundle(
                "demo.extension",
                "Demo",
                "5.0.0",
                revision,
                "ENABLED",
                DIGEST_A,
                "release-key",
                DIGEST_B,
                Set.of(),
                permissions(),
                new BundleRpcContracts.Health(
                        BundleRpcContracts.HealthState.NOT_PROBED,
                        0,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
    }

    private static BundleRpcContracts.PermissionReview permissions() {
        return new BundleRpcContracts.PermissionReview(
                true,
                true,
                true,
                Set.of("example.test"),
                Set.of(443),
                true,
                "worker",
                Duration.ofSeconds(30),
                1_048_576,
                65_536,
                1,
                8);
    }

    private <T> T assertRoundTrip(T value, Class<T> type) {
        T decoded = json.decode(json.encode(value), type);
        assertEquals(value, decoded);
        return decoded;
    }
}
