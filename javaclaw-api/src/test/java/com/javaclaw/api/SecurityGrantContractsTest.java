package com.javaclaw.api;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGrantContractsTest {
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("5db6adeb-a154-4249-9220-c9f4077ed4f5");
    private static final Instant CREATED = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-09-02T00:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    @Test
    void 私网授权规范Origin并保留不可变版本身份() {
        PrivateNetworkGrant grant = privateGrant(URI.create("https://Example.COM:443/"));

        assertEquals(URI.create("https://example.com"), grant.origin());
        assertEquals(Set.of("10.0.0.1"), grant.dnsAddresses());
        assertEquals(SecurityGrantState.ACTIVE, grant.state());
        assertEquals(
                URI.create("https://[fd12:3456::1]"),
                PrivateNetworkGrant.normalizeOrigin(URI.create("https://[FD12:3456::1]")));
    }

    @Test
    void 私网授权拒绝非Origin端口范围和矛盾时间() {
        assertThrows(IllegalArgumentException.class, () -> PrivateNetworkGrant.normalizeOrigin(URI.create("http://x")));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrivateNetworkGrant.normalizeOrigin(URI.create("https://user@example.com")));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrivateNetworkGrant.normalizeOrigin(URI.create("https://example.com/path")));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrivateNetworkGrant.normalizeOrigin(URI.create("https://example.com?query=1")));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrivateNetworkGrant.normalizeOrigin(URI.create("https://example.com#fragment")));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrivateNetworkGrant.normalizeOrigin(URI.create("https://example.com:0")));
        assertThrows(IllegalArgumentException.class, () -> PrivateNetworkGrant.normalizeOrigin(URI.create("https:x")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PrivateNetworkGrant(
                        "grant",
                        1,
                        SecurityGrantState.ACTIVE,
                        WORKSPACE,
                        PrivateNetworkPurpose.MCP,
                        URI.create("https://example.com"),
                        Set.of(),
                        EXPIRES,
                        CREATED,
                        CREATED));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PrivateNetworkGrant(
                        "grant",
                        1,
                        SecurityGrantState.ACTIVE,
                        WORKSPACE,
                        PrivateNetworkPurpose.MCP,
                        URI.create("https://example.com"),
                        Set.of("10.0.0.1"),
                        CREATED,
                        CREATED,
                        CREATED));
    }

    @Test
    void 私网预览复制地址并校验确认摘要() {
        PrivateNetworkGrantPreview preview = new PrivateNetworkGrantPreview(
                WORKSPACE,
                PrivateNetworkPurpose.SITE,
                URI.create("https://site.example/"),
                Set.of("192.168.1.1"),
                EXPIRES,
                DIGEST);

        assertEquals(URI.create("https://site.example"), preview.origin());
        assertThrows(
                IllegalArgumentException.class,
                () -> new PrivateNetworkGrantPreview(
                        WORKSPACE,
                        PrivateNetworkPurpose.SITE,
                        URI.create("https://site.example"),
                        Set.of(),
                        EXPIRES,
                        DIGEST));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PrivateNetworkGrantPreview(
                        WORKSPACE,
                        PrivateNetworkPurpose.SITE,
                        URI.create("https://site.example"),
                        Set.of("192.168.1.1"),
                        EXPIRES,
                        "wrong"));
    }

    @Test
    void 无人值守授权和草稿限制次数版本摘要与时间() {
        UnattendedToolGrant grant = unattendedGrant(3);
        UnattendedToolGrantDraft draft = draft(3);

        assertEquals(3, grant.maximumUses());
        assertEquals(Duration.ofHours(1), draft.validity());
        assertThrows(IllegalArgumentException.class, () -> unattendedGrant(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UnattendedToolGrantDraft(
                        WORKSPACE,
                        "schedule",
                        2,
                        tool(),
                        4,
                        DIGEST,
                        new CanonicalPayload("{\"message\":\"fixed\"}"),
                        Set.of("bad field"),
                        1,
                        Duration.ofHours(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UnattendedToolGrant(
                        "grant",
                        1,
                        SecurityGrantState.ACTIVE,
                        WORKSPACE,
                        "schedule",
                        2,
                        tool(),
                        4,
                        DIGEST,
                        new CanonicalPayload("{}"),
                        Set.of(),
                        1,
                        CREATED,
                        CREATED,
                        CREATED));
    }

    @Test
    void 使用余额与调用冻结身份拒绝不一致输入() {
        UnattendedToolGrant grant = unattendedGrant(3);
        UnattendedToolGrantStatus status = new UnattendedToolGrantStatus(grant, 1, 2);
        UnattendedToolInvocation invocation = invocation();

        assertEquals(2, status.remainingUses());
        assertEquals("invocation", invocation.invocationId());
        assertThrows(IllegalArgumentException.class, () -> new UnattendedToolGrantStatus(grant, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new UnattendedToolGrantStatus(grant, -1, 4));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UnattendedToolInvocation(
                        WORKSPACE,
                        "grant",
                        0,
                        "schedule",
                        2,
                        tool(),
                        4,
                        DIGEST,
                        new CanonicalPayload("{}"),
                        "invocation"));
    }

    @Test
    void 权限决策要求步骤和拒绝原因严格一致() {
        PermissionDecisionTrace.Step allowedStep =
                new PermissionDecisionTrace.Step("system-ceiling", 0, true, "未命中永久拒绝范围");
        PermissionDecisionTrace allowed = new PermissionDecisionTrace(
                "trace",
                WORKSPACE,
                SecurityGrantKind.PRIVATE_NETWORK,
                "grant",
                "private-network/connect",
                "https://example.com",
                true,
                List.of(allowedStep),
                Optional.empty(),
                CREATED);
        PermissionDecisionTrace denied = new PermissionDecisionTrace(
                "trace-denied",
                WORKSPACE,
                SecurityGrantKind.UNATTENDED_TOOL,
                "tool-grant",
                "schedule/tool-call",
                "extension:notify@1",
                false,
                List.of(new PermissionDecisionTrace.Step("quota", 1, false, "额度耗尽")),
                Optional.of("额度耗尽"),
                CREATED);

        assertTrue(allowed.allowed());
        assertEquals("额度耗尽", denied.denialReason().orElseThrow());
        assertEquals(3, UnattendedInvocationOutcome.values().length);
        assertEquals(2, SecurityGrantState.values().length);
        assertEquals(2, SecurityGrantKind.values().length);
        assertEquals(2, PrivateNetworkPurpose.values().length);
    }

    @Test
    void 权限决策拒绝空步骤矛盾结果和无效来源() {
        PermissionDecisionTrace.Step allowedStep =
                new PermissionDecisionTrace.Step("system-ceiling", 0, true, "未命中永久拒绝范围");
        assertThrows(
                IllegalArgumentException.class,
                () -> new PermissionDecisionTrace(
                        "trace",
                        WORKSPACE,
                        SecurityGrantKind.PRIVATE_NETWORK,
                        "grant",
                        "connect",
                        "origin",
                        true,
                        List.of(allowedStep),
                        Optional.of("不应存在"),
                        CREATED));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PermissionDecisionTrace(
                        "trace",
                        WORKSPACE,
                        SecurityGrantKind.PRIVATE_NETWORK,
                        "grant",
                        "connect",
                        "origin",
                        false,
                        List.of(allowedStep),
                        Optional.empty(),
                        CREATED));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PermissionDecisionTrace(
                        "trace",
                        WORKSPACE,
                        SecurityGrantKind.PRIVATE_NETWORK,
                        "grant",
                        "connect",
                        "origin",
                        true,
                        List.of(),
                        Optional.empty(),
                        CREATED));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PermissionDecisionTrace.Step("bad source", 0, true, "detail"));
    }

    private static PrivateNetworkGrant privateGrant(URI origin) {
        return new PrivateNetworkGrant(
                "grant",
                1,
                SecurityGrantState.ACTIVE,
                WORKSPACE,
                PrivateNetworkPurpose.MCP,
                origin,
                Set.of("10.0.0.1"),
                EXPIRES,
                CREATED,
                CREATED);
    }

    private static UnattendedToolGrant unattendedGrant(int maximumUses) {
        return new UnattendedToolGrant(
                "grant",
                1,
                SecurityGrantState.ACTIVE,
                WORKSPACE,
                "schedule",
                2,
                tool(),
                4,
                DIGEST,
                new CanonicalPayload("{\"message\":\"fixed\"}"),
                Set.of("message"),
                maximumUses,
                EXPIRES,
                CREATED,
                CREATED);
    }

    private static UnattendedToolGrantDraft draft(int maximumUses) {
        return new UnattendedToolGrantDraft(
                WORKSPACE,
                "schedule",
                2,
                tool(),
                4,
                DIGEST,
                new CanonicalPayload("{\"message\":\"fixed\"}"),
                Set.of("message"),
                maximumUses,
                Duration.ofHours(1));
    }

    private static UnattendedToolInvocation invocation() {
        return new UnattendedToolInvocation(
                WORKSPACE,
                "grant",
                1,
                "schedule",
                2,
                tool(),
                4,
                DIGEST,
                new CanonicalPayload("{\"message\":\"value\"}"),
                "invocation");
    }

    private static ToolIdentity tool() {
        return new ToolIdentity("schedule-extension", "notify", 3);
    }
}
