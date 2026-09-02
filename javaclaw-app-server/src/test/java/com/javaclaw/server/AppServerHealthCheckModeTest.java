package com.javaclaw.server;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.VaultLockReason;
import com.javaclaw.api.VaultState;
import com.javaclaw.api.VaultStatus;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.RpcId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppServerHealthCheckModeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void 健康检查Bootstrap锁定Vault且仍可完成ProtocolV2查询() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-09-02T06:00:00Z"), ZoneOffset.UTC);
        AppServerOptions options = AppServerOptions.parse(new String[] {"--health-check"});

        try (AppServerBootstrap.Components components =
                        AppServerMain.createComponents(options, temporaryDirectory.resolve("health/data-v5"), clock);
                var session = components.newSession()) {
            JsonRpcResponse initialized = session.handle(request(
                    components,
                    "initialize",
                    "initialize/session",
                    new InitializeParams(
                            ProtocolVersion.CURRENT,
                            new ClientInfo("health-test", "5.0"),
                            new CapabilityAdvertisement(Set.of("core.item-envelope"), Set.of()))));
            VaultStatus status = components
                    .json()
                    .decode(
                            session.handle(request(components, "vault", "credential/status", new Empty()))
                                    .result()
                                    .orElseThrow(),
                            VaultStatus.class);

            assertTrue(initialized.result().isPresent());
            assertEquals(VaultState.LOCKED, status.state());
            assertEquals(VaultLockReason.SYSTEM_CREDENTIAL_UNAVAILABLE, status.reason());
            assertEquals(0, status.credentialCount());
        }
    }

    private static JsonRpcRequest request(
            AppServerBootstrap.Components components, String id, String method, Object parameters) {
        return new JsonRpcRequest(new RpcId(id), method, components.json().encode(parameters));
    }

    private record Empty() {}
}
