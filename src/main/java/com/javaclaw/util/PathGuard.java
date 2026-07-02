package com.javaclaw.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 符号链接安全的目录包含性检查。
 *
 * <p>仅用 {@code normalize()+startsWith()} 判断包含关系无法防符号链接逃逸：
 * 目录内一个指向外部的 symlink 在字面路径上仍"位于"目录内，但读写会落到外部真实位置。
 * 本工具把基准目录与目标都解析为真实路径（{@code toRealPath()}，跟随符号链接）后再判断，
 * symlink 指向目录外时即拒绝。</p>
 */
public final class PathGuard {

    private PathGuard() {
    }

    /**
     * 判断 target 的真实位置是否落在 base 目录内（含符号链接解析）。
     *
     * @param base   基准目录（必须已存在）
     * @param target 目标路径（可以尚不存在，如写入场景——按最近的已存在祖先解析）
     * @return 真实位置在 base 内返回 true；越界、base 不存在或解析失败一律返回 false（安全默认拒绝）
     */
    public static boolean isInside(Path base, Path target) {
        try {
            Path realBase = base.toRealPath();
            Path realTarget = realOfNearestExisting(target.toAbsolutePath().normalize());
            return realTarget.startsWith(realBase);
        } catch (IOException e) {
            return false;
        }
    }

    /** 目标可能尚不存在：向上找最近的已存在祖先解析真实路径，再拼回剩余路径段。 */
    private static Path realOfNearestExisting(Path p) throws IOException {
        Path existing = p;
        Deque<String> tail = new ArrayDeque<>();
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path name = existing.getFileName();
            if (name == null) throw new IOException("无法解析路径: " + p);
            tail.push(name.toString());
            existing = existing.getParent();
        }
        if (existing == null) throw new IOException("无已存在祖先: " + p);
        Path real = existing.toRealPath();
        for (String seg : tail) {
            real = real.resolve(seg);
        }
        return real.normalize();
    }
}
