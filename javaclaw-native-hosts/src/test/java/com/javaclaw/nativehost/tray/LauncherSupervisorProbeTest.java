package com.javaclaw.nativehost.tray;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherSupervisorProbeTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void idea运行没有发行Launcher时明确FailClosed() {
        LauncherSupervisorProbe.Status status = new LauncherSupervisorProbe("", missingTray()).status();

        assertFalse(status.launcherConfigured());
        assertFalse(status.serverControlAvailable());
        assertEquals(
                "IDEA 调试未配置 launcher supervisor", status.unavailableReason().orElseThrow());
    }

    @Test
    void 发行Launcher存在但托盘心跳缺失时不伪造可控制() throws Exception {
        Path launcher = Files.writeString(temporaryDirectory.resolve("javaclaw-service"), "launcher");

        LauncherSupervisorProbe.Status status =
                new LauncherSupervisorProbe(launcher.toString(), missingTray()).status();

        assertTrue(status.launcherConfigured());
        assertFalse(status.trayActive());
        assertFalse(status.serverControlAvailable());
        assertEquals("未检测到托盘 supervisor", status.unavailableReason().orElseThrow());
    }

    @Test
    void 仅可信Launcher与存活新鲜心跳同时存在时开放控制() throws Exception {
        Path launcher = Files.writeString(temporaryDirectory.resolve("javaclaw-service"), "launcher");
        Path presence = temporaryDirectory.resolve("tray-v6.presence");
        Files.writeString(presence, "1\n42\n" + NOW.toEpochMilli() + "\n");
        TrayPresenceProbe tray =
                new TrayPresenceProbe(presence, Clock.fixed(NOW, ZoneOffset.UTC), processId -> processId == 42);

        LauncherSupervisorProbe.Status status = new LauncherSupervisorProbe(launcher.toString(), tray).status();

        assertTrue(status.launcherConfigured());
        assertTrue(status.trayActive());
        assertTrue(status.serverControlAvailable());
        assertTrue(status.unavailableReason().isEmpty());
    }

    private TrayPresenceProbe missingTray() {
        return new TrayPresenceProbe(
                temporaryDirectory.resolve("missing.presence"), Clock.fixed(NOW, ZoneOffset.UTC), ignored -> true);
    }
}
