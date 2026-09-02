package com.javaclaw.nativehost.tray;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrayBoundaryContractsTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void 心跳协议拒绝未知版本非正数溢出值和未来时间() throws Exception {
        assertUnavailable("2\n42\n" + NOW.toEpochMilli() + "\n", "版本");
        assertUnavailable("1\n0\n" + NOW.toEpochMilli() + "\n", "无效");
        assertUnavailable("1\n9223372036854775808\n" + NOW.toEpochMilli() + "\n", "无效");
        assertUnavailable("1\n42\n" + NOW.plusMillis(1).toEpochMilli() + "\n", "过期");
    }

    @Test
    void 心跳在最大时限边界仍有效且超过一毫秒立即失效() throws Exception {
        Path presence = temporaryDirectory.resolve("boundary.presence");
        Instant updatedAt = NOW.minus(TrayPresenceProbe.MAXIMUM_AGE);
        Files.writeString(presence, "1\n42\n" + updatedAt.toEpochMilli() + "\n");
        TrayPresenceProbe exact = probe(presence, NOW);

        assertTrue(exact.status().active());
        assertFalse(probe(presence, NOW.plusMillis(1)).status().active());
    }

    @Test
    void 当前用户探针规范化路径且缺失心跳时不查询进程() {
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty(
                    "user.home", temporaryDirectory.resolve("nested/..").toString());
            TrayPresenceStatus status = TrayPresenceProbe.currentUser().status();

            assertFalse(status.active());
            assertEquals("未检测到托盘 supervisor", status.unavailableReason().orElseThrow());
        } finally {
            restore("user.home", originalHome);
        }
    }

    @Test
    void 非法进程号不会遗留独占锁() throws Exception {
        Path presence = temporaryDirectory.resolve("invalid-pid/tray.presence");

        assertThrows(IllegalArgumentException.class, () -> TrayPresenceLease.acquire(presence, Clock.systemUTC(), 0));
        try (TrayPresenceLease ignored = TrayPresenceLease.acquire(presence, Clock.systemUTC(), 42)) {
            assertTrue(Files.isRegularFile(presence));
        }
    }

    @Test
    void Launcher拒绝相对路径软链接和含空字符的配置() throws Exception {
        Path launcher = Files.writeString(temporaryDirectory.resolve("launcher"), "launcher");
        Path symbolicLink = temporaryDirectory.resolve("launcher-link");
        try {
            Files.createSymbolicLink(symbolicLink, launcher);
        } catch (UnsupportedOperationException failure) {
            symbolicLink = temporaryDirectory.resolve("missing-link");
        }

        assertLauncherRejected("relative-launcher", "可信绝对文件");
        assertLauncherRejected(symbolicLink.toString(), "可信绝对文件");
        assertLauncherRejected("bad\0launcher", "配置无效");
    }

    @Test
    void 脱敏状态对象拒绝互相矛盾的字段组合() {
        assertThrows(
                IllegalArgumentException.class, () -> new TrayPresenceStatus(true, Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrayPresenceStatus(false, Optional.of(42L), Optional.of("失败")));
        assertThrows(NullPointerException.class, () -> TrayPresenceStatus.unavailable(null));
        assertThrows(IllegalArgumentException.class, () -> TrayPresenceStatus.unavailable(" "));

        assertThrows(
                IllegalArgumentException.class,
                () -> new SystemTrayFeature.Status(SystemTrayFeature.Platform.MACOS, true, Optional.of("失败")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LauncherSupervisorProbe.Status(false, true, false, Optional.of("失败")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LauncherSupervisorProbe.Status(true, true, false, Optional.of("失败")));
    }

    private void assertUnavailable(String content, String reasonFragment) throws IOException {
        Path presence = temporaryDirectory.resolve("invalid-" + Integer.toUnsignedString(content.hashCode()));
        Files.writeString(presence, content);

        TrayPresenceStatus status = probe(presence, NOW).status();

        assertFalse(status.active());
        assertTrue(status.unavailableReason().orElseThrow().contains(reasonFragment));
    }

    private void assertLauncherRejected(String configured, String reasonFragment) {
        TrayPresenceProbe tray = new TrayPresenceProbe(
                temporaryDirectory.resolve("missing.presence"), Clock.fixed(NOW, ZoneOffset.UTC), ignored -> true);

        LauncherSupervisorProbe.Status status = new LauncherSupervisorProbe(configured, tray).status();

        assertFalse(status.launcherConfigured());
        assertTrue(status.unavailableReason().orElseThrow().contains(reasonFragment));
    }

    private static TrayPresenceProbe probe(Path presence, Instant now) {
        return new TrayPresenceProbe(presence, Clock.fixed(now, ZoneOffset.UTC), processId -> processId == 42);
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
