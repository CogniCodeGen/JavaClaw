package com.javaclaw.protocol;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PrivateNetworkGrantPreview;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.SecurityGrantKind;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.UnattendedToolGrantDraft;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGrantRpcContractsTest {
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("9a13972c-bdc0-4ba8-a2d8-cdaeccf7ad7d");
    private static final String DIGEST = "a".repeat(64);
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void 私网预览和命令DTO按强类型往返() {
        SecurityGrantRpcContracts.PrivateNetworkPreviewPayload payload =
                new SecurityGrantRpcContracts.PrivateNetworkPreviewPayload(
                        WORKSPACE,
                        PrivateNetworkPurpose.MCP,
                        URI.create("https://mcp.example"),
                        Set.of("10.0.0.1"),
                        Optional.of(Duration.ofHours(1)));
        PrivateNetworkGrantPreview preview = new PrivateNetworkGrantPreview(
                WORKSPACE,
                PrivateNetworkPurpose.MCP,
                payload.origin(),
                payload.dnsAddresses(),
                Instant.parse("2026-09-01T01:00:00Z"),
                DIGEST);

        assertEquals(payload, json.decode(json.encode(payload), payload.getClass()));
        assertEquals(
                preview,
                json.decode(
                                json.encode(new SecurityGrantRpcContracts.PrivateNetworkCreatePayload(preview)),
                                SecurityGrantRpcContracts.PrivateNetworkCreatePayload.class)
                        .preview());
        assertEquals(WORKSPACE, new SecurityGrantRpcContracts.WorkspaceGrantQuery(WORKSPACE).workspaceId());
        assertEquals("grant", new SecurityGrantRpcContracts.GrantHistoryQuery(" grant ").grantId());
        assertEquals("grant", new SecurityGrantRpcContracts.GrantRevokePayload(" grant ").grantId());
    }

    @Test
    void 无人值守和审计DTO复制集合并限制查询上限() {
        UnattendedToolGrantDraft draft = new UnattendedToolGrantDraft(
                WORKSPACE,
                "schedule",
                1,
                new ToolIdentity("schedule-extension", "notify", 1),
                2,
                DIGEST,
                new CanonicalPayload("{\"message\":\"fixed\"}"),
                Set.of("message"),
                10,
                Duration.ofDays(1));
        SecurityGrantRpcContracts.UnattendedCreatePayload create =
                new SecurityGrantRpcContracts.UnattendedCreatePayload(draft);
        SecurityGrantRpcContracts.PermissionDecisionListPayload query =
                new SecurityGrantRpcContracts.PermissionDecisionListPayload(
                        WORKSPACE, Optional.of(SecurityGrantKind.UNATTENDED_TOOL), Optional.of("grant"), 100);

        assertEquals(draft, json.decode(json.encode(create), create.getClass()).draft());
        assertEquals(100, query.limit());
        assertEquals(List.of(), new SecurityGrantRpcContracts.PrivateNetworkListResult(List.of()).grants());
        assertEquals(List.of(), new SecurityGrantRpcContracts.PrivateNetworkHistoryResult(List.of()).grants());
        assertEquals(List.of(), new SecurityGrantRpcContracts.UnattendedListResult(List.of()).grants());
        assertEquals(List.of(), new SecurityGrantRpcContracts.UnattendedHistoryResult(List.of()).grants());
        assertEquals(List.of(), new SecurityGrantRpcContracts.PermissionDecisionListResult(List.of()).traces());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SecurityGrantRpcContracts.PermissionDecisionListPayload(
                        WORKSPACE, Optional.empty(), Optional.empty(), 0));
        assertThrows(IllegalArgumentException.class, () -> new SecurityGrantRpcContracts.GrantHistoryQuery("bad id"));
    }

    @Test
    void 方法目录与逐方法Schema同时声明安全授权() throws IOException {
        NegotiatedCapabilities none = new NegotiatedCapabilities(Set.of(), Set.of());
        assertEquals(
                RpcMethodKind.QUERY,
                MethodCatalog.require("privateNetworkGrant/preview", none).kind());
        assertEquals(
                RpcMethodKind.COMMAND,
                MethodCatalog.require("unattendedToolGrant/create", none).kind());
        assertEquals(
                RpcMethodKind.QUERY,
                MethodCatalog.require("permissionDecision/list", none).kind());

        String schema = read("/schema/security-grants-v3.schema.json");
        String methods = read("/schema/methods-v3.json");
        json.parse(schema);
        json.parse(methods);
        assertTrue(schema.contains("UNKNOWN_OUTCOME") || schema.contains("unattendedToolGrant"));
        assertTrue(methods.contains("security-grants-v3.schema.json#/$defs/privateNetworkPreviewParams"));
    }

    private static String read(String resource) throws IOException {
        try (var input = SecurityGrantRpcContractsTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("missing resource " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
