package com.javaclaw.server.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HexFormat;
import java.util.Set;

/** data-v6 内内容寻址 Blob 的原子文件实现。 */
final class AttachmentBlobStore {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path root;

    AttachmentBlobStore(Path dataRoot) {
        try {
            root = dataRoot.toRealPath();
        } catch (IOException failure) {
            throw new PersistenceException("无法解析 data-v6 Blob 根目录", failure);
        }
    }

    String write(String digest, byte[] content) {
        Path relative = relativePath(digest);
        Path target = resolve(relative);
        Path parent = target.getParent();
        Path temporary = null;
        try {
            secureDirectories(parent);
            requireContained(parent.toRealPath());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireContent(target, digest, content.length);
                return relative.toString();
            }
            temporary = Files.createTempFile(parent, ".incoming-", ".blob");
            setPermissions(temporary, FILE_PERMISSIONS);
            Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING);
            force(temporary);
            moveNew(temporary, target);
            temporary = null;
            requireContent(target, digest, content.length);
            return relative.toString();
        } catch (IOException failure) {
            throw new PersistenceException("附件 Blob 写入失败", failure);
        } finally {
            deleteTemporary(temporary);
        }
    }

    String writeVerified(String digest, Path source, long expectedLength) {
        Path relative = relativePath(digest);
        Path target = resolve(relative);
        Path parent = target.getParent();
        Path temporary = null;
        try {
            requireSource(source, digest, expectedLength);
            secureDirectories(parent);
            requireContained(parent.toRealPath());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireContent(target, digest, expectedLength);
                return relative.toString();
            }
            temporary = Files.createTempFile(parent, ".incoming-", ".blob");
            setPermissions(temporary, FILE_PERMISSIONS);
            Files.copy(source, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            force(temporary);
            moveNew(temporary, target);
            temporary = null;
            requireContent(target, digest, expectedLength);
            return relative.toString();
        } catch (IOException failure) {
            throw new PersistenceException("附件 Blob 流式写入失败", failure);
        } finally {
            deleteTemporary(temporary);
        }
    }

    byte[] read(AttachmentRepository.StoredAttachment attachment) {
        Path path = resolve(Path.of(attachment.relativePath()));
        try {
            requireContent(
                    path, attachment.metadata().digest(), attachment.metadata().sizeBytes());
            return Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new PersistenceException("附件 Blob 读取失败", failure);
        }
    }

    private Path resolve(Path relative) {
        if (relative.isAbsolute() || relative.normalize().startsWith("..")) {
            throw new PersistenceException("附件 Blob 路径越界");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new PersistenceException("附件 Blob 路径越界");
        }
        return resolved;
    }

    byte[] readChunk(AttachmentRepository.StoredAttachment attachment, long offset, int maximum) {
        Path path = resolve(Path.of(attachment.relativePath()));
        try {
            requireContained(path.toRealPath());
            if (Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) != attachment.metadata().sizeBytes()) {
                throw new IOException("附件 Blob 类型或大小不一致");
            }
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                channel.position(offset);
                var buffer = java.nio.ByteBuffer.allocate(
                        Math.toIntExact(Math.min(maximum, attachment.metadata().sizeBytes() - offset)));
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                    // 只分配当前页；完整内容摘要在消费全部块后核验，避免逐页重复扫描全部 Blob。
                }
                if (buffer.hasRemaining()) {
                    throw new IOException("附件 Blob 在分块读取时变短");
                }
                return buffer.array();
            }
        } catch (IOException failure) {
            throw new PersistenceException("附件分块读取失败", failure);
        }
    }

    private static Path relativePath(String digest) {
        return Path.of("blobs", "core", digest.substring(0, 2), digest + ".blob");
    }

    private void requireContained(Path path) {
        if (!path.startsWith(root)) {
            throw new PersistenceException("附件 Blob 目录越界");
        }
    }

    private static void requireSource(Path path, String expectedDigest, long expectedLength) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new PersistenceException("附件 staging 不是普通文件");
        }
        requireContent(path, expectedDigest, expectedLength);
    }

    private static void requireContent(Path path, String expectedDigest, long expectedLength) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new PersistenceException("附件 Blob 不是普通文件");
        }
        long size = Files.size(path);
        if (size != expectedLength) {
            throw new PersistenceException("附件 Blob 大小不一致");
        }
        String actual = HexFormat.of().formatHex(sha256(path));
        if (!actual.equals(expectedDigest)) {
            throw new PersistenceException("附件 Blob 摘要不一致");
        }
    }

    private static byte[] sha256(byte[] content) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(content);
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static byte[] sha256(Path path) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = Files.newInputStream(path)) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) {
                        digest.update(buffer, 0, count);
                    }
                }
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static void secureDirectories(Path directory) throws IOException {
        Files.createDirectories(directory);
        setPermissions(directory, DIRECTORY_PERMISSIONS);
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows 使用平台 ACL；Java NIO 不在此处扩大既有 ACL。
        }
    }

    private static void moveNew(Path source, Path target) throws IOException {
        try {
            Files.move(source, target);
        } catch (FileAlreadyExistsException raced) {
            Files.deleteIfExists(source);
        }
    }

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void deleteTemporary(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // 临时文件位于受控 data-v6 内；下次瘦身审计可以安全清理。
        }
    }
}
