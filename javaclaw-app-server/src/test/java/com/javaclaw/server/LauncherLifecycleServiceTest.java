package com.javaclaw.server;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.nativehost.tray.LauncherSupervisorProbe;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.DiagnosticsRpcContracts;
import com.javaclaw.server.lifecycle.LauncherLifecycleService;
import com.javaclaw.server.lifecycle.LifecycleCoordinator;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.LifecycleLeaseRepository;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherLifecycleServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void idea运行只返回明确不可用且拒绝停止() {
        try (Fixture fixture = fixture(unavailable())) {
            assertEquals(
                    "IDEA 调试未配置 launcher supervisor",
                    fixture.service().readStatus().unavailableReason().orElseThrow());

            DiagnosticsRpcContracts.ServerStopResult result = fixture.service().stop(identity("idea-stop", 0));

            assertFalse(result.accepted());
            assertEquals("IDEA 调试未配置 launcher supervisor", result.reason().orElseThrow());
            assertFalse(fixture.lifecycle().shutdownRequested());
        }
    }

    @Test
    void 托盘控制连接以外的客户端与活动Lease都会阻止停止() {
        try (Fixture fixture = fixture(available());
                LifecycleCoordinator.Lease control = fixture.lifecycle().clientConnected();
                LifecycleCoordinator.Lease desktop = fixture.lifecycle().clientConnected();
                LifecycleCoordinator.Lease schedule =
                        fixture.lifecycle().acquireActivity("schedule:one", "SCHEDULE", Duration.ofMinutes(1))) {
            DiagnosticsRpcContracts.ServerStopResult result = fixture.service().stop(identity("busy-stop", 0));

            assertFalse(result.accepted());
            assertEquals(2, result.connectedClients());
            assertEquals(1, result.activeLeases());
            assertEquals("存在活动 Turn、交互或 Schedule lease", result.reason().orElseThrow());
        }
    }

    @Test
    void 唯一托盘控制连接可安排响应后的协作式退出() throws Exception {
        try (Fixture fixture = fixture(available());
                LifecycleCoordinator.Lease ignored = fixture.lifecycle().clientConnected()) {
            DiagnosticsRpcContracts.ServerStopResult result = fixture.service().stop(identity("safe-stop", 0));

            assertTrue(result.accepted());
            assertEquals(1, result.connectedClients());
            assertTrue(awaitShutdown(fixture.lifecycle()));
        }
    }

    @Test
    void 相同幂等键不会在环境变化后重放旧停止意图() {
        try (Fixture fixture = fixture(available());
                LifecycleCoordinator.Lease control = fixture.lifecycle().clientConnected()) {
            LifecycleCoordinator.Lease desktop = fixture.lifecycle().clientConnected();
            CommandIdentity identity = identity("retry-stop", 0);
            DiagnosticsRpcContracts.ServerStopResult rejected =
                    fixture.service().stop(identity);
            desktop.close();

            DiagnosticsRpcContracts.ServerStopResult replayed =
                    fixture.service().stop(identity);

            assertEquals(rejected, replayed);
            assertFalse(replayed.accepted());
            assertFalse(fixture.lifecycle().shutdownRequested());
        }
    }

    @Test
    void 停止命令只接受零Revision() {
        try (Fixture fixture = fixture(available())) {
            assertThrows(PersistenceException.class, () -> fixture.service().stop(identity("bad-revision", 1)));
        }
    }

    private Fixture fixture(LauncherSupervisorProbe.Status supervisor) {
        Path fixtureRoot = temporaryDirectory.resolve("fixture-" + System.nanoTime());
        H2Database database = new H2Database(fixtureRoot.resolve("data-v5"));
        database.initialize();
        LifecycleCoordinator lifecycle = new LifecycleCoordinator(
                new LifecycleLeaseRepository(database, Clock.systemUTC()), Duration.ofSeconds(5));
        LauncherLifecycleService service = new LauncherLifecycleService(
                database, lifecycle, () -> supervisor, new CanonicalJson(), Clock.systemUTC());
        return new Fixture(lifecycle, service);
    }

    private static LauncherSupervisorProbe.Status available() {
        return new LauncherSupervisorProbe.Status(true, true, true, Optional.empty());
    }

    private static LauncherSupervisorProbe.Status unavailable() {
        return new LauncherSupervisorProbe.Status(false, false, false, Optional.of("IDEA 调试未配置 launcher supervisor"));
    }

    private static CommandIdentity identity(String key, long revision) {
        return new CommandIdentity("diagnostics/server/stop", key, revision, "a".repeat(64));
    }

    private static boolean awaitShutdown(LifecycleCoordinator lifecycle) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!lifecycle.shutdownRequested() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return lifecycle.shutdownRequested();
    }

    private record Fixture(LifecycleCoordinator lifecycle, LauncherLifecycleService service) implements AutoCloseable {
        @Override
        public void close() {
            lifecycle.close();
        }
    }
}
