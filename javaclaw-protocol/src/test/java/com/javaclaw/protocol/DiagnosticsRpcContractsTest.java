package com.javaclaw.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.DiagnosticsSnapshot;
import com.javaclaw.api.VaultState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsRpcContractsTest {
    @Test
    void 脱敏子系统快照经过规范Json往返且修复方法是写命令() {
        CanonicalJson json = new CanonicalJson();
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        DiagnosticsSnapshot snapshot = new DiagnosticsSnapshot(
                new DiagnosticsSnapshot.BuildIdentity("5.0.0", 2, 1),
                new DiagnosticsSnapshot.RuntimeHealth(true, 1, 9, 1, 2, "test", "25"),
                new DiagnosticsSnapshot.SubsystemHealth(
                        new DiagnosticsSnapshot.ProviderVaultHealth(2, 1, VaultState.READY, 3),
                        new DiagnosticsSnapshot.ExtensionHealth(9, 7, 1, 1, 1),
                        new DiagnosticsSnapshot.IntegrationHealth(2, 1, 1, false, true, false),
                        new DiagnosticsSnapshot.JobHealth(1, 2, 3, 4),
                        new DiagnosticsSnapshot.ScheduleHealth(
                                true, true, 1, false, false, Optional.of("IDEA 调试未配置 launcher")),
                        new DiagnosticsSnapshot.LauncherHealth(
                                false, false, false, Optional.of("IDEA 调试未配置 launcher"))),
                now,
                now);

        assertEquals(snapshot, json.decode(json.encode(snapshot), DiagnosticsSnapshot.class));
        assertEquals(
                RpcMethodKind.COMMAND,
                MethodCatalog.require("diagnostics/loginStartup/repair", new NegotiatedCapabilities(Set.of(), Set.of()))
                        .kind());
    }

    @Test
    void 逐方法Schema只包含白名单状态字段() throws IOException {
        String schema = read("/schema/diagnostics-v3.schema.json");
        String methods = read("/schema/methods-v3.json");

        new CanonicalJson().parse(schema);
        assertTrue(methods.contains("diagnostics-v3.schema.json#/$defs/repairCommand"));
        assertTrue(methods.contains("diagnostics-v3.schema.json#/$defs/launcherStatusParams"));
        assertTrue(methods.contains("diagnostics-v3.schema.json#/$defs/serverStopCommand"));
        assertTrue(schema.contains("\"browserWorkerAvailable\""));
        assertTrue(schema.contains("\"skillExecutionAvailable\""));
        assertTrue(schema.contains("\"loginStartupInstalled\""));
        assertTrue(schema.contains("\"quarantined\""));
        assertTrue(schema.contains("\"trayActive\""));
        assertTrue(schema.contains("\"serverControlAvailable\""));
        assertTrue(schema.contains("\"serverStopCommand\""));
        assertFalse(schema.contains("environment"));
        assertFalse(schema.contains("absolutePath"));
        assertFalse(schema.contains("promptContent"));
    }

    @Test
    void launcher状态与停止决策拒绝自相矛盾字段() {
        DiagnosticsRpcContracts.LauncherStatus available =
                new DiagnosticsRpcContracts.LauncherStatus(true, true, true, Optional.empty());
        DiagnosticsRpcContracts.ServerStopResult accepted =
                new DiagnosticsRpcContracts.ServerStopResult(true, 1, 0, Optional.empty());

        assertTrue(available.serverControlAvailable());
        assertTrue(accepted.accepted());
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiagnosticsRpcContracts.LauncherStatus(true, false, true, Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiagnosticsRpcContracts.LauncherStatus(false, true, false, Optional.of("配置缺失")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiagnosticsRpcContracts.ServerStopResult(false, 0, 0, Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiagnosticsRpcContracts.ServerStopResult(true, 2, 0, Optional.empty()));
    }

    private static String read(String resource) throws IOException {
        try (var stream = DiagnosticsRpcContractsTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
