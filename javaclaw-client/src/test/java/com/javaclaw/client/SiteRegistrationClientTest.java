package com.javaclaw.client;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.client.extension.ExtensionClient;
import com.javaclaw.client.extension.SiteRegistrationClient;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteRegistrationClientTest {
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("ae6ff47c-2406-4e41-82cd-7c63cd8a7ebf");
    private static final String SESSION = "40a90908-698c-403d-879d-11c54bbb5d6e";
    private static final URI ORIGIN = URI.create("https://example.com");

    @Test
    void 登记命令固定工作区且查询不携带写入身份或秘密() throws Exception {
        List<String> operations = new ArrayList<>();
        List<CommandOptions> identities = new ArrayList<>();
        var response = new SiteRegistrationContracts.Session(
                SESSION,
                SiteRegistrationContracts.State.ACTIVE,
                new SiteRegistrationContracts.Access(1, Set.of(ORIGIN), Set.of(), Instant.MAX),
                new SiteRegistrationContracts.Page(1, Optional.of(ORIGIN), "测试网站", List.of()),
                Optional.empty());
        try (RpcClientConnection connection = new RpcClientConnection(
                new ScriptedRpcConnection(request -> reply(request, response, operations, identities)),
                JSON,
                ignored -> {})) {
            var client = new SiteRegistrationClient(new ExtensionClient(connection));
            CommandOptions identity = new CommandOptions("stable-registration", 0);
            assertEquals(
                    response, client.begin(WORKSPACE, new SiteRegistrationContracts.BeginRequest(ORIGIN), identity));
            assertEquals(response, client.status(WORKSPACE, new SiteRegistrationContracts.SessionRequest(SESSION)));
            assertEquals(
                    response,
                    client.allowOrigin(
                            WORKSPACE, new SiteRegistrationContracts.OriginRequest(SESSION, 1, ORIGIN), identity));
            assertEquals(
                    response,
                    client.complete(
                            WORKSPACE,
                            new SiteRegistrationContracts.CompleteRequest(SESSION, 1, 1, Optional.empty(), "网站"),
                            identity));
            assertEquals(
                    response,
                    client.cancel(WORKSPACE, new SiteRegistrationContracts.SessionRequest(SESSION), identity));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.begin(
                            WORKSPACE,
                            new SiteRegistrationContracts.BeginRequest(ORIGIN),
                            new CommandOptions("invalid", 1)));
        }
        assertEquals(
                List.of(
                        "registration.begin",
                        "registration.status",
                        "registration.origin",
                        "registration.complete",
                        "registration.cancel"),
                operations);
        assertEquals(4, identities.size());
        assertTrue(identities.stream().allMatch(value -> value.equals(new CommandOptions("stable-registration", 0))));
    }

    private static JsonRpcResponse reply(
            JsonRpcRequest request,
            SiteRegistrationContracts.Session response,
            List<String> operations,
            List<CommandOptions> identities) {
        ExtensionRpcContracts.CallPayload call;
        if (request.method().equals("extension/command")) {
            WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
            identities.add(new CommandOptions(command.idempotencyKey(), command.expectedRevision()));
            call = JSON.decode(command.payload(), ExtensionRpcContracts.CallPayload.class);
        } else {
            assertEquals("extension/query", request.method());
            call = JSON.decode(request.params(), ExtensionRpcContracts.CallPayload.class);
        }
        assertEquals(BuiltinExtensionIds.SITE, call.extensionId());
        assertEquals(WORKSPACE, call.workspaceId());
        assertTrue(call.threadId().isEmpty());
        assertTrue(call.turnId().isEmpty());
        operations.add(call.operation());
        return JsonRpcResponse.success(
                request.id(), JSON.encode(new ExtensionRpcContracts.CallResult(JSON.encode(response), 0)));
    }
}
