package com.javaclaw.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LegacyTestDataManagerTest {
    @TempDir Path temp;

    @Test
    void 只扫描JUnit目录且必须显式调用才删除() throws Exception {
        Path legacy = Files.createDirectories(temp.resolve("junit-123").resolve("data"));
        Files.writeString(legacy.resolve("javaclaw.mv.db"), "test");
        Path foreign = Files.createDirectories(temp.resolve("junit-other").resolve("data"));
        Files.writeString(foreign.resolve("other.mv.db"), "other");
        Path normal = Files.createDirectories(temp.resolve("production-data"));

        var candidates = LegacyTestDataManager.scan(List.of(temp));

        assertEquals(1, candidates.size());
        assertTrue(Files.exists(legacy));
        assertTrue(candidates.getFirst().bytes() > 0);

        assertEquals(1, LegacyTestDataManager.deleteConfirmed(candidates));
        assertFalse(Files.exists(temp.resolve("junit-123")));
        assertTrue(Files.exists(foreign));
        assertTrue(Files.exists(normal));
    }

    @Test
    void 拒绝清理根目录外目标() {
        var forged = new LegacyTestDataManager.Candidate(
                temp, temp.resolve("nested").resolve("junit-forged"), 0);
        assertThrows(SecurityException.class,
                () -> LegacyTestDataManager.deleteConfirmed(List.of(forged)));
    }

    @Test
    void 删除前标记消失则拒绝清理() throws Exception {
        Path data = Files.createDirectories(temp.resolve("junit-stale").resolve("data"));
        Path marker = Files.writeString(data.resolve("javaclaw.mv.db"), "test");
        var candidate = LegacyTestDataManager.scan(List.of(temp)).getFirst();

        Files.delete(marker);

        assertThrows(SecurityException.class,
                () -> LegacyTestDataManager.deleteConfirmed(List.of(candidate)));
        assertTrue(Files.exists(temp.resolve("junit-stale")));
    }

    @Test
    void 不跟随伪装成数据库标记的符号链接() throws Exception {
        Path external = Files.writeString(temp.resolve("external.db"), "test");
        Path data = Files.createDirectories(temp.resolve("junit-linked").resolve("data"));
        try {
            Files.createSymbolicLink(data.resolve("javaclaw.mv.db"), external);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "当前文件系统不支持符号链接");
        }

        assertTrue(LegacyTestDataManager.scan(List.of(temp)).isEmpty());
    }
}
