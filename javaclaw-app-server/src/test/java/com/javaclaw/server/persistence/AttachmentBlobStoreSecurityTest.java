package com.javaclaw.server.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AttachmentMetadata;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttachmentBlobStoreSecurityTest {
    @TempDir
    Path temporaryDirectory;

    private Path dataRoot;
    private AttachmentBlobStore blobs;

    @BeforeEach
    void initialize() throws Exception {
        dataRoot = Files.createDirectory(temporaryDirectory.resolve("data-v6"));
        blobs = new AttachmentBlobStore(dataRoot);
    }

    @Test
    void 内容寻址写入读取和流式写入均验证权威摘要() throws Exception {
        byte[] directContent = "direct attachment".getBytes(StandardCharsets.UTF_8);
        String directDigest = ManagedWorktreePolicy.sha256(directContent);
        String directPath = blobs.write(directDigest, directContent);

        assertEquals(directPath, blobs.write(directDigest, directContent));
        assertArrayEquals(directContent, blobs.read(stored(directDigest, directContent.length, directPath)));

        byte[] streamedContent = "streamed attachment".getBytes(StandardCharsets.UTF_8);
        String streamedDigest = ManagedWorktreePolicy.sha256(streamedContent);
        Path staging = temporaryDirectory.resolve("staging.bin");
        Files.write(staging, streamedContent);
        String streamedPath = blobs.writeVerified(streamedDigest, staging, streamedContent.length);

        assertEquals(streamedPath, blobs.writeVerified(streamedDigest, staging, streamedContent.length));
        assertArrayEquals(streamedContent, blobs.read(stored(streamedDigest, streamedContent.length, streamedPath)));
    }

    @Test
    void 已存在Blob发生大小或摘要篡改时所有入口都失败关闭() throws Exception {
        byte[] content = "trusted".getBytes(StandardCharsets.UTF_8);
        String digest = ManagedWorktreePolicy.sha256(content);
        String relative = blobs.write(digest, content);
        Path target = dataRoot.resolve(relative);

        Files.write(target, "changed-size".getBytes(StandardCharsets.UTF_8));
        assertThrows(PersistenceException.class, () -> blobs.write(digest, content));
        assertThrows(PersistenceException.class, () -> blobs.read(stored(digest, content.length, relative)));

        byte[] sameLengthTamper = "untrust".getBytes(StandardCharsets.UTF_8);
        assertEquals(content.length, sameLengthTamper.length);
        Files.write(target, sameLengthTamper);
        assertThrows(PersistenceException.class, () -> blobs.write(digest, content));
        assertThrows(PersistenceException.class, () -> blobs.read(stored(digest, content.length, relative)));
    }

    @Test
    void 流式来源必须是摘要和长度完全匹配的普通文件() throws Exception {
        byte[] content = "verified source".getBytes(StandardCharsets.UTF_8);
        String digest = ManagedWorktreePolicy.sha256(content);
        Path source = temporaryDirectory.resolve("source.bin");
        Files.write(source, content);

        assertThrows(PersistenceException.class, () -> blobs.writeVerified(digest, source, content.length + 1L));
        assertThrows(
                PersistenceException.class,
                () -> blobs.writeVerified(ManagedWorktreePolicy.sha256(new byte[] {1}), source, content.length));
        assertThrows(
                PersistenceException.class,
                () -> blobs.writeVerified(digest, temporaryDirectory.resolve("missing.bin"), content.length));
        assertThrows(PersistenceException.class, () -> blobs.writeVerified(digest, temporaryDirectory, content.length));
    }

    @Test
    void Blob路径拒绝绝对路径上跳目录符号链接和受管根逃逸() throws Exception {
        byte[] content = "contained".getBytes(StandardCharsets.UTF_8);
        String digest = ManagedWorktreePolicy.sha256(content);
        AttachmentMetadata metadata = metadata(digest, content.length);

        assertThrows(
                PersistenceException.class,
                () -> blobs.read(new AttachmentRepository.StoredAttachment(metadata, "/tmp/outside.blob")));
        assertThrows(
                PersistenceException.class,
                () -> blobs.read(new AttachmentRepository.StoredAttachment(metadata, "../outside.blob")));
        assertThrows(
                PersistenceException.class, () -> blobs.read(new AttachmentRepository.StoredAttachment(metadata, ".")));

        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        try {
            Path source = Files.write(outside.resolve("source.blob"), content);
            Path link = dataRoot.resolve("linked.blob");
            Files.createSymbolicLink(link, source);
            assertThrows(
                    PersistenceException.class,
                    () -> blobs.read(new AttachmentRepository.StoredAttachment(metadata, "linked.blob")));

            Files.createSymbolicLink(dataRoot.resolve("blobs"), outside);
            assertThrows(PersistenceException.class, () -> blobs.write(digest, content));
        } catch (UnsupportedOperationException | IOException ignored) {
            // 不支持符号链接或未授予创建权限的平台仍由普通文件和规范路径检查覆盖。
        }
    }

    @Test
    void 不存在或不是目录的数据根不会产生非受管Blob() throws Exception {
        assertThrows(
                PersistenceException.class,
                () -> new AttachmentBlobStore(temporaryDirectory.resolve("missing-data-v6")));

        Path regularFile = Files.writeString(temporaryDirectory.resolve("data-root-file"), "not a directory");
        AttachmentBlobStore invalid = new AttachmentBlobStore(regularFile);
        byte[] content = "blocked".getBytes(StandardCharsets.UTF_8);
        assertThrows(PersistenceException.class, () -> invalid.write(ManagedWorktreePolicy.sha256(content), content));
    }

    private static AttachmentRepository.StoredAttachment stored(String digest, long size, String relativePath) {
        return new AttachmentRepository.StoredAttachment(metadata(digest, size), relativePath);
    }

    private static AttachmentMetadata metadata(String digest, long size) {
        return new AttachmentMetadata(digest, "application/octet-stream", size, Instant.parse("2026-09-01T00:00:00Z"));
    }
}
