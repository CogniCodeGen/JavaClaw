package com.javaclaw.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PathGuard} 路径穿越/符号链接防护回归测试 */
class PathGuardTest {

    @Test
    void 目录内正常文件放行(@TempDir Path dir) throws Exception {
        Path base = Files.createDirectories(dir.resolve("base"));
        Path file = Files.writeString(base.resolve("a.txt"), "x");
        assertTrue(PathGuard.isInside(base, file));
    }

    @Test
    void 点点穿越拒绝(@TempDir Path dir) throws Exception {
        Path base = Files.createDirectories(dir.resolve("base"));
        Path outside = Files.writeString(dir.resolve("secret.txt"), "x");
        assertFalse(PathGuard.isInside(base, base.resolve("../secret.txt")));
        assertFalse(PathGuard.isInside(base, outside));
    }

    @Test
    void 尚不存在的目标按最近祖先判定(@TempDir Path dir) throws Exception {
        Path base = Files.createDirectories(dir.resolve("base"));
        assertTrue(PathGuard.isInside(base, base.resolve("new/sub/file.txt")), "写入场景：目标不存在但落在 base 内应放行");
        assertFalse(PathGuard.isInside(base, dir.resolve("elsewhere/file.txt")), "目标不存在且在 base 外应拒绝");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void 符号链接逃逸拒绝(@TempDir Path dir) throws Exception {
        Path base = Files.createDirectories(dir.resolve("base"));
        Path outside = Files.writeString(dir.resolve("outside.txt"), "secret");
        Path link = base.resolve("evil.txt");
        Files.createSymbolicLink(link, outside);
        assertFalse(PathGuard.isInside(base, link), "指向目录外的符号链接必须拒绝");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void 目录内符号链接指向目录内放行(@TempDir Path dir) throws Exception {
        Path base = Files.createDirectories(dir.resolve("base"));
        Path real = Files.writeString(base.resolve("real.txt"), "x");
        Path link = base.resolve("alias.txt");
        Files.createSymbolicLink(link, real);
        assertTrue(PathGuard.isInside(base, link), "目录内互指的符号链接应放行");
    }

    @Test
    void base不存在时拒绝(@TempDir Path dir) {
        assertFalse(PathGuard.isInside(dir.resolve("nope"), dir.resolve("nope/file")));
    }
}
