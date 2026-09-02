package com.javaclaw.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BundleRpcContractsTest {
    private static final String DIGEST = "a".repeat(64);

    @Test
    void attachmentStaging固定内容寻址身份和权限审阅() {
        var pointer = new BundleRpcContracts.AttachmentPointer(DIGEST.toUpperCase(), DIGEST);
        BundleRpcContracts.PermissionReview permissions = permissions();
        var staged = new BundleRpcContracts.StageResult(
                DIGEST,
                pointer,
                DIGEST,
                "demo.extension",
                "Demo",
                "5.0.0",
                "release",
                "b".repeat(64),
                Set.of("TOOL"),
                permissions);

        assertEquals(DIGEST, staged.attachment().attachmentId());
        assertEquals(permissions, staged.permissions());
        assertThrows(
                IllegalArgumentException.class, () -> new BundleRpcContracts.AttachmentPointer(DIGEST, "b".repeat(64)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BundleRpcContracts.PermissionReview(
                        false, true, false, Set.of(), Set.of(), true, "worker", Duration.ofSeconds(1), 1, 1, 1, 1));
    }

    @Test
    void trust和Trash契约要求精确Revision与危险确认() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        var key = new BundleRpcContracts.TrustKey(
                "release", DIGEST, "b".repeat(64), 1, BundleRpcContracts.TrustState.ACTIVE, now, now);
        var trash = new BundleRpcContracts.TrashEntry(
                "demo-r1-trash",
                "demo.extension",
                "5.0.0",
                1,
                DIGEST,
                key.id(),
                BundleRpcContracts.TrashState.TRASHED,
                now,
                Optional.empty(),
                Optional.empty());

        assertEquals(BundleRpcContracts.TrustState.ACTIVE, key.state());
        assertEquals(
                "PURGE demo-r1-trash",
                new BundleRpcContracts.TrashPurgePayload(trash.trashId(), "PURGE " + trash.trashId()).confirmation());
        assertThrows(
                IllegalArgumentException.class,
                () -> new BundleRpcContracts.TrashPurgePayload(trash.trashId(), "PURGE OTHER"));
    }

    private static BundleRpcContracts.PermissionReview permissions() {
        return new BundleRpcContracts.PermissionReview(
                true,
                false,
                false,
                Set.of("example.com"),
                Set.of(443),
                true,
                "worker",
                Duration.ofSeconds(5),
                1024,
                512,
                1,
                4);
    }
}
