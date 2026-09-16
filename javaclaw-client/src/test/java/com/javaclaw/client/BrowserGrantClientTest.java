package com.javaclaw.client;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BrowserGrantContracts;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.client.extension.BrowserClient;
import com.javaclaw.client.extension.ExtensionClient;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserGrantClientTest {
    private final CanonicalJson json = new CanonicalJson();
    private final WorkspaceId workspace = WorkspaceId.random();
    private final ThreadId thread = ThreadId.random();
    private final URI origin = URI.create("https://example.com");
    private final BrowserGrantContracts.Preview preview =
            new BrowserGrantContracts.Preview(workspace, thread, origin, Instant.EPOCH, "a".repeat(64));
    private final BrowserGrantContracts.Grant grant = new BrowserGrantContracts.Grant(
            UUID.randomUUID().toString(), 3, SecurityGrantState.ACTIVE, workspace, thread, origin, Instant.EPOCH);

    @Test
    void 查询与预览只调用扩展query并保留精确会话和来源() throws Exception {
        try (var connection = connection(false)) {
            var client = new BrowserClient(new ExtensionClient(connection));
            assertEquals(List.of(grant), client.grants(workspace, thread).grants());
            assertEquals(preview, client.previewGrant(workspace, thread, origin));
        }
    }

    @Test
    void 确认原预览和撤销原版本通过独立领域命令传递() throws Exception {
        try (var connection = connection(true)) {
            var client = new BrowserClient(new ExtensionClient(connection));
            assertEquals(grant, client.confirmGrant(workspace, thread, preview, new CommandOptions("confirm", 0)));
            assertEquals(grant, client.revokeGrant(workspace, thread, grant.id(), new CommandOptions("revoke", 3)));
        }
    }

    private RpcClientConnection connection(boolean write) {
        return new RpcClientConnection(
                new ScriptedRpcConnection(request -> {
                    assertEquals(write ? "extension/command" : "extension/query", request.method());
                    var command = write ? json.decode(request.params(), WriteCommand.class) : null;
                    var call = json.decode(
                            write ? command.payload() : request.params(), ExtensionRpcContracts.CallPayload.class);
                    assertEquals(workspace, call.workspaceId());
                    assertEquals(Optional.of(thread), call.threadId());
                    assertEquals(BuiltinExtensionIds.SITE, call.extensionId());
                    assertTrue(call.turnId().isEmpty());
                    Object result =
                            switch (call.operation()) {
                                case "browser.grants" -> new BrowserGrantContracts.GrantList(List.of(grant));
                                case "browser.grant.preview" -> {
                                    assertEquals(
                                            origin.toString(),
                                            json.textField(call.payload(), "origin")
                                                    .orElseThrow());
                                    yield preview;
                                }
                                case "browser.grant.confirm" -> {
                                    assertEquals(0, command.expectedRevision());
                                    assertEquals(json.encode(preview), call.payload());
                                    yield grant;
                                }
                                case "browser.grant.revoke" -> {
                                    assertEquals(3, command.expectedRevision());
                                    assertEquals(
                                            grant.id(),
                                            json.textField(call.payload(), "id").orElseThrow());
                                    yield grant;
                                }
                                default -> throw new AssertionError("不得使用新的 Core RPC");
                            };
                    return JsonRpcResponse.success(
                            request.id(), json.encode(new ExtensionRpcContracts.CallResult(json.encode(result), 0)));
                }),
                json,
                ignored -> {});
    }
}
