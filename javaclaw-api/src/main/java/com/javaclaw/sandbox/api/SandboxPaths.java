package com.javaclaw.sandbox.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Objects;

/** Canonicalizes existing path ancestors so policy comparisons cannot be fooled by symlinks. */
public final class SandboxPaths {
    private SandboxPaths() {}

    /**
     * 解析现存祖先目录的真实路径，再拼接尚不存在的后缀；避免符号链接伪装绕过路径求交。
     *
     * @param value 非空路径，允许最终文件尚不存在
     * @return 绝对、规范化且解析现存链接后的路径
     * @throws IllegalArgumentException 无法安全解析现存祖先
     */
    public static Path canonicalize(Path value) {
        Path absolute = Objects.requireNonNull(value, "path").toAbsolutePath().normalize();
        Path existing = absolute;
        ArrayDeque<String> missing = new ArrayDeque<>();
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path name = existing.getFileName();
            if (name != null) {
                missing.addFirst(name.toString());
            }
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IllegalArgumentException("path has no existing ancestor: " + absolute);
        }
        try {
            Path resolved = existing.toRealPath();
            for (String element : missing) {
                resolved = resolved.resolve(element);
            }
            return resolved.normalize();
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot canonicalize path: " + absolute, failure);
        }
    }
}
