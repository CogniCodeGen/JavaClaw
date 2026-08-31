package com.javaclaw.server.browser;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** 只回收本次创建、且对应进程已退出的 Browser 临时目录；绝不扫描用户工作区或旧数据目录。 */
final class BrowserSessionFiles {
    private BrowserSessionFiles() {}

    static void remove(Path parent, Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!root.getParent().equals(parent.toRealPath())
                || !root.getFileName().toString().matches("session-[0-9a-f-]{36}")) {
            throw new IOException("browser cleanup target is not an owned session directory");
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        // 不跟随浏览器创建的链接；根本身被换成链接时也只删除链接，不能清理其目标。
        Files.walkFileTree(root, java.util.Set.of(), 64, new SimpleFileVisitor<>() {
            private int visited;

            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                if (++visited > 50_000 || !path.toRealPath().startsWith(root)) {
                    throw new IOException("browser cleanup exceeded its owned boundary");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                if (++visited > 50_000) {
                    throw new IOException("browser cleanup exceeded entry limit");
                }
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
