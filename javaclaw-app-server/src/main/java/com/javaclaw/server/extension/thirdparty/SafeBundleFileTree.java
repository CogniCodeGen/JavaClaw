package com.javaclaw.server.extension.thirdparty;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** 只删除受管 staging 根的直接子树，遍历时不跟随符号链接。 */
final class SafeBundleFileTree {
    private SafeBundleFileTree() {}

    static void delete(Path stagingRoot, Path target) throws IOException {
        Path root = stagingRoot.toRealPath();
        Path normalized = target.toAbsolutePath().normalize();
        if (!root.equals(normalized.getParent())) {
            throw new SecurityException("refusing to delete outside staging root");
        }
        if (!Files.exists(normalized, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
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
}
