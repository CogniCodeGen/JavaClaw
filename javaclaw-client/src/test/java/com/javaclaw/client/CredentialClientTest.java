package com.javaclaw.client;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CredentialClearReceipt;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.VaultLockReason;
import com.javaclaw.api.VaultManagementAction;
import com.javaclaw.api.VaultManagementReceipt;
import com.javaclaw.api.VaultState;
import com.javaclaw.api.VaultStatus;
import com.javaclaw.client.facade.CredentialClient;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CredentialRpcContracts;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CredentialClientTest {
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final CredentialRef REFERENCE = new CredentialRef("mcp", "secret-1");
    private static final CredentialMetadata CREATED = new CredentialMetadata(REFERENCE, 1, NOW);
    private static final CredentialMetadata ROTATED = new CredentialMetadata(REFERENCE, 2, NOW);
    private static final VaultStatus READY = new VaultStatus(VaultState.READY, VaultLockReason.NONE, 1, false, NOW);

    @Test
    void credentialFacadeSealsSecretsAndMapsVaultLifecycle() throws Exception {
        try (SessionSecretChannel secrets = SessionSecretChannel.open()) {
            CredentialScript script = new CredentialScript(secrets);
            try (RpcClientConnection connection =
                    new RpcClientConnection(new ScriptedRpcConnection(script::respond), JSON, ignored -> {})) {
                CredentialClient client = new CredentialClient(connection, secrets.publicKey());

                assertEquals(READY, client.status());
                assertEquals(READY, client.refresh());
                assertEquals(Optional.of(CREATED), client.read(REFERENCE));
                assertEquals(Optional.empty(), client.read(new CredentialRef("mcp", "missing")));
                assertEquals(List.of(CREATED), client.list("mcp"));
                assertEquals(
                        CREATED, client.create("mcp", "first-secret".toCharArray(), new CommandOptions("create", 0)));
                assertEquals(
                        ROTATED,
                        client.rotate(REFERENCE, "second-secret".toCharArray(), new CommandOptions("rotate", 1)));
                assertEquals(
                        new CredentialClearReceipt(REFERENCE, 2, NOW),
                        client.clear(REFERENCE, new CommandOptions("clear", 2)));
                assertEquals(
                        new VaultManagementReceipt(VaultManagementAction.MASTER_KEY_ROTATED, 1, NOW),
                        client.rotateMasterKey(new CommandOptions("master-key", 0)));
                assertEquals(
                        new VaultManagementReceipt(VaultManagementAction.VAULT_RESET, 1, NOW),
                        client.reset("RESET VAULT", new CommandOptions("reset", 0)));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> client.create("bad namespace", "secret".toCharArray(), new CommandOptions("bad", 0)));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> client.reset("reset vault", new CommandOptions("bad-reset", 0)));
            }

            assertEquals("first-secret", script.createdPlaintext);
            assertEquals("second-secret", script.rotatedPlaintext);
            assertEquals(10, script.calls);
        }
    }

    private static final class CredentialScript {
        private final SessionSecretChannel secrets;
        private String createdPlaintext;
        private String rotatedPlaintext;
        private int calls;

        private CredentialScript(SessionSecretChannel secrets) {
            this.secrets = secrets;
        }

        private JsonRpcResponse respond(JsonRpcRequest request) {
            calls++;
            Object result =
                    switch (request.method()) {
                        case "credential/status", "vault/refresh" -> READY;
                        case "credential/read" -> read(request);
                        case "credential/list" -> list(request);
                        case "credential/create" -> create(request);
                        case "credential/rotate" -> rotate(request);
                        case "credential/clear" -> new CredentialClearReceipt(REFERENCE, 2, NOW);
                        case "vault/masterKey/rotate" ->
                            new VaultManagementReceipt(VaultManagementAction.MASTER_KEY_ROTATED, 1, NOW);
                        case "vault/reset" -> new VaultManagementReceipt(VaultManagementAction.VAULT_RESET, 1, NOW);
                        default -> throw new AssertionError("unexpected Credential method " + request.method());
                    };
            return JsonRpcResponse.success(request.id(), JSON.encode(result));
        }

        private static CredentialRpcContracts.ReadResult read(JsonRpcRequest request) {
            CredentialRpcContracts.ReadPayload payload =
                    JSON.decode(request.params(), CredentialRpcContracts.ReadPayload.class);
            return new CredentialRpcContracts.ReadResult(
                    payload.reference().equals(REFERENCE) ? Optional.of(CREATED) : Optional.empty());
        }

        private static CredentialRpcContracts.ListResult list(JsonRpcRequest request) {
            CredentialRpcContracts.ListPayload payload =
                    JSON.decode(request.params(), CredentialRpcContracts.ListPayload.class);
            assertEquals("mcp", payload.namespace());
            return new CredentialRpcContracts.ListResult(List.of(CREATED));
        }

        private CredentialMetadata create(JsonRpcRequest request) {
            WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
            assertEquals(0, command.expectedRevision());
            CredentialRpcContracts.CreatePayload payload =
                    JSON.decode(command.payload(), CredentialRpcContracts.CreatePayload.class);
            createdPlaintext = unseal(payload.secret(), "credential/mcp/create");
            return CREATED;
        }

        private CredentialMetadata rotate(JsonRpcRequest request) {
            WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
            assertEquals(1, command.expectedRevision());
            CredentialRpcContracts.RotatePayload payload =
                    JSON.decode(command.payload(), CredentialRpcContracts.RotatePayload.class);
            assertEquals(REFERENCE, payload.reference());
            rotatedPlaintext = unseal(payload.secret(), "credential/mcp/rotate");
            return ROTATED;
        }

        private String unseal(com.javaclaw.protocol.SealedSecret sealed, String purpose) {
            byte[] plaintext = secrets.unseal(sealed, purpose);
            try {
                return new String(plaintext, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }
}
