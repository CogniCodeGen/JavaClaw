package com.javaclaw.client;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.facade.ExecutionClient;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExecutionRpcContracts;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionClientTest {
    @Test
    void 直接配置读写使用各自Scope与Revision() {
        CanonicalJson json = new CanonicalJson();
        WorkspaceId workspace = WorkspaceId.parse("00000000-0000-0000-0000-000000000001");
        ThreadId thread = ThreadId.parse("00000000-0000-0000-0000-000000000002");
        ExecutionOverrides overrides = ExecutionOverrides.empty();
        ExecutionConfiguration configuration = new ExecutionConfiguration(
                Optional.of(workspace), Optional.of(thread), overrides, 2, Instant.parse("2026-09-07T00:00:00Z"));
        java.util.List<String> methods = new java.util.ArrayList<>();
        ScriptedRpcConnection rpc = new ScriptedRpcConnection(request -> {
            methods.add(request.method());
            if (request.method().endsWith("/read")) {
                return JsonRpcResponse.success(
                        request.id(), json.encode(new ExecutionRpcContracts.ReadResult(Optional.of(configuration))));
            }
            WriteCommand command = json.decode(request.params(), WriteCommand.class);
            assertEquals(1, command.expectedRevision());
            assertEquals(
                    overrides,
                    json.decode(
                            json.objectField(command.payload(), "execution").orElseThrow(), ExecutionOverrides.class));
            return JsonRpcResponse.success(request.id(), json.encode(configuration));
        });
        ExecutionClient client = new ExecutionClient(new RpcClientConnection(rpc, json, ignored -> {}));
        CommandOptions options = new CommandOptions("update", 1);

        assertEquals(Optional.of(configuration), client.readDefaults(Optional.of(workspace)));
        assertEquals(configuration, client.updateDefaults(Optional.of(workspace), overrides, options));
        assertEquals(Optional.of(configuration), client.readSubagentDefaults(Optional.of(workspace)));
        assertEquals(configuration, client.updateSubagentDefaults(Optional.of(workspace), overrides, options));
        assertEquals(Optional.of(configuration), client.readThread(workspace, thread));
        assertEquals(configuration, client.updateThread(workspace, thread, overrides, options));
        assertEquals(
                java.util.List.of(
                        "execution/default/read",
                        "execution/default/update",
                        "execution/subagent/read",
                        "execution/subagent/update",
                        "thread/execution/read",
                        "thread/execution/update"),
                methods);
    }
}
