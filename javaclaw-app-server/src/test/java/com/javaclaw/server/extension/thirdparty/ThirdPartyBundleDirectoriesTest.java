package com.javaclaw.server.extension.thirdparty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThirdPartyBundleDirectoriesTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    private ThirdPartyBundleDirectories directories;

    @BeforeEach
    void initializeDirectories() {
        directories = new ThirdPartyBundleDirectories(temporaryDirectory.resolve("data-v6"), CLOCK);
    }

    @Test
    void installTrashRestoreAndPurgeRemainInsideManagedDirectories() throws Exception {
        String firstDigest = "a".repeat(64);
        Path firstStage = Files.createDirectory(directories.staging(firstDigest));
        Files.writeString(firstStage.resolve("worker.bin"), "bundle");
        Path installed = directories.install(firstStage, "bundle-r1");
        assertEquals(installed, directories.requireInstalled(installed));

        Path secondStage = Files.createDirectory(directories.staging("b".repeat(64)));
        assertThrows(IllegalStateException.class, () -> directories.install(secondStage, "bundle-r1"));
        String trashName = directories.trashName("bundle", 1, firstDigest);
        assertEquals(trashName, directories.moveToTrash("bundle-r1", trashName));
        assertEquals(trashName, directories.moveToTrash("bundle-r1", trashName));

        Files.createDirectory(directories.installed("bundle-r1"));
        assertThrows(IllegalStateException.class, () -> directories.restoreFromTrash(trashName, "bundle-r1"));
        directories.purgeInstalled("bundle-r1");
        Path restored = directories.restoreFromTrash(trashName, "bundle-r1");
        assertTrue(Files.isDirectory(restored));
        directories.purgeInstalled("bundle-r1");
        directories.purgeInstalled("bundle-r1");
        directories.purgeTrash("missing-trash");
        assertFalse(Files.exists(restored));
    }

    @Test
    void installAndTrashNamesValidateRevisionDigestAndDirectChildOwnership() throws Exception {
        assertEquals("extension-r2-aaaaaaaaaaaa", directories.installDirectory("extension", 2, "a".repeat(64)));
        assertTrue(directories.trashName("extension", 2, "a".repeat(64)).startsWith("extension-r2-"));
        assertThrows(
                IllegalArgumentException.class, () -> directories.installDirectory("extension", 0, "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> directories.trashName("extension", 0, "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> directories.staging(null));
        assertThrows(IllegalArgumentException.class, () -> directories.staging("A".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> directories.installed("../escape"));

        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        assertThrows(SecurityException.class, () -> directories.requireStaging(outside));
        Path link = directories.stagingRoot().resolve("c".repeat(64));
        Files.createSymbolicLink(link, outside);
        assertThrows(SecurityException.class, () -> directories.requireStaging(link));
        assertThrows(IllegalStateException.class, () -> directories.moveToTrash("missing-install", "missing-trash"));
    }

    @Test
    void dataRootIdentityAndExtensionDirectorySymlinksFailClosed() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThirdPartyBundleDirectories(temporaryDirectory.resolve("wrong-root"), CLOCK));

        Path fileParent = Files.createDirectory(temporaryDirectory.resolve("file-parent"));
        Path regularFileRoot = Files.writeString(fileParent.resolve("data-v6"), "blocked");
        assertThrows(IllegalStateException.class, () -> new ThirdPartyBundleDirectories(regularFileRoot, CLOCK));

        Path root = Files.createDirectory(temporaryDirectory.resolve("nested")).resolve("data-v6");
        com.javaclaw.nativehost.ManagedRuntimeDirectory.prepare(
                root.toAbsolutePath().normalize());
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside-extensions"));
        Files.createSymbolicLink(root.resolve("extensions"), outside);
        assertThrows(SecurityException.class, () -> new ThirdPartyBundleDirectories(root, CLOCK));
    }
}
