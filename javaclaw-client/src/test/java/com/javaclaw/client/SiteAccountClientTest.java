package com.javaclaw.client;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.client.extension.ExtensionClient;
import com.javaclaw.client.extension.SiteAccountClient;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.ExtensionSecretRpcContracts;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SiteAccountClientTest {
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("ae6ff47c-2406-4e41-82cd-7c63cd8a7ebf");

    @Test
    void 账号密码在wire编码前密封并绑定完整领域身份() throws Exception {
        var account = new SiteAccountContracts.AccountProjection(
                "account", "site", 2, 2, 0, "工作", true, true, true, false, Instant.EPOCH);
        try (SessionSecretChannel secrets = SessionSecretChannel.open();
                RpcClientConnection connection = new RpcClientConnection(
                        new ScriptedRpcConnection(request -> {
                            assertEquals(ExtensionSecretRpcContracts.METHOD, request.method());
                            assertFalse(request.params().json().contains("unique-password"));
                            assertFalse(request.params().json().contains("unique-username"));
                            WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
                            var payload = JSON.decode(command.payload(), ExtensionSecretRpcContracts.Payload.class);
                            assertEquals(
                                    "account/credential/set", payload.call().operation());
                            assertEquals(WORKSPACE, payload.call().workspaceId());
                            byte[] plaintext = secrets.unseal(
                                    payload.secret(),
                                    ExtensionSecretRpcContracts.purpose(payload.call(), command.expectedRevision()));
                            try {
                                assertEquals(
                                        "unique-username\0unique-password",
                                        new String(plaintext, StandardCharsets.UTF_8));
                            } finally {
                                Arrays.fill(plaintext, (byte) 0);
                            }
                            return JsonRpcResponse.success(
                                    request.id(),
                                    JSON.encode(new ExtensionRpcContracts.CallResult(JSON.encode(account), 2)));
                        }),
                        JSON,
                        ignored -> {})) {
            var client = new SiteAccountClient(new ExtensionClient(connection, secrets.publicKey()));
            var input = new SiteAccountContracts.CredentialRequest(
                    new SiteAccountContracts.Selection("site", "account"), 1, 1);
            assertEquals(
                    account,
                    client.setCredential(
                            WORKSPACE,
                            input,
                            "unique-username".toCharArray(),
                            "unique-password".toCharArray(),
                            new CommandOptions("one", 1)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.setCredential(
                            WORKSPACE, input, new char[0], "p".toCharArray(), new CommandOptions("empty", 1)));
        }
    }
}
