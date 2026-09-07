package com.javaclaw.server;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.DiagnosticsSnapshot;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.LoginStartupPort;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.DiagnosticsRpcContracts;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.diagnostics.DiagnosticsService;
import com.javaclaw.server.extension.BuiltinIsolatedServices;
import com.javaclaw.server.lifecycle.LifecycleCoordinator;
import com.javaclaw.server.lifecycle.ScheduleLifecycleCoordinator;
import com.javaclaw.server.mcp.McpBuiltinExtensionDescriptor;
import com.javaclaw.server.mcp.McpPlatformFactory;
import com.javaclaw.server.mcp.McpRuntimePorts;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.ExtensionJobService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.LifecycleLeaseRepository;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.ProviderService;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;
import com.javaclaw.server.security.vault.SecretVaultService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerInfrastructureTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void startupResourcesCloseInReverseOrderAndCanTransferOwnership() {
        List<String> closed = new ArrayList<>();
        StartupCloseStack stack = new StartupCloseStack();
        AutoCloseable first = stack.own(() -> closed.add("first"));
        AutoCloseable second = stack.own(() -> closed.add("second"));
        stack.release(first);
        stack.release(() -> {});

        stack.close();
        assertEquals(List.of("second"), closed);

        StartupCloseStack released = new StartupCloseStack();
        released.own(first);
        released.own(second);
        released.releaseAll();
        released.close();
        assertEquals(List.of("second"), closed);
        assertThrows(NullPointerException.class, () -> new StartupCloseStack().own(null));
    }

    @Test
    void startupCleanupPreservesFirstFailureAndSuppressesLaterOnes() {
        StartupCloseStack stack = new StartupCloseStack();
        stack.own(() -> {
            throw new Exception("first-owned");
        });
        stack.own(() -> {
            throw new Exception("last-owned");
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class, stack::close);

        assertEquals("last-owned", failure.getCause().getMessage());
        assertEquals(1, failure.getCause().getSuppressed().length);
        assertEquals("first-owned", failure.getCause().getSuppressed()[0].getMessage());
    }

    @Test
    void diagnosticsContainOnlyCountsBuildIdentityAndRuntimeHealth() {
        Instant started = Instant.parse("2026-09-01T00:00:00Z");
        Clock clock = Clock.fixed(started.plusSeconds(10), ZoneOffset.UTC);
        H2Database database = database();
        CanonicalJson json = new CanonicalJson();
        CoreCommandService core = new CoreCommandService(database, json, clock);
        try (LifecycleCoordinator lifecycle =
                        new LifecycleCoordinator(new LifecycleLeaseRepository(database, clock), Duration.ofSeconds(1));
                SecretVaultService vault =
                        new SecretVaultService(database, new MemoryProtector(), json, clock, new SecureRandom());
                ScheduleLifecycleCoordinator schedule = new ScheduleLifecycleCoordinator(lifecycle, required -> {})) {
            LifecycleCoordinator.Lease client = lifecycle.clientConnected();
            DiagnosticsService diagnostics = new DiagnosticsService(
                    diagnosticSources(database, core, lifecycle, vault, schedule, json, clock), json, clock, started);

            DiagnosticsSnapshot snapshot = diagnostics.read();

            assertEquals("6.0.0-SNAPSHOT", snapshot.build().applicationVersion());
            assertEquals(0, snapshot.health().workspaceCount());
            assertEquals(9, snapshot.health().extensionCount());
            assertEquals(1, snapshot.health().connectedClients());
            assertTrue(snapshot.health().databaseHealthy());
            assertEquals(0, snapshot.subsystems().providers().configuredProviders());
            assertEquals(6, snapshot.subsystems().extensions().enabled());
            assertEquals(1, snapshot.subsystems().extensions().disabled());
            assertEquals(1, snapshot.subsystems().extensions().quarantined());
            assertEquals(2, snapshot.subsystems().extensions().thirdParty());
            assertFalse(snapshot.subsystems().launcher().serverControlAvailable());
            assertEquals(0, snapshot.subsystems().jobs().running());
            assertTrue(snapshot.subsystems().schedule().repairAvailable());
            assertEquals(started.plusSeconds(10), snapshot.observedAt());
            client.close();
        }
    }

    @Test
    void 登录启动项修复具备持久幂等结果且反映Schedule权威状态() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        H2Database database = database();
        CanonicalJson json = new CanonicalJson();
        CoreCommandService core = new CoreCommandService(database, json, clock);
        RecordingLoginStartup startup = new RecordingLoginStartup();
        try (LifecycleCoordinator lifecycle =
                        new LifecycleCoordinator(new LifecycleLeaseRepository(database, clock), Duration.ofSeconds(1));
                SecretVaultService vault =
                        new SecretVaultService(database, new MemoryProtector(), json, clock, new SecureRandom());
                ScheduleLifecycleCoordinator schedule = new ScheduleLifecycleCoordinator(lifecycle, startup)) {
            schedule.synchronize(WorkspaceId.random(), true);
            startup.installed = false;
            DiagnosticsService diagnostics = new DiagnosticsService(
                    diagnosticSources(database, core, lifecycle, vault, schedule, json, clock), json, clock, now);
            WriteCommand command = new WriteCommand(
                    "repair-login-startup", 0, json.encode(new DiagnosticsRpcContracts.LoginStartupRepairPayload()));
            CommandIdentity identity = CommandIdentity.from("diagnostics/loginStartup/repair", command, json);

            DiagnosticsSnapshot repaired = diagnostics.repairLoginStartup(identity);
            DiagnosticsSnapshot replayed = diagnostics.repairLoginStartup(identity);

            assertEquals(repaired, replayed);
            assertTrue(repaired.subsystems().schedule().loginStartupInstalled());
            assertEquals(2, startup.writes);
            assertThrows(
                    PersistenceException.class,
                    () -> diagnostics.repairLoginStartup(new CommandIdentity(
                            "diagnostics/loginStartup/repair",
                            "stale-login-startup",
                            1,
                            json.encode(Map.of()).sha256())));
        }
    }

    @Test
    void diagnosticsAndDataRootValidateConfiguration() {
        H2Database database = database();
        Clock clock = Clock.systemUTC();
        CoreCommandService core = new CoreCommandService(database, new CanonicalJson(), clock);
        try (LifecycleCoordinator lifecycle =
                new LifecycleCoordinator(new LifecycleLeaseRepository(database, clock), Duration.ofSeconds(1))) {
            assertThrows(
                    NullPointerException.class,
                    () -> new DiagnosticsService(null, new CanonicalJson(), clock, clock.instant()));
            assertThrows(NullPointerException.class, () -> new DiagnosticsService.CoreSources(null, core, lifecycle));
            assertThrows(
                    NullPointerException.class, () -> new DiagnosticsService.CoreSources(database, null, lifecycle));
            assertThrows(NullPointerException.class, () -> new DiagnosticsService.CoreSources(database, core, null));
            assertThrows(
                    NullPointerException.class,
                    () -> new DiagnosticsService.RuntimeSources(
                            null,
                            new BuiltinIsolatedServices.Availability(false, false, false),
                            new ScheduleLifecycleCoordinator(lifecycle, required -> {}),
                            ServerInfrastructureTest::unavailableLauncher));
        }

        String prior = System.getProperty("javaclaw.data.root");
        String priorProgram = System.getProperty("javaclaw.program.dir");
        try {
            System.setProperty(
                    "javaclaw.data.root",
                    temporaryDirectory.resolve("custom-data-v6").toString());
            assertEquals(
                    temporaryDirectory
                            .resolve("custom-data-v6")
                            .toAbsolutePath()
                            .normalize(),
                    AppServerMain.dataRoot());
            System.setProperty("javaclaw.data.root", "   ");
            Path program =
                    temporaryDirectory.resolve("program").toAbsolutePath().normalize();
            System.setProperty("javaclaw.program.dir", program.toString());
            assertEquals(program.resolve("data-v6"), AppServerMain.dataRoot());
            assertFalse(AppServerMain.dataRoot().toString().isBlank());
        } finally {
            if (priorProgram == null) {
                System.clearProperty("javaclaw.program.dir");
            } else {
                System.setProperty("javaclaw.program.dir", priorProgram);
            }
            if (prior == null) {
                System.clearProperty("javaclaw.data.root");
            } else {
                System.setProperty("javaclaw.data.root", prior);
            }
        }
    }

    @Test
    void 配置化Bootstrap从DataV6构造并关闭完整运行时() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-09-02T05:00:00Z"), ZoneOffset.UTC);
        AppServerBootstrap.Foundation foundation = PlatformFoundationFactory.create(
                temporaryDirectory.resolve("configured/data-v6"), clock, new MemoryProtector(), required -> {});

        try (AppServerBootstrap.Components components =
                ConfiguredProviderBootstrap.create(foundation, reference -> Optional.empty())) {
            try (var session = components.newSession()) {
                assertNotNull(session);
                assertFalse(components.lifecycle().shutdownRequested());
            }
        }
    }

    private H2Database database() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        return database;
    }

    private static DiagnosticsService.Sources diagnosticSources(
            H2Database database,
            CoreCommandService core,
            LifecycleCoordinator lifecycle,
            SecretVaultService vault,
            ScheduleLifecycleCoordinator schedule,
            CanonicalJson json,
            Clock clock) {
        ProviderService providers = new ProviderService(database, reference -> true, json, clock);
        ExtensionJobService jobs = new ExtensionJobService(database, json, clock);
        ExtensionCatalogRepository extensions = new ExtensionCatalogRepository(database, json, clock);
        extensions.installBuiltIn(McpBuiltinExtensionDescriptor.create());
        var mcp = McpPlatformFactory.create(
                        new McpPlatformFactory.PlatformDependencies(
                                database,
                                vault,
                                new PrivateNetworkGrantService(database, json, clock),
                                extensions,
                                lifecycle,
                                json,
                                clock),
                        new McpPlatformFactory.RuntimeDependencies(
                                McpRuntimePorts.unavailable(),
                                ignored -> {
                                    throw new IllegalStateException("signed Bundle MCP unavailable");
                                },
                                Optional.empty()))
                .service();
        return new DiagnosticsService.Sources(
                new DiagnosticsService.CoreSources(database, core, lifecycle),
                new DiagnosticsService.PlatformSources(providers, vault, jobs, mcp),
                new DiagnosticsService.RuntimeSources(
                        ServerInfrastructureTest::extensionSummaries,
                        new BuiltinIsolatedServices.Availability(false, false, false),
                        schedule,
                        ServerInfrastructureTest::unavailableLauncher));
    }

    private static List<ExtensionRpcContracts.Summary> extensionSummaries() {
        return IntStream.range(0, 9)
                .mapToObj(index ->
                        extensionSummary(index, extensionState(index), index >= 7 ? "THIRD_PARTY" : "BUILT_IN"))
                .toList();
    }

    private static ExtensionRpcContracts.Summary extensionSummary(int index, String state, String trust) {
        return new ExtensionRpcContracts.Summary(
                "javaclaw.test." + index, "测试扩展 " + index, "5.0.0", 1, state, trust, Set.of("VIEW"));
    }

    private static String extensionState(int index) {
        return switch (index) {
            case 6 -> "DISABLED";
            case 7 -> "QUARANTINED";
            case 8 -> "INSTALLED";
            default -> "ENABLED";
        };
    }

    private static DiagnosticsRpcContracts.LauncherStatus unavailableLauncher() {
        return new DiagnosticsRpcContracts.LauncherStatus(false, false, false, Optional.of("测试环境未配置 launcher"));
    }

    private static final class MemoryProtector implements MasterKeyProtector {
        private final Map<String, byte[]> keys = new HashMap<>();

        @Override
        public Optional<byte[]> load(String keyId) {
            byte[] value = keys.get(keyId);
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        @Override
        public void store(String keyId, byte[] key) {
            keys.put(keyId, key.clone());
        }

        @Override
        public void delete(String keyId) {
            byte[] removed = keys.remove(keyId);
            if (removed != null) {
                Arrays.fill(removed, (byte) 0);
            }
        }
    }

    private static final class RecordingLoginStartup implements LoginStartupPort {
        private int writes;
        private boolean installed;

        @Override
        public void setRequired(boolean required) {
            writes++;
            installed = required;
        }

        @Override
        public Status status(boolean required) {
            return new Status(true, installed, Optional.empty());
        }
    }
}
