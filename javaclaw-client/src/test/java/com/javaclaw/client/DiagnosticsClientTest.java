package com.javaclaw.client;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.client.facade.DiagnosticsClient;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.DiagnosticsRpcContracts;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsClientTest {
    private static final CanonicalJson JSON = new CanonicalJson();

    @Test
    void launcher状态与停止命令使用强类型协议() {
        DiagnosticsRpcContracts.LauncherStatus status =
                new DiagnosticsRpcContracts.LauncherStatus(true, true, true, Optional.empty());
        DiagnosticsRpcContracts.ServerStopResult stopped =
                new DiagnosticsRpcContracts.ServerStopResult(true, 1, 0, Optional.empty());
        ScriptedRpcConnection rpc = new ScriptedRpcConnection(request -> switch (request.method()) {
            case "diagnostics/launcher/read" -> {
                assertEquals("{}", request.params().json());
                yield success(request, status);
            }
            case "diagnostics/server/stop" -> {
                WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
                assertEquals("tray-stop", command.idempotencyKey());
                assertEquals(0, command.expectedRevision());
                assertEquals(
                        new DiagnosticsRpcContracts.ServerStopPayload(),
                        JSON.decode(command.payload(), DiagnosticsRpcContracts.ServerStopPayload.class));
                yield success(request, stopped);
            }
            default -> throw new AssertionError("unexpected method " + request.method());
        });
        DiagnosticsClient client = new DiagnosticsClient(new RpcClientConnection(rpc, JSON, ignored -> {}));

        assertEquals(status, client.launcherStatus());
        assertEquals(stopped, client.stopServer(new CommandOptions("tray-stop", 0)));
        assertTrue(stopped.accepted());
    }

    private static JsonRpcResponse success(JsonRpcRequest request, Object result) {
        return JsonRpcResponse.success(request.id(), JSON.encode(result));
    }
}
