package com.javaclaw.client;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.client.extension.BrowserClient;
import com.javaclaw.client.extension.ExtensionClient;
import com.javaclaw.client.extension.SiteClient;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserClientTest {
    @Test
    void 控制权调用绑定当前Thread和用户看到的租约代次() throws Exception {
        CanonicalJson json = new CanonicalJson();
        WorkspaceId workspace = WorkspaceId.random();
        ThreadId thread = ThreadId.random();
        BrowserCommands.Status status = new BrowserCommands.Status(true, Optional.empty(), "已关闭");
        try (RpcClientConnection connection = new RpcClientConnection(
                new ScriptedRpcConnection(request -> {
                    assertEquals("extension/command", request.method());
                    WriteCommand command = json.decode(request.params(), WriteCommand.class);
                    assertEquals(17, command.expectedRevision());
                    var call = json.decode(command.payload(), ExtensionRpcContracts.CallPayload.class);
                    assertEquals(BuiltinExtensionIds.SITE, call.extensionId());
                    assertEquals(workspace, call.workspaceId());
                    assertEquals(Optional.of(thread), call.threadId());
                    assertTrue(call.turnId().isEmpty());
                    assertEquals("browser.close", call.operation());
                    assertEquals("{}", call.payload().json());
                    return JsonRpcResponse.success(
                            request.id(), json.encode(new ExtensionRpcContracts.CallResult(json.encode(status), 0)));
                }),
                json,
                ignored -> {})) {
            BrowserClient browser = new SiteClient(new ExtensionClient(connection)).browser();
            assertEquals(
                    status,
                    browser.control(
                            workspace, thread, BrowserClient.Control.CLOSE, new CommandOptions("close-once", 17)));
        }
    }
}
