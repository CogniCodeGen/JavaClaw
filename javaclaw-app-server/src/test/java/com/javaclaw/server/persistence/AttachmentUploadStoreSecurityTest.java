package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.AttachmentUploadSession;
import com.javaclaw.api.AttachmentUploadState;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentUploadStoreSecurityTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private Path dataRoot;
    private AttachmentUploadStore store;

    @BeforeEach
    void initializeStore() throws Exception {
        dataRoot = Files.createDirectory(temporaryDirectory.resolve("data-v6"));
        store = new AttachmentUploadStore(dataRoot);
    }

    @Test
    void chunksAreDurableIdempotentAndAssembledInDeclaredOrder() throws Exception {
        String uploadId = UUID.randomUUID().toString();
        byte[] first = "first-".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);
        byte[] expected = "first-second".getBytes(StandardCharsets.UTF_8);
        store.create(uploadId);

        store.writeChunk(uploadId, 0, first);
        store.writeChunk(uploadId, 0, first);
        store.writeChunk(uploadId, 1, second);
        AttachmentUploadStore.AssembledAttachment assembled = store.assemble(session(uploadId, expected, 2));

        assertEquals(ManagedWorktreePolicy.sha256(expected), assembled.digest());
        assertEquals(expected.length, assembled.sizeBytes());
        assertArrayEquals(expected, Files.readAllBytes(assembled.path()));
        assertThrows(
                AttachmentUploadStore.StagingCorruptionException.class,
                () -> store.writeChunk(uploadId, 0, "changed".getBytes(StandardCharsets.UTF_8)));
        assertThrows(PersistenceException.class, () -> store.create(uploadId));
    }

    @Test
    void assemblyRejectsMissingOversizedAndDigestMismatchedChunks() {
        String missing = UUID.randomUUID().toString();
        byte[] twoBytes = {1, 2};
        store.create(missing);
        store.writeChunk(missing, 0, new byte[] {1});
        assertThrows(
                AttachmentUploadStore.StagingCorruptionException.class,
                () -> store.assemble(session(missing, twoBytes, 2)));

        String oversized = UUID.randomUUID().toString();
        store.create(oversized);
        store.writeChunk(oversized, 0, twoBytes);
        assertThrows(
                AttachmentUploadStore.StagingCorruptionException.class,
                () -> store.assemble(session(oversized, new byte[] {1}, 1)));

        String mismatch = UUID.randomUUID().toString();
        store.create(mismatch);
        store.writeChunk(mismatch, 0, twoBytes);
        AttachmentUploadSession wrongDigest = session(mismatch, new byte[] {2, 1}, 1);
        assertThrows(AttachmentUploadStore.StagingCorruptionException.class, () -> store.assemble(wrongDigest));
    }

    @Test
    void orphanCleanupOnlyDeletesCanonicalUnclaimedSessionDirectories() throws Exception {
        String active = UUID.randomUUID().toString();
        String orphan = UUID.randomUUID().toString();
        store.create(active);
        store.create(orphan);
        Path root = stagingRoot();
        Path unrelatedDirectory = Files.createDirectory(root.resolve("not-a-session"));
        Path unrelatedFile = Files.writeString(root.resolve("note.txt"), "keep");

        store.cleanupOrphans(Set.of(active));

        assertTrue(Files.isDirectory(root.resolve(active)));
        assertFalse(Files.exists(root.resolve(orphan)));
        assertTrue(Files.isDirectory(unrelatedDirectory));
        assertTrue(Files.isRegularFile(unrelatedFile));
        store.cleanup(active);
        store.cleanup(active);
        assertFalse(Files.exists(root.resolve(active)));
    }

    @Test
    void stagingRejectsInvalidIdentifiersAndSymbolicLinkSessionsOrChunks() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> store.create("not-a-uuid"));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.create(UUID.randomUUID().toString().toUpperCase()));

        Path outsideDirectory = Files.createDirectory(temporaryDirectory.resolve("outside"));
        String linkedSession = UUID.randomUUID().toString();
        Files.createSymbolicLink(stagingRoot().resolve(linkedSession), outsideDirectory);
        assertThrows(
                AttachmentUploadStore.StagingCorruptionException.class,
                () -> store.writeChunk(linkedSession, 0, new byte[] {1}));

        String linkedChunk = UUID.randomUUID().toString();
        store.create(linkedChunk);
        Path outsideFile = Files.write(temporaryDirectory.resolve("outside.bin"), new byte[] {1});
        Files.createSymbolicLink(stagingRoot().resolve(linkedChunk).resolve("0000.chunk"), outsideFile);
        assertThrows(
                AttachmentUploadStore.StagingCorruptionException.class,
                () -> store.writeChunk(linkedChunk, 0, new byte[] {1}));
    }

    @Test
    void regularFileDataRootCannotBecomeManagedStaging() throws Exception {
        Path invalidRoot = Files.writeString(temporaryDirectory.resolve("not-a-directory"), "blocked");

        assertThrows(PersistenceException.class, () -> new AttachmentUploadStore(invalidRoot));
    }

    private AttachmentUploadSession session(String uploadId, byte[] declaration, int chunkCount) {
        return new AttachmentUploadSession(
                uploadId,
                AttachmentScope.global(),
                AttachmentUploadState.ACTIVE,
                "application/octet-stream",
                ManagedWorktreePolicy.sha256(declaration),
                declaration.length,
                declaration.length,
                chunkCount,
                1,
                NOW,
                NOW,
                NOW.plusSeconds(60),
                Optional.empty(),
                Optional.empty());
    }

    private Path stagingRoot() {
        return dataRoot.resolve("staging/attachment-uploads");
    }
}
