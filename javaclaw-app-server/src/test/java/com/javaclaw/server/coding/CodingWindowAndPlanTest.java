package com.javaclaw.server.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.TurnId;
import com.javaclaw.builtin.contracts.CodingContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingWindowAndPlanTest {
    @Test
    void 中文和Emoji分页不能产生伪二进制或丢失末尾半个字符() {
        byte[] text = "中🙂文".getBytes(StandardCharsets.UTF_8);
        var first = CodingUtf8Window.decode(java.util.Arrays.copyOfRange(text, 0, 5), 0, false);
        assertFalse(first.binary());
        assertEquals("中", first.text());
        assertEquals(3, first.consumed());
        var second =
                CodingUtf8Window.decode(java.util.Arrays.copyOfRange(text, first.consumed(), text.length), 3, true);
        assertEquals("🙂文", second.text());
        assertThrows(IllegalArgumentException.class, () -> CodingUtf8Window.decode(new byte[] {(byte) 0xe4}, 0, false));
        assertTrue(CodingUtf8Window.decode(new byte[] {0}, 0, true).binary());
        assertTrue(CodingUtf8Window.decode(new byte[] {(byte) 0xff}, 0, true).binary());
    }

    @Test
    void 精确根锁与Turn槽独立且重复关闭不能释放新拥有者() {
        var locks = new CodingExecutionLocks();
        var turn = TurnId.random();
        Path root = Path.of("coding-lock-root").toAbsolutePath();
        var first = locks.acquire(turn, root);
        assertThrows(IllegalStateException.class, () -> locks.acquire(turn, root.resolve("other")));
        assertThrows(IllegalStateException.class, () -> locks.acquire(TurnId.random(), root));
        first.close();
        try (var next = locks.acquire(turn, root)) {
            first.close();
            assertThrows(IllegalStateException.class, () -> locks.acquire(root));
        }
        try (var reused = locks.acquire(turn, root)) {
            assertThrows(IllegalStateException.class, () -> locks.acquire(root));
        }
    }

    @Test
    void 原生准备计划尊重锁文件及明确脚本策略() {
        var npm = request(CodingContracts.PackageManager.NPM);
        assertEquals("install", plan(npm, Map.of(), true).get(1));
        assertEquals(
                "ci",
                plan(npm, Map.of("sub/package-lock.json", Optional.of("digest")), false)
                        .get(1));
        assertTrue(plan(npm, Map.of(), false).contains("--ignore-scripts"));
        var pnpm = request(CodingContracts.PackageManager.PNPM);
        assertTrue(plan(pnpm, Map.of("pnpm-lock.yaml", Optional.of("digest")), true)
                .contains("--frozen-lockfile"));
        assertTrue(plan(pnpm, Map.of(), false).contains("--ignore-scripts"));
        assertEquals(
                List.of("gradle", "dependencies"),
                plan(request(CodingContracts.PackageManager.GRADLE), Map.of(), true));
        assertTrue(plan(request(CodingContracts.PackageManager.MAVEN), Map.of(), true)
                .contains("dependency:go-offline"));
        for (var manager : List.of(
                CodingContracts.PackageManager.MAVEN,
                CodingContracts.PackageManager.GRADLE,
                CodingContracts.PackageManager.PIP)) {
            assertThrows(SecurityException.class, () -> plan(request(manager), Map.of(), false));
        }
        assertTrue(plan(
                        request(CodingContracts.PackageManager.PIP),
                        Map.of("requirements.txt", Optional.of("digest")),
                        true)
                .contains("requirements.txt"));
    }

    private static CodingContracts.DependenciesPrepare request(CodingContracts.PackageManager manager) {
        return new CodingContracts.DependenciesPrepare(manager, ".", List.of());
    }

    private static List<String> plan(
            CodingContracts.DependenciesPrepare input, Map<String, Optional<String>> manifests, boolean scripts) {
        return NativeDependencyPlan.argv(
                input, manifests, scripts, Path.of("cache"), Path.of("settings.xml"), "http://127.0.0.1:41234");
    }
}
