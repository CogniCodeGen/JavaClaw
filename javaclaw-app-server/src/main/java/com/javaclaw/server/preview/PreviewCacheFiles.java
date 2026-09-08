package com.javaclaw.server.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Comparator;
import java.util.UUID;

/** 专用缓存目录的安全创建与回收，不跟随链接或清理其他领域文件。 */
final class PreviewCacheFiles {
    private PreviewCacheFiles() {}

    static Path instance(Path dataRoot) throws IOException {
        Path root = dataRoot.toRealPath();
        if (!root.getFileName().toString().equals("data-v6")) {
            throw new IOException("预览缓存必须位于 data-v6");
        }
        UserPrincipal owner = Files.getOwner(root, LinkOption.NOFOLLOW_LINKS);
        Path cache = directory(root.resolve("cache"), owner);
        Path previews = directory(cache.resolve("document-preview"), owner);
        try (var entries = Files.newDirectoryStream(previews)) {
            for (Path previous : entries) {
                if (previous.getFileName().toString().matches("[a-f0-9-]{36}")) {
                    delete(previous, owner);
                }
            }
        }
        return directory(previews.resolve(UUID.randomUUID().toString()), owner);
    }

    static Path directory(Path path, UserPrincipal owner) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createDirectory(
                        path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                // data-v6 的受限 ACL 由目录继承；此处不增加任何主体的权限。
                Files.createDirectory(path);
            }
        }
        requireOwned(path, owner);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("预览缓存必须是普通目录");
        }
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")
                && !PosixFilePermissions.fromString("rwx------")
                        .containsAll(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("预览缓存目录权限过宽");
        }
        return path;
    }

    static void delete(Path directory, UserPrincipal owner) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        requireOwned(directory, owner);
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                requireOwned(path, owner);
                Files.delete(path);
            }
        }
    }

    private static void requireOwned(Path path, UserPrincipal owner) throws IOException {
        if (Files.isSymbolicLink(path)
                || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(owner)) {
            throw new IOException("预览缓存所有权或链接边界不满足要求");
        }
    }
}
