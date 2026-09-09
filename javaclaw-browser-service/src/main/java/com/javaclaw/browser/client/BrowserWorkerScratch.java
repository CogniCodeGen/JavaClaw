package com.javaclaw.browser.client;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;

/** 每次 Browser 启动独占的临时目录；清理通过冻结身份和目录句柄完成，不跟随 Worker 留下的链接。 */
final class BrowserWorkerScratch {
    private final Path parent;
    private final Object parentKey;
    private final Path root;
    private final Object rootKey;

    private BrowserWorkerScratch(Path parent, Object parentKey, Path root, Object rootKey) {
        this.parent = parent;
        this.parentKey = parentKey;
        this.root = root;
        this.rootKey = rootKey;
    }

    static BrowserWorkerScratch create(Path parent, Object expectedParentKey) throws IOException {
        requireIdentity(parent, expectedParentKey);
        Path root = Files.createTempDirectory(parent, "worker-").toRealPath();
        return new BrowserWorkerScratch(parent, expectedParentKey, root, identity(root));
    }

    Path root() {
        return root;
    }

    static Object identity(Path root) throws IOException {
        BasicFileAttributes attributes =
                Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()
                || attributes.isSymbolicLink()
                || !root.toRealPath().equals(root)
                || attributes.fileKey() == null) {
            throw new IOException("Browser Worker scratch requires a canonical ordinary directory identity");
        }
        return attributes.fileKey();
    }

    void cleanup() throws IOException {
        requireIdentity(parent, parentKey);
        try (DirectoryStream<Path> opened = Files.newDirectoryStream(parent)) {
            if (opened instanceof SecureDirectoryStream<Path> secure) {
                requireIdentity(
                        secure.getFileAttributeView(BasicFileAttributeView.class)
                                .readAttributes(),
                        parentKey);
                Path name = root.getFileName();
                BasicFileAttributes attributes = secure.getFileAttributeView(
                                name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        .readAttributes();
                requireIdentity(attributes, rootKey);
                try (SecureDirectoryStream<Path> child = secure.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                    requireIdentity(
                            child.getFileAttributeView(BasicFileAttributeView.class)
                                    .readAttributes(),
                            rootKey);
                    removeChildren(child, 0);
                }
                secure.deleteDirectory(name);
            } else {
                // 无安全目录句柄时只移除空根；不能用路径递归跟随仍可能被子进程替换的目录。
                requireIdentity(root, rootKey);
                Files.delete(root);
            }
        }
    }

    void cleanupAfterExit() {
        try {
            cleanup();
        } catch (IOException | RuntimeException failure) {
            // 不记录路径、profile 内容或异常文本；无法安全清理时保留隔离目录，不影响其他实例。
            System.getLogger(BrowserWorkerScratch.class.getName())
                    .log(System.Logger.Level.WARNING, "Browser Worker 私有临时目录未清理，已保留隔离内容");
        }
    }

    private static void removeChildren(SecureDirectoryStream<Path> directory, int depth) throws IOException {
        if (depth > 128) {
            throw new IOException("Browser Worker scratch directory depth exceeds cleanup limit");
        }
        for (Path entry : directory) {
            Path name = entry.getFileName();
            BasicFileAttributes attributes = directory
                    .getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes();
            if (attributes.isDirectory()) {
                try (SecureDirectoryStream<Path> child =
                        directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                    removeChildren(child, depth + 1);
                }
                directory.deleteDirectory(name);
            } else {
                directory.deleteFile(name);
            }
        }
    }

    private static void requireIdentity(Path root, Object expected) throws IOException {
        if (!expected.equals(identity(root))) {
            throw new IOException("Browser Worker scratch directory identity changed");
        }
    }

    private static void requireIdentity(BasicFileAttributes attributes, Object expected) throws IOException {
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || !expected.equals(attributes.fileKey())) {
            throw new IOException("Browser Worker scratch directory identity changed");
        }
    }
}
