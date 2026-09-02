package com.javaclaw.server.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import com.javaclaw.api.AttachmentUploadSession;

/** data-v5 内分块上传 staging 的有界文件实现。 */
final class AttachmentUploadStore {
    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path root;

    AttachmentUploadStore(Path dataRoot) {
        try {
            Path staging =
                    dataRoot.toAbsolutePath().normalize().resolve("staging").resolve("attachment-uploads");
            Files.createDirectories(staging);
            setPermissions(staging, DIRECTORY_PERMISSIONS);
            root = staging.toRealPath();
        } catch (IOException failure) {
            throw new PersistenceException("无法创建 Attachment 上传 staging", failure);
        }
    }

    void create(String uploadId) {
        Path directory = sessionPath(uploadId);
        try {
            Files.createDirectory(directory);
            setPermissions(directory, DIRECTORY_PERMISSIONS);
        } catch (FileAlreadyExistsException conflict) {
            throw new PersistenceException("Attachment 上传 staging 标识冲突", conflict);
        } catch (IOException failure) {
            throw new PersistenceException("无法创建 Attachment 上传 staging", failure);
        }
    }

    void writeChunk(String uploadId, int chunkIndex, byte[] content) {
        Path directory = requireSession(uploadId);
        Path target = directory.resolve(chunkName(chunkIndex));
        Path temporary = null;
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireSameChunk(target, content);
                return;
            }
            temporary = Files.createTempFile(directory, ".incoming-", ".chunk");
            setPermissions(temporary, FILE_PERMISSIONS);
            writeDurably(temporary, content);
            moveNew(temporary, target);
            temporary = null;
            requireSameChunk(target, content);
        } catch (IOException failure) {
            throw new PersistenceException("Attachment chunk staging 写入失败", failure);
        } finally {
            deleteTemporary(temporary);
        }
    }

    AssembledAttachment assemble(AttachmentUploadSession session) {
        Path directory = requireSession(session.id());
        Path assembled = directory.resolve("assembled.blob");
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, ".assembling-", ".blob");
            setPermissions(temporary, FILE_PERMISSIONS);
            AssemblyDigest digest = copyChunks(session, temporary);
            requireDeclaration(session, digest);
            moveReplace(temporary, assembled);
            temporary = null;
            return new AssembledAttachment(assembled, digest.hex(), digest.bytes());
        } catch (IOException failure) {
            throw new PersistenceException("Attachment staging 合并失败", failure);
        } finally {
            deleteTemporary(temporary);
        }
    }

    void cleanup(String uploadId) {
        Path directory = sessionPath(uploadId);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            deleteTree(directory);
        } catch (IOException failure) {
            throw new PersistenceException("Attachment 上传 staging 回收失败", failure);
        }
    }

    void cleanupOrphans(Set<String> activeUploadIds) {
        try (var children = Files.list(root)) {
            children.filter(this::isManagedSession)
                    .filter(path -> !activeUploadIds.contains(path.getFileName().toString()))
                    .forEach(path -> cleanup(path.getFileName().toString()));
        } catch (IOException failure) {
            throw new PersistenceException("Attachment 孤立 staging 审计失败", failure);
        }
    }

    private AssemblyDigest copyChunks(AttachmentUploadSession session, Path target) throws IOException {
        MessageDigest digest = sha256();
        long total = 0;
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int index = 0; index < session.nextChunkIndex(); index++) {
                Path chunk = requireChunk(session.id(), index);
                try (InputStream input = Files.newInputStream(chunk)) {
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (count == 0) {
                            continue;
                        }
                        total = Math.addExact(total, count);
                        if (total > session.expectedSizeBytes()) {
                            throw new StagingCorruptionException("Attachment staging 超过声明大小");
                        }
                        digest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                    }
                }
            }
        }
        return new AssemblyDigest(HexFormat.of().formatHex(digest.digest()), total);
    }

    private Path requireChunk(String uploadId, int chunkIndex) {
        Path chunk = requireSession(uploadId).resolve(chunkName(chunkIndex));
        if (Files.isSymbolicLink(chunk) || !Files.isRegularFile(chunk, LinkOption.NOFOLLOW_LINKS)) {
            throw new StagingCorruptionException("Attachment staging 缺少连续 chunk");
        }
        return chunk;
    }

    private Path requireSession(String uploadId) {
        Path directory = sessionPath(uploadId);
        try {
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new StagingCorruptionException("Attachment staging 目录不存在或不安全");
            }
            Path real = directory.toRealPath();
            if (!real.getParent().equals(root)) {
                throw new StagingCorruptionException("Attachment staging 目录越界");
            }
            return real;
        } catch (IOException failure) {
            throw new PersistenceException("Attachment staging 目录校验失败", failure);
        }
    }

    private Path sessionPath(String uploadId) {
        String canonical = UUID.fromString(uploadId).toString();
        if (!canonical.equals(uploadId)) {
            throw new IllegalArgumentException("uploadId must be a canonical UUID");
        }
        Path candidate = root.resolve(canonical).normalize();
        if (!candidate.getParent().equals(root)) {
            throw new SecurityException("Attachment staging path escapes its managed root");
        }
        return candidate;
    }

    private boolean isManagedSession(Path path) {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return UUID.fromString(path.getFileName().toString())
                    .toString()
                    .equals(path.getFileName().toString());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static void requireSameChunk(Path target, byte[] expected) throws IOException {
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new StagingCorruptionException("Attachment chunk 不是普通文件");
        }
        if (Files.size(target) != expected.length
                || !MessageDigest.isEqual(sha256().digest(expected), digest(target))) {
            throw new StagingCorruptionException("Attachment chunk 与幂等重试内容不一致");
        }
    }

    private static void writeDurably(Path target, byte[] content) throws IOException {
        try (FileChannel channel =
                FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void requireDeclaration(AttachmentUploadSession session, AssemblyDigest actual) {
        if (actual.bytes() != session.expectedSizeBytes() || !actual.hex().equals(session.expectedDigest())) {
            throw new StagingCorruptionException("Attachment staging 与声明的大小或摘要不一致");
        }
    }

    private static byte[] digest(Path path) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (InputStream input = Files.newInputStream(path)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String chunkName(int chunkIndex) {
        return "%04d.chunk".formatted(chunkIndex);
    }

    private static void moveNew(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows 使用发行包设置的 ACL；此处不扩大继承权限。
        }
    }

    private static void deleteTemporary(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // 临时文件只位于受管 staging，会由会话终止或启动恢复再次清理。
        }
    }

    record AssembledAttachment(Path path, String digest, long sizeBytes) {}

    private record AssemblyDigest(String hex, long bytes) {}

    static final class StagingCorruptionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StagingCorruptionException(String message) {
            super(message);
        }
    }
}
