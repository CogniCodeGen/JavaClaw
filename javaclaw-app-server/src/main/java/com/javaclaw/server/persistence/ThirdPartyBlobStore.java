package com.javaclaw.server.persistence;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** data-v6 中按扩展隔离的内容寻址 Blob 文件实现。 */
final class ThirdPartyBlobStore {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path dataRoot;
    private final Path extensionRoot;

    ThirdPartyBlobStore(Path dataRoot, String extensionId) {
        if (extensionId == null || !extensionId.matches("[a-z][a-z0-9.-]{1,63}")) {
            throw new IllegalArgumentException("invalid third-party extension id");
        }
        try {
            this.dataRoot = dataRoot.toRealPath();
            Path root = this.dataRoot.resolve("blobs").resolve("extensions").resolve(extensionId);
            secureDirectories(root);
            extensionRoot = root.toRealPath();
            requireContained(extensionRoot);
        } catch (IOException failure) {
            throw new PersistenceException("无法初始化第三方 Blob 目录", failure);
        }
    }

    String write(String digest, byte[] content) {
        Path relative = Path.of(
                "blobs",
                "extensions",
                extensionRoot.getFileName().toString(),
                digest.substring(0, 2),
                digest + ".blob");
        Path target = dataRoot.resolve(relative).normalize();
        Path temporary = null;
        try {
            requireContained(target);
            secureDirectories(target.getParent());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireContent(target, digest, content.length);
                return relative.toString();
            }
            temporary = Files.createTempFile(target.getParent(), ".incoming-", ".blob");
            setPermissions(temporary, FILE_PERMISSIONS);
            Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target);
            } catch (FileAlreadyExistsException raced) {
                Files.deleteIfExists(temporary);
            }
            temporary = null;
            requireContent(target, digest, content.length);
            return relative.toString();
        } catch (IOException failure) {
            throw new PersistenceException("第三方 Blob 写入失败", failure);
        } finally {
            deleteTemporary(temporary);
        }
    }

    private void requireContained(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(extensionRoot) && !normalized.equals(extensionRoot)) {
            throw new SecurityException("third-party blob path escapes its namespace");
        }
    }

    private static void requireContent(Path path, String digest, int expectedLength) throws IOException {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) != expectedLength) {
            throw new SecurityException("third-party blob file type or size changed");
        }
        String actual = digest(Files.readAllBytes(path));
        if (!actual.equals(digest)) {
            throw new SecurityException("third-party blob digest changed");
        }
    }

    private static String digest(byte[] content) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static void secureDirectories(Path path) throws IOException {
        Files.createDirectories(path);
        setPermissions(path, DIRECTORY_PERMISSIONS);
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows 由 data-v6 ACL 管理；此处不扩大权限。
        }
    }

    private static void deleteTemporary(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // 临时文件仍位于当前扩展的受管命名空间。
        }
    }
}
