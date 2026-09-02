package com.javaclaw.client;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.client.facade.BuiltinExtensionManagementClient;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.protocol.BuiltinExtensionRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuiltinExtensionManagementClientTest {
    private static final CanonicalJson JSON = new CanonicalJson();

    @Test
    void facade查询和启停都保持独立状态Revision() {
        BuiltinExtensionRpcContracts.Status enabled = status(ExtensionState.ENABLED, 1);
        BuiltinExtensionRpcContracts.Status disabled = status(ExtensionState.DISABLED, 2);
        ScriptedRpcConnection rpc = new ScriptedRpcConnection(request -> switch (request.method()) {
            case "extension/builtin/list" ->
                success(request, new BuiltinExtensionRpcContracts.ListResult(List.of(enabled)));
            case "extension/builtin/read" -> success(request, new BuiltinExtensionRpcContracts.StatusResult(enabled));
            case "extension/builtin/disable" -> {
                WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
                assertEquals(1, command.expectedRevision());
                assertEquals("disable", command.idempotencyKey());
                yield success(request, new BuiltinExtensionRpcContracts.StatusResult(disabled));
            }
            default -> throw new AssertionError("unexpected method " + request.method());
        });
        BuiltinExtensionManagementClient client =
                new BuiltinExtensionManagementClient(new RpcClientConnection(rpc, JSON, ignored -> {}));

        assertEquals(List.of(enabled), client.list());
        assertEquals(enabled, client.read(enabled.id()));
        assertEquals(disabled, client.disable(enabled.id(), new CommandOptions("disable", 1)));
    }

    private static BuiltinExtensionRpcContracts.Status status(ExtensionState state, long stateRevision) {
        return new BuiltinExtensionRpcContracts.Status(
                "com.javaclaw.plan",
                "计划",
                "5.0.0",
                1,
                stateRevision,
                ExtensionAvailability.OPTIONAL,
                state,
                BuiltinExtensionRpcContracts.RuntimeKind.BUNDLE,
                Set.of(ContributionKind.COMMAND, ContributionKind.VIEW),
                Instant.parse("2026-09-01T00:00:00Z"));
    }

    private static JsonRpcResponse success(JsonRpcRequest request, Object result) {
        return JsonRpcResponse.success(request.id(), JSON.encode(result));
    }
}
