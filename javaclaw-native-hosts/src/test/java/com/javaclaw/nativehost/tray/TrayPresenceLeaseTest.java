package com.javaclaw.nativehost.tray;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrayPresenceLeaseTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void 独占Lease写入新鲜心跳且关闭不触碰Server() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        Path presence = temporaryDirectory.resolve("run/tray-v5.presence");
        TrayPresenceProbe probe = new TrayPresenceProbe(presence, clock, processId -> processId == 42);

        try (TrayPresenceLease ignored = TrayPresenceLease.acquire(presence, clock, 42)) {
            TrayPresenceStatus active = probe.status();
            assertTrue(active.active());
            assertEquals(42L, active.processId().orElseThrow());
            assertThrows(IOException.class, () -> TrayPresenceLease.acquire(presence, clock, 43));

            clock.advance(TrayPresenceProbe.MAXIMUM_AGE.plusMillis(1));
            assertFalse(probe.status().active());
        }

        assertFalse(probe.status().active());
    }

    @Test
    void 损坏心跳和已退出进程均FailClosed() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        Path presence = temporaryDirectory.resolve("tray-v5.presence");
        java.nio.file.Files.writeString(presence, "broken");
        assertFalse(
                new TrayPresenceProbe(presence, clock, ignored -> true).status().active());
        java.nio.file.Files.writeString(presence, "x".repeat(129));
        assertFalse(
                new TrayPresenceProbe(presence, clock, ignored -> true).status().active());

        try (TrayPresenceLease ignored = TrayPresenceLease.acquire(presence, clock, 99)) {
            TrayPresenceStatus status = new TrayPresenceProbe(presence, clock, processId -> false).status();
            assertFalse(status.active());
            assertTrue(status.unavailableReason().orElseThrow().contains("退出"));
        }
    }

    @Test
    void 关闭Lease是幂等操作() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        Path presence = temporaryDirectory.resolve("idempotent/tray-v5.presence");
        TrayPresenceLease lease = TrayPresenceLease.acquire(presence, clock, 42);

        lease.close();
        lease.close();

        assertFalse(java.nio.file.Files.exists(presence));
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
