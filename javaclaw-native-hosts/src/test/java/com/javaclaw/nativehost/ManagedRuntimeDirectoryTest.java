package com.javaclaw.nativehost;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedRuntimeDirectoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void newDirectoryIsPrivateWritableAndReentrant() throws Exception {
        Path root = temporaryDirectory.resolve("data-v6");

        ManagedRuntimeDirectory.prepare(root);
        ManagedRuntimeDirectory.prepare(root);

        assertTrue(Files.isDirectory(root));
        try (var files = Files.list(root)) {
            assertEquals(0, files.count());
        }
        if (root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
        }
    }

    @Test
    void legacyRootAndDescendantsAreRejectedBeforeAnyWrite() throws Exception {
        Path legacy = Files.createDirectory(temporaryDirectory.resolve("data-v5"));
        Path sentinel = Files.writeString(legacy.resolve("sentinel"), "旧数据必须保持原样");
        var before = Files.getLastModifiedTime(legacy);

        assertThrows(IOException.class, () -> ManagedRuntimeDirectory.prepare(legacy));
        assertThrows(IOException.class, () -> ManagedRuntimeDirectory.prepare(legacy.resolve("data-v6")));

        assertEquals("旧数据必须保持原样", Files.readString(sentinel));
        assertEquals(before, Files.getLastModifiedTime(legacy));
        assertFalse(Files.exists(legacy.resolve("data-v6")));
    }

    @Test
    void parentAliasCannotCreateDirectoriesInsideLegacyData() throws Exception {
        Assumptions.assumeTrue(
                temporaryDirectory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path legacy = Files.createDirectory(temporaryDirectory.resolve("data-v5"));
        Path sentinel = Files.writeString(legacy.resolve("sentinel"), "旧目录不得经别名写入");
        Path alias = Files.createSymbolicLink(temporaryDirectory.resolve("alias"), legacy);
        var before = Files.getLastModifiedTime(legacy);

        assertThrows(IOException.class, () -> ManagedRuntimeDirectory.prepare(alias.resolve("data-v6")));
        assertThrows(IOException.class, () -> ManagedRuntimeDirectory.prepare(alias.resolve("missing/data-v6")));

        assertEquals("旧目录不得经别名写入", Files.readString(sentinel));
        assertEquals(before, Files.getLastModifiedTime(legacy));
        try (var contents = Files.list(legacy)) {
            assertEquals(java.util.List.of(sentinel), contents.toList());
        }
    }

    @Test
    void ordinaryParentAliasRemainsUsableWithoutRelaxingRootPermissions() throws Exception {
        Assumptions.assumeTrue(
                temporaryDirectory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path target = Files.createDirectory(temporaryDirectory.resolve("fresh"));
        Path alias = Files.createSymbolicLink(temporaryDirectory.resolve("alias"), target);

        ManagedRuntimeDirectory.prepare(alias.resolve("nested/data-v6"));

        Path root = target.resolve("nested/data-v6");
        assertTrue(Files.isDirectory(root));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
    }

    @Test
    void publicDirectoryAndReadOnlyRootAreRejectedWithoutPermissionRepair() throws Exception {
        Assumptions.assumeTrue(
                temporaryDirectory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path root = Files.createDirectory(temporaryDirectory.resolve("data-v6"));
        var publicPermissions = PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(root, publicPermissions);

        assertThrows(IOException.class, () -> ManagedRuntimeDirectory.prepare(root));
        assertEquals(publicPermissions, Files.getPosixFilePermissions(root));
        try {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("r-x------"));
            Assumptions.assumeFalse(Files.isWritable(root));
            assertThrows(IOException.class, () -> ManagedRuntimeDirectory.prepare(root));
        } finally {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void fileAndSymbolicLinkCannotBecomeTheDataRoot() throws Exception {
        Path fileRoot = Files.createFile(temporaryDirectory.resolve("data-v6"));
        assertThrows(IOException.class, () -> ManagedRuntimeDirectory.prepare(fileRoot));
        Files.delete(fileRoot);
        Assumptions.assumeTrue(
                temporaryDirectory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path target = Files.createDirectory(temporaryDirectory.resolve("target"));
        Files.createSymbolicLink(fileRoot, target);

        assertThrows(IOException.class, () -> ManagedRuntimeDirectory.prepare(fileRoot));
        try (var files = Files.list(target)) {
            assertEquals(0, files.count());
        }
    }
}
