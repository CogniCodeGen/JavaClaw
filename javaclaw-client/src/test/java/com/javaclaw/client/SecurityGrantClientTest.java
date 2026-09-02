package com.javaclaw.client;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantPreview;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.SecurityGrantKind;
import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.UnattendedToolGrant;
import com.javaclaw.api.UnattendedToolGrantDraft;
import com.javaclaw.api.UnattendedToolGrantStatus;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.facade.SecurityGrantClient;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.SecurityGrantRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGrantClientTest {
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("a7b38e63-d062-464f-a09f-d01500a026da");
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    @Test
    void facade为两类授权和审计保持逐方法强类型() {
        PrivateNetworkGrantPreview preview = preview();
        PrivateNetworkGrant privateGrant = privateGrant(preview);
        UnattendedToolGrant unattended = unattended();
        List<String> called = new ArrayList<>();
        ScriptedRpcConnection rpc = new ScriptedRpcConnection(request -> {
            called.add(request.method());
            return response(request, preview, privateGrant, unattended);
        });
        SecurityGrantClient client = new SecurityGrantClient(new RpcClientConnection(rpc, JSON, ignored -> {}));

        assertEquals(
                preview,
                client.previewPrivateNetwork(
                        WORKSPACE,
                        PrivateNetworkPurpose.MCP,
                        preview.origin(),
                        preview.dnsAddresses(),
                        Optional.of(Duration.ofHours(1))));
        assertEquals(privateGrant, client.createPrivateNetwork(preview, new CommandOptions("private-create", 0)));
        assertEquals(List.of(privateGrant), client.listPrivateNetwork(WORKSPACE));
        assertEquals(List.of(privateGrant), client.privateNetworkHistory(privateGrant.id()));
        assertEquals(
                privateGrant, client.revokePrivateNetwork(privateGrant.id(), new CommandOptions("private-revoke", 1)));
        assertEquals(unattended, client.createUnattended(draft(), new CommandOptions("unattended-create", 0)));
        assertEquals(1, client.listUnattended(WORKSPACE).getFirst().remainingUses());
        assertEquals(List.of(unattended), client.unattendedHistory(unattended.id()));
        assertEquals(unattended, client.revokeUnattended(unattended.id(), new CommandOptions("unattended-revoke", 1)));
        assertTrue(client.decisions(
                        WORKSPACE, Optional.of(SecurityGrantKind.PRIVATE_NETWORK), Optional.of(privateGrant.id()), 10)
                .isEmpty());
        assertEquals(10, called.size());
    }

    private static JsonRpcResponse response(
            JsonRpcRequest request,
            PrivateNetworkGrantPreview preview,
            PrivateNetworkGrant privateGrant,
            UnattendedToolGrant unattended) {
        return switch (request.method()) {
            case "privateNetworkGrant/preview" -> {
                SecurityGrantRpcContracts.PrivateNetworkPreviewPayload payload =
                        JSON.decode(request.params(), SecurityGrantRpcContracts.PrivateNetworkPreviewPayload.class);
                assertEquals(WORKSPACE, payload.workspaceId());
                yield success(request, preview);
            }
            case "privateNetworkGrant/list" ->
                success(request, new SecurityGrantRpcContracts.PrivateNetworkListResult(List.of(privateGrant)));
            case "privateNetworkGrant/history" ->
                success(request, new SecurityGrantRpcContracts.PrivateNetworkHistoryResult(List.of(privateGrant)));
            case "privateNetworkGrant/create", "privateNetworkGrant/revoke" -> {
                assertCommand(request);
                yield success(request, privateGrant);
            }
            case "unattendedToolGrant/list" ->
                success(
                        request,
                        new SecurityGrantRpcContracts.UnattendedListResult(
                                List.of(new UnattendedToolGrantStatus(unattended, 0, 1))));
            case "unattendedToolGrant/history" ->
                success(request, new SecurityGrantRpcContracts.UnattendedHistoryResult(List.of(unattended)));
            case "unattendedToolGrant/create", "unattendedToolGrant/revoke" -> {
                assertCommand(request);
                yield success(request, unattended);
            }
            case "permissionDecision/list" ->
                success(request, new SecurityGrantRpcContracts.PermissionDecisionListResult(List.of()));
            default -> throw new AssertionError("unexpected method " + request.method());
        };
    }

    private static void assertCommand(JsonRpcRequest request) {
        WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
        assertTrue(command.idempotencyKey().contains("create")
                || command.idempotencyKey().contains("revoke"));
    }

    private static JsonRpcResponse success(JsonRpcRequest request, Object value) {
        return JsonRpcResponse.success(request.id(), JSON.encode(value));
    }

    private static PrivateNetworkGrantPreview preview() {
        return new PrivateNetworkGrantPreview(
                WORKSPACE,
                PrivateNetworkPurpose.MCP,
                URI.create("https://mcp.example"),
                Set.of("10.0.0.1"),
                NOW.plus(Duration.ofHours(1)),
                DIGEST);
    }

    private static PrivateNetworkGrant privateGrant(PrivateNetworkGrantPreview preview) {
        return new PrivateNetworkGrant(
                "private-grant",
                1,
                SecurityGrantState.ACTIVE,
                WORKSPACE,
                preview.purpose(),
                preview.origin(),
                preview.dnsAddresses(),
                preview.expiresAt(),
                NOW,
                NOW);
    }

    private static UnattendedToolGrantDraft draft() {
        return new UnattendedToolGrantDraft(
                WORKSPACE,
                "nightly",
                1,
                tool(),
                2,
                DIGEST,
                new CanonicalPayload("{\"message\":\"fixed\"}"),
                Set.of("message"),
                1,
                Duration.ofDays(1));
    }

    private static UnattendedToolGrant unattended() {
        UnattendedToolGrantDraft draft = draft();
        return new UnattendedToolGrant(
                "unattended-grant",
                1,
                SecurityGrantState.ACTIVE,
                WORKSPACE,
                draft.scheduleId(),
                draft.scheduleRevision(),
                draft.tool(),
                draft.catalogRevision(),
                draft.schemaHash(),
                draft.fixedArguments(),
                draft.variableStringFields(),
                draft.maximumUses(),
                NOW.plus(draft.validity()),
                NOW,
                NOW);
    }

    private static ToolIdentity tool() {
        return new ToolIdentity("schedule-extension", "notify", 1);
    }
}
