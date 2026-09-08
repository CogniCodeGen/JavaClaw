package com.javaclaw.server.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PreviewCacheFilesTest {
    @TempDir
    Path temporary;

    @Test
    void 启动只回收旧实例而保留其他目录并拒绝错误数据版本() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("data-v6"));
        Path first = PreviewCacheFiles.instance(root);
        Files.writeString(first.resolve("content"), "stale");
        Path unrelated = Files.createDirectory(first.getParent().resolve("reserved"));
        Path next = PreviewCacheFiles.instance(root);
        assertFalse(Files.exists(first));
        assertTrue(Files.isDirectory(next));
        assertTrue(Files.isDirectory(unrelated));
        assertThrows(
                IOException.class,
                () -> PreviewCacheFiles.instance(Files.createDirectory(temporary.resolve("data-v5"))));
    }

    @Test
    void 缓存目录拒绝符号链接且回收不会遍历外部目标() throws Exception {
        assumeTrue(temporary.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path root = Files.createDirectory(temporary.resolve("data-v6"));
        Path instance = PreviewCacheFiles.instance(root);
        var owner = Files.getOwner(root, LinkOption.NOFOLLOW_LINKS);
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("keep"), "protected");
        Path link = Files.createSymbolicLink(instance.resolve(UUID.randomUUID().toString()), outside);
        assertThrows(IOException.class, () -> PreviewCacheFiles.directory(link, owner));
        assertThrows(IOException.class, () -> PreviewCacheFiles.delete(instance, owner));
        assertEquals("protected", Files.readString(sentinel));
        Files.delete(link);
        PreviewCacheFiles.delete(instance, owner);
        PreviewCacheFiles.delete(instance, owner);
        assertFalse(Files.exists(instance));
    }

    @Test
    void 拒绝将普通文件或对其他用户开放的目录作为缓存() throws Exception {
        assumeTrue(temporary.getFileSystem().supportedFileAttributeViews().contains("posix"));
        var owner = Files.getOwner(temporary, LinkOption.NOFOLLOW_LINKS);
        Path file = Files.writeString(temporary.resolve("file"), "content");
        assertThrows(IOException.class, () -> PreviewCacheFiles.directory(file, owner));
        Path broad = Files.createDirectory(temporary.resolve("broad"));
        Files.setPosixFilePermissions(broad, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IOException.class, () -> PreviewCacheFiles.directory(broad, owner));
    }
}
