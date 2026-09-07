package com.javaclaw.server.persistence;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionRequirements;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.extension.spi.ExtensionTrust;
import com.javaclaw.protocol.BundleRpcContracts;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThirdPartyPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T01:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private Clock clock;

    @BeforeEach
    void initialize() {
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        json = new CanonicalJson();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        installTrustKeys();
    }

    @Test
    void thirdParty目录执行安装退避状态转换和可重试Trash移除() {
        ThirdPartyExtensionRepository repository = new ThirdPartyExtensionRepository(database, json, clock);
        ThirdPartyTrashRepository trash = new ThirdPartyTrashRepository(database, json, clock);
        ExtensionDescriptor descriptor = descriptor("demo.extension", 1);
        String digest = "a".repeat(64);

        ThirdPartyExtensionRecord installed = install(repository, descriptor, digest, "release-key", "demo.extension");
        ThirdPartyExtensionRecord retried = install(repository, descriptor, digest, "release-key", "demo.extension");
        repository.recordFailure(descriptor.id(), 2, NOW.plusSeconds(30), "health failed", false);
        ThirdPartyExtensionRecord failed = repository.find(descriptor.id()).orElseThrow();
        repository.clearFailures(descriptor.id());
        ThirdPartyExtensionRecord starting =
                repository.transition(descriptor.id(), 1, Set.of(ExtensionState.INSTALLED), ExtensionState.STARTING);
        ThirdPartyExtensionRecord disabled =
                repository.transition(descriptor.id(), 1, Set.of(ExtensionState.STARTING), ExtensionState.DISABLED);
        ThirdPartyExtensionRecord removing = repository.beginRemoval(descriptor.id(), 1, "demo.extension-trash");
        ThirdPartyTrashRecord removed = trash.completeRemoval(removing, "demo.extension-trash");
        long nextRevision = repository.nextRevision(descriptor.id());
        ThirdPartyExtensionRecord reinstalled = install(
                repository,
                descriptor("demo.extension", nextRevision),
                "b".repeat(64),
                "release-key",
                "demo.extension");

        assertEquals(ExtensionState.INSTALLED, installed.state());
        assertEquals(installed, retried);
        assertEquals(2, failed.failureCount());
        assertEquals(Optional.of(NOW.plusSeconds(30)), failed.nextRetryAt());
        assertEquals(Optional.of("health failed"), failed.lastFailure());
        assertEquals(ExtensionState.STARTING, starting.state());
        assertEquals(ExtensionState.DISABLED, disabled.state());
        assertEquals(Optional.of("demo.extension-trash"), removing.pendingTrashName());
        assertEquals(reinstalled, repository.find(descriptor.id()).orElseThrow());
        assertEquals("demo.extension-trash", removed.entry().trashId());
        assertEquals(removed, trash.findByRevision(descriptor.id().value(), 1).orElseThrow());
        assertEquals(2, nextRevision);
        assertEquals(2, reinstalled.descriptor().revision());
        assertEquals(List.of(reinstalled), repository.list());
    }

    @Test
    void thirdParty目录拒绝信任层冲突Revision冲突和非法状态() {
        ThirdPartyExtensionRepository repository = new ThirdPartyExtensionRepository(database, json, clock);
        ExtensionDescriptor descriptor = descriptor("demo.extension", 1);
        install(repository, descriptor, "a".repeat(64), "key", "demo.extension");

        assertThrows(
                PersistenceException.class,
                () -> install(repository, descriptor("demo.extension", 2), "b".repeat(64), "key", "demo.extension"));
        assertThrows(
                PersistenceException.class,
                () -> repository.transition(
                        descriptor.id(), 2, Set.of(ExtensionState.INSTALLED), ExtensionState.ENABLED));
        assertThrows(
                PersistenceException.class,
                () -> repository.transition(
                        descriptor.id(), 1, Set.of(ExtensionState.DISABLED), ExtensionState.ENABLED));
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.recordFailure(descriptor.id(), 0, NOW, "failure", false));
        assertThrows(IllegalArgumentException.class, () -> repository.beginRemoval(descriptor.id(), 1, "bad/name"));
        assertThrows(
                IllegalArgumentException.class,
                () -> install(
                        repository,
                        builtInDescriptor("builtin.extension"),
                        "c".repeat(64),
                        "key",
                        "builtin.extension"));

        ExtensionCatalogRepository catalog = new ExtensionCatalogRepository(database, json, clock);
        catalog.installBuiltIn(builtInDescriptor("occupied.extension"));
        assertThrows(
                PersistenceException.class,
                () -> install(
                        repository, descriptor("occupied.extension", 1), "d".repeat(64), "key", "occupied.extension"));
    }

    @Test
    void 内置目录按契约语义比较Set并拒绝真实变化或损坏Payload() throws Exception {
        ExtensionCatalogRepository catalog = new ExtensionCatalogRepository(database, json, clock);
        ExtensionDescriptor descriptor = new ExtensionDescriptor(
                new ExtensionId("builtin.semantic"),
                "Built-in",
                "5.0.0",
                1,
                Set.of(ContributionKind.QUERY, ContributionKind.COMMAND, ContributionKind.VIEW),
                new ExtensionRequirements(
                        ExtensionTrust.BUILT_IN, ExtensionAvailability.OPTIONAL, 2, permission("builtin.semantic")));
        catalog.installBuiltIn(descriptor);
        String ordered = json.encode(descriptor).json();
        String reordered = ordered.replace(
                "\"contributionKinds\":[\"COMMAND\",\"QUERY\",\"VIEW\"]",
                "\"contributionKinds\":[\"VIEW\",\"QUERY\",\"COMMAND\"]");
        assertNotEquals(ordered, reordered);
        updateStoredDescriptor(descriptor.id(), reordered);

        catalog.installBuiltIn(descriptor);
        ExtensionDescriptor changed = new ExtensionDescriptor(
                descriptor.id(),
                "Changed",
                descriptor.version(),
                descriptor.revision(),
                descriptor.contributionKinds(),
                descriptor.requirements());
        assertThrows(PersistenceException.class, () -> catalog.installBuiltIn(changed));

        updateStoredDescriptor(descriptor.id(), "{not-json}");
        assertThrows(PersistenceException.class, () -> catalog.installBuiltIn(descriptor));
    }

    @Test
    void thirdParty失败达到阈值后隔离并可清零() {
        ThirdPartyExtensionRepository repository = new ThirdPartyExtensionRepository(database, json, clock);
        ExtensionDescriptor descriptor = descriptor("demo.extension", 1);
        install(repository, descriptor, "a".repeat(64), "key", "demo.extension");

        repository.recordFailure(descriptor.id(), 3, NOW.plusSeconds(60), "three failures", true);
        ThirdPartyExtensionRecord quarantined = repository.find(descriptor.id()).orElseThrow();
        repository.clearFailures(descriptor.id());
        ThirdPartyExtensionRecord cleared = repository.find(descriptor.id()).orElseThrow();

        assertEquals(ExtensionState.QUARANTINED, quarantined.state());
        assertEquals(0, cleared.failureCount());
        assertTrue(cleared.nextRetryAt().isEmpty());
        assertTrue(cleared.lastFailure().isEmpty());
        assertThrows(
                PersistenceException.class,
                () -> new ThirdPartyTrashRepository(database, json, clock)
                        .completeRemoval(cleared, "missing-removing-state"));
    }

    @Test
    void namespaced文档执行乐观锁并限制单项和总量() {
        H2ThirdPartyDocumentStore store = new H2ThirdPartyDocumentStore(
                database, new ExtensionId("demo.extension"), new ThirdPartyStorageQuota(32, 40, 8, 12), clock);
        CanonicalPayload first = new CanonicalPayload("{\"value\":\"one\"}");
        CanonicalPayload second = new CanonicalPayload("{\"value\":\"two\"}");

        long created = store.put(" item ", 0, first);
        long updated = store.put("item", created, second);

        assertEquals(1, created);
        assertEquals(2, updated);
        assertEquals(second, store.get("item").orElseThrow().payload());
        assertTrue(store.get("absent").isEmpty());
        assertThrows(PersistenceException.class, () -> store.put("item", 1, first));
        assertThrows(IllegalArgumentException.class, () -> store.put("item", -1, first));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.put("large", 0, new CanonicalPayload("{\"value\":\"" + "x".repeat(40) + "\"}")));
        store.put("second", 0, new CanonicalPayload("{\"v\":\"1234567890\"}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.put("third", 0, new CanonicalPayload("{\"v\":\"1234567890\"}")));
        assertThrows(IllegalArgumentException.class, () -> store.get(" "));
    }

    @Test
    void namespacedBlob校验声明长度去重总量和磁盘摘要() throws Exception {
        H2ThirdPartyDocumentStore store = new H2ThirdPartyDocumentStore(
                database, new ExtensionId("demo.extension"), new ThirdPartyStorageQuota(64, 128, 8, 12), clock);
        ClosingInputStream first = new ClosingInputStream("12345678".getBytes(StandardCharsets.UTF_8));
        String digest = store.putBlob(first, 8, "text/plain");
        String duplicate =
                store.putBlob(new ByteArrayInputStream("12345678".getBytes(StandardCharsets.UTF_8)), 8, "text/plain");
        store.putBlob(new ByteArrayInputStream("abcd".getBytes(StandardCharsets.UTF_8)), 4, "text/plain");

        assertTrue(first.closed);
        assertEquals(digest, duplicate);
        assertThrows(
                IllegalArgumentException.class,
                () -> store.putBlob(new ByteArrayInputStream(new byte[1]), 9, "application/octet-stream"));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.putBlob(new ByteArrayInputStream(new byte[2]), 1, "application/octet-stream"));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.putBlob(new ByteArrayInputStream(new byte[1]), 2, "application/octet-stream"));
        assertThrows(
                IllegalArgumentException.class, () -> store.putBlob(new ByteArrayInputStream(new byte[1]), 1, " "));

        Path blob = database.dataRoot()
                .resolve("blobs/extensions/demo.extension")
                .resolve(digest.substring(0, 2))
                .resolve(digest + ".blob");
        Files.writeString(blob, "tampered", StandardCharsets.UTF_8);
        assertThrows(
                SecurityException.class,
                () -> store.putBlob(
                        new ByteArrayInputStream("12345678".getBytes(StandardCharsets.UTF_8)), 8, "text/plain"));
    }

    @Test
    void storageQuota与命名空间拒绝非法配置() {
        assertEquals(256L * 1024, ThirdPartyStorageQuota.defaults().documentBytes());
        assertThrows(IllegalArgumentException.class, () -> new ThirdPartyStorageQuota(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ThirdPartyStorageQuota(2, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ThirdPartyStorageQuota(1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ThirdPartyStorageQuota(1, 1, 2, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new H2ThirdPartyDocumentStore(
                        database, new ExtensionId("bad/id"), ThirdPartyStorageQuota.defaults(), clock));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThirdPartyExtensionRecord(
                        descriptor("demo.extension", 1),
                        ExtensionState.INSTALLED,
                        "a".repeat(64),
                        "key",
                        "demo.extension",
                        permissionReview(),
                        -1,
                        BundleRpcContracts.HealthState.NOT_PROBED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
    }

    @Test
    void namespacedBlob拒绝不可信根目录文件类型与写入父路径() throws Exception {
        Path dataRoot = Files.createDirectories(temporaryDirectory.resolve("blob-root"));
        assertThrows(IllegalArgumentException.class, () -> new ThirdPartyBlobStore(dataRoot, null));
        assertThrows(
                PersistenceException.class,
                () -> new ThirdPartyBlobStore(temporaryDirectory.resolve("missing-root"), "demo.extension"));

        ThirdPartyBlobStore store = new ThirdPartyBlobStore(dataRoot, "demo.extension");
        byte[] content = "12345678".getBytes(StandardCharsets.UTF_8);
        String digest = sha256(content);
        Path blob = dataRoot.resolve(store.write(digest, content));
        Path outside = Files.writeString(temporaryDirectory.resolve("outside-blob"), "12345678");
        Files.delete(blob);
        Files.createSymbolicLink(blob, outside);
        assertThrows(SecurityException.class, () -> store.write(digest, content));
        Files.delete(blob);
        Files.createDirectory(blob);
        assertThrows(SecurityException.class, () -> store.write(digest, content));

        ThirdPartyBlobStore blocked = new ThirdPartyBlobStore(dataRoot, "blocked.extension");
        Path blockedPrefix =
                dataRoot.resolve("blobs/extensions/blocked.extension").resolve(digest.substring(0, 2));
        Files.writeString(blockedPrefix, "occupied");
        assertThrows(PersistenceException.class, () -> blocked.write(digest, content));
    }

    @Test
    void namespaced文档拒绝负长度超大长度非法Key和Blob总量溢出() throws Exception {
        long overInteger = (long) Integer.MAX_VALUE + 1;
        H2ThirdPartyDocumentStore largeQuota = new H2ThirdPartyDocumentStore(
                database,
                new ExtensionId("large.extension"),
                new ThirdPartyStorageQuota(8, 16, overInteger, overInteger),
                clock);
        assertThrows(
                IllegalArgumentException.class,
                () -> largeQuota.putBlob(new ByteArrayInputStream(new byte[0]), -1, "application/octet-stream"));
        assertThrows(
                IllegalArgumentException.class,
                () -> largeQuota.putBlob(
                        new ByteArrayInputStream(new byte[0]), overInteger, "application/octet-stream"));
        assertThrows(IllegalArgumentException.class, () -> largeQuota.get("x".repeat(501)));
        assertThrows(IllegalArgumentException.class, () -> largeQuota.get("bad\0key"));

        H2ThirdPartyDocumentStore bounded = new H2ThirdPartyDocumentStore(
                database, new ExtensionId("bounded.extension"), new ThirdPartyStorageQuota(8, 16, 8, 8), clock);
        bounded.putBlob(new ByteArrayInputStream(new byte[8]), 8, "application/octet-stream");
        assertThrows(
                IllegalArgumentException.class,
                () -> bounded.putBlob(new ByteArrayInputStream(new byte[] {1}), 1, "application/octet-stream"));
    }

    @Test
    void trustKey导入精确幂等并拒绝任一身份字段漂移() {
        ExtensionTrustKeyRepository repository = new ExtensionTrustKeyRepository(database, clock);
        ExtensionTrustKeyRecord current = repository.find("key").orElseThrow();

        ExtensionTrustKeyRecord replayed = repository.importKey(current);
        assertEquals(current.metadata(), replayed.metadata());
        assertArrayEquals(current.encodedKey(), replayed.encodedKey());
        assertThrows(
                PersistenceException.class,
                () -> repository.importKey(changedTrustKey(
                        current, "f".repeat(64), current.metadata().attachmentDigest(), current.encodedKey())));
        assertThrows(
                PersistenceException.class,
                () -> repository.importKey(changedTrustKey(
                        current, current.metadata().fingerprint(), "a".repeat(64), current.encodedKey())));
        assertThrows(
                PersistenceException.class,
                () -> repository.importKey(changedTrustKey(
                        current,
                        current.metadata().fingerprint(),
                        current.metadata().attachmentDigest(),
                        "different-key".getBytes(StandardCharsets.UTF_8))));
        assertThrows(PersistenceException.class, () -> repository.revoke("key", 2));

        ExtensionTrustKeyRepository.RevocationResult revoked = repository.revoke("key", 1);
        ExtensionTrustKeyRepository.RevocationResult replay = repository.revoke("key", 2);
        assertEquals(BundleRpcContracts.TrustState.REVOKED, revoked.key().state());
        assertTrue(replay.disabledExtensions().isEmpty());
    }

    @Test
    void thirdParty幂等安装升级和Trash状态逐字段失败关闭() {
        ThirdPartyExtensionRepository extensions = new ThirdPartyExtensionRepository(database, json, clock);
        ThirdPartyTrashRepository trash = new ThirdPartyTrashRepository(database, json, clock);
        ExtensionDescriptor first = descriptor("branches.extension", 1);
        ThirdPartyExtensionRecord installed = install(extensions, first, "1".repeat(64), "key", "branches.extension");

        assertThrows(
                PersistenceException.class,
                () -> install(extensions, first, "2".repeat(64), "key", "branches.extension"));
        assertThrows(
                PersistenceException.class,
                () -> install(extensions, first, "1".repeat(64), "release-key", "branches.extension"));
        assertThrows(
                PersistenceException.class,
                () -> install(extensions, first, "1".repeat(64), "key", "different-directory"));
        assertThrows(
                PersistenceException.class,
                () -> extensions.install(
                        first,
                        "1".repeat(64),
                        "key",
                        "branches.extension",
                        new BundleRpcContracts.PermissionReview(
                                false,
                                false,
                                false,
                                Set.of(),
                                Set.of(),
                                true,
                                "worker",
                                Duration.ofSeconds(10),
                                1024,
                                1024,
                                1,
                                4)));
    }

    @Test
    void thirdParty升级和Trash状态逐字段失败关闭() {
        ThirdPartyExtensionRepository extensions = new ThirdPartyExtensionRepository(database, json, clock);
        ThirdPartyTrashRepository trash = new ThirdPartyTrashRepository(database, json, clock);
        ExtensionDescriptor first = descriptor("branches.extension", 1);
        ThirdPartyExtensionRecord installed = install(extensions, first, "1".repeat(64), "key", "branches.extension");

        var wrongRevision = new ThirdPartyExtensionRepository.Upgrade(
                descriptor("branches.extension", 3),
                "3".repeat(64),
                "key",
                "branches.extension-3",
                permissionReview(),
                ExtensionState.DISABLED);
        assertThrows(PersistenceException.class, () -> extensions.upgrade(first.id(), 1, wrongRevision));
        var wrongId = new ThirdPartyExtensionRepository.Upgrade(
                descriptor("other.extension", 2),
                "4".repeat(64),
                "key",
                "other.extension-2",
                permissionReview(),
                ExtensionState.DISABLED);
        assertThrows(PersistenceException.class, () -> extensions.upgrade(first.id(), 1, wrongId));
        assertThrows(IllegalArgumentException.class, () -> extensions.beginRemoval(first.id(), 1, null));

        ThirdPartyTrashRecord superseded = trash.recordSuperseded(installed, "branches.extension-v1");
        assertEquals(superseded, trash.recordSuperseded(installed, "branches.extension-v1"));
        assertThrows(
                PersistenceException.class,
                () -> trash.restore(superseded, descriptor("branches.extension", 2), "branches.extension-v2"));
        assertThrows(
                PersistenceException.class, () -> trash.purge(superseded.entry().trashId(), 2));
        ThirdPartyTrashRecord purged = trash.purge(superseded.entry().trashId(), 1);
        assertEquals(purged, trash.purge(superseded.entry().trashId(), 1));
        assertThrows(
                PersistenceException.class,
                () -> trash.restore(purged, descriptor("branches.extension", 2), "branches.extension-v2"));
    }

    private void installTrustKeys() {
        byte[] encoded = "test-key".getBytes(StandardCharsets.UTF_8);
        AttachmentService attachments = new AttachmentService(database, json, clock);
        var metadata = attachments.store(
                AttachmentScope.global(),
                new CommandIdentity("attachment/internal/store", "third-party-trust", 0, "c".repeat(64)),
                BundleRpcContracts.PUBLIC_KEY_MEDIA_TYPE,
                encoded);
        ExtensionTrustKeyRepository trust = new ExtensionTrustKeyRepository(database, clock);
        trust.importKey(ExtensionTrustKeyRecord.active("key", "d".repeat(64), metadata.digest(), encoded, NOW));
        trust.importKey(ExtensionTrustKeyRecord.active("release-key", "e".repeat(64), metadata.digest(), encoded, NOW));
    }

    private static ExtensionTrustKeyRecord changedTrustKey(
            ExtensionTrustKeyRecord source, String fingerprint, String attachmentDigest, byte[] encoded) {
        BundleRpcContracts.TrustKey metadata = new BundleRpcContracts.TrustKey(
                source.metadata().id(),
                fingerprint,
                attachmentDigest,
                source.metadata().revision(),
                source.metadata().state(),
                source.metadata().createdAt(),
                source.metadata().updatedAt());
        return new ExtensionTrustKeyRecord(metadata, encoded);
    }

    private static String sha256(byte[] content) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static ThirdPartyExtensionRecord install(
            ThirdPartyExtensionRepository repository,
            ExtensionDescriptor descriptor,
            String digest,
            String keyId,
            String directory) {
        return repository.install(descriptor, digest, keyId, directory, permissionReview());
    }

    private static BundleRpcContracts.PermissionReview permissionReview() {
        return new BundleRpcContracts.PermissionReview(
                true, false, false, Set.of(), Set.of(), true, "worker", Duration.ofSeconds(10), 1024, 1024, 1, 4);
    }

    private static ExtensionDescriptor descriptor(String id, long revision) {
        return new ExtensionDescriptor(
                new ExtensionId(id),
                "Demo",
                "5.0.0",
                revision,
                Set.of(ContributionKind.QUERY),
                new ExtensionRequirements(
                        ExtensionTrust.THIRD_PARTY, ExtensionAvailability.OPTIONAL, 2, permission(id)));
    }

    private static ExtensionDescriptor builtInDescriptor(String id) {
        return new ExtensionDescriptor(
                new ExtensionId(id),
                "Built-in",
                "5.0.0",
                1,
                Set.of(ContributionKind.QUERY),
                new ExtensionRequirements(ExtensionTrust.BUILT_IN, ExtensionAvailability.OPTIONAL, 2, permission(id)));
    }

    private static PermissionProfile permission(String id) {
        return new PermissionProfile(
                id,
                1,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of("worker"), false, Duration.ofSeconds(10)),
                new ToolPermission(Set.of(), ToolRisk.READ_ONLY, ApprovalRequirement.RISKY),
                new ResourceLimits(1024, 1024, 1, 4));
    }

    private void updateStoredDescriptor(ExtensionId id, String descriptor) throws Exception {
        try (var connection = database.open();
                var statement = connection.prepareStatement("UPDATE CORE.EXTENSION SET DESCRIPTOR = ? WHERE ID = ?")) {
            statement.setString(1, descriptor);
            statement.setString(2, id.value());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static final class ClosingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private ClosingInputStream(byte[] content) {
            super(content);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
