package com.javaclaw.server.security.grant;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.PermissionDecisionTrace;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantPreview;
import com.javaclaw.api.PrivateNetworkGrantRef;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.SecurityGrantKind;
import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.SecurityGrantRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGrantServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private MutableClock clock;
    private Workspace workspace;

    @BeforeEach
    void 初始化全新DataV6与Workspace() {
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        json = new CanonicalJson();
        clock = new MutableClock(NOW);
        CoreCommandService core = new CoreCommandService(database, json, clock);
        Path root = temporaryDirectory.resolve("workspace").toAbsolutePath();
        CoreRpcContracts.WorkspaceCreatePayload payload = new CoreRpcContracts.WorkspaceCreatePayload("Security", root);
        workspace = core.createWorkspace(identity("workspace/create", "workspace", 0, payload), "Security", root);
    }

    @Test
    void 私网地址只允许显式RFC1918或ULA且永久拒绝保留范围() {
        assertEquals(Set.of("10.0.0.1"), PrivateNetworkAddressPolicy.requireGrantable(Set.of("10.0.0.1")));
        assertEquals(
                1,
                PrivateNetworkAddressPolicy.requireGrantable(Set.of("fd12:3456::1"))
                        .size());

        assertThrows(SecurityException.class, () -> PrivateNetworkAddressPolicy.requireGrantable(Set.of("127.0.0.1")));
        assertThrows(
                SecurityException.class, () -> PrivateNetworkAddressPolicy.requireGrantable(Set.of("169.254.169.254")));
        assertThrows(SecurityException.class, () -> PrivateNetworkAddressPolicy.requireGrantable(Set.of("224.0.0.1")));
        assertThrows(SecurityException.class, () -> PrivateNetworkAddressPolicy.requireGrantable(Set.of("::1")));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrivateNetworkAddressPolicy.requireGrantable(Set.of("203.0.113.10")));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrivateNetworkAddressPolicy.requireGrantable(Set.of("private.example")));
    }

    @Test
    void 私网地址解析拒绝非规范空值越界和所有云元数据地址() {
        assertThrows(IllegalArgumentException.class, () -> PrivateNetworkAddressPolicy.requireGrantable(null));
        assertThrows(IllegalArgumentException.class, () -> PrivateNetworkAddressPolicy.requireGrantable(Set.of()));
        for (String invalid : List.of("", "10.0.0.1%eth0", "10.0.0", "010.0.0.1", "256.0.0.1", "fd00::name")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PrivateNetworkAddressPolicy.requireGrantable(Set.of(invalid)));
        }
        for (String forbidden : List.of(
                "0.0.0.0",
                "169.254.1.1",
                "100.100.100.200",
                "168.63.129.16",
                "::",
                "fe80::1",
                "ff02::1",
                "fd00:ec2::254")) {
            assertThrows(
                    SecurityException.class, () -> PrivateNetworkAddressPolicy.requireGrantable(Set.of(forbidden)));
        }
    }

    @Test
    void 私网地址边界只接受RFC1918或ULA并允许同次解析含公网地址() {
        for (String address :
                List.of("10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.0.1", "fc00::1", "fdff::1")) {
            assertEquals(
                    1,
                    PrivateNetworkAddressPolicy.requireGrantable(Set.of(address))
                            .size());
        }
        for (String address : List.of("9.255.255.255", "172.15.255.255", "172.32.0.1", "192.167.1.1", "fe00::1")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PrivateNetworkAddressPolicy.requireGrantable(Set.of(address)));
        }
        assertEquals(
                Set.of("10.0.0.1", "203.0.113.10"),
                PrivateNetworkAddressPolicy.requireGrantable(Set.of("10.0.0.1", "203.0.113.10")));
    }

    @Test
    void 私网授权完成预览幂等创建精确决策历史与实时撤销() {
        PrivateNetworkGrantService service = new PrivateNetworkGrantService(database, json, clock);
        PrivateNetworkGrantPreview preview = service.preview(
                workspace.id(),
                PrivateNetworkPurpose.MCP,
                URI.create("https://MCP.EXAMPLE:443/"),
                Set.of("10.0.0.2"),
                Optional.empty());
        SecurityGrantRpcContracts.PrivateNetworkCreatePayload payload =
                new SecurityGrantRpcContracts.PrivateNetworkCreatePayload(preview);
        CommandIdentity create = identity("privateNetworkGrant/create", "private-create", 0, payload);

        PrivateNetworkGrant grant = service.create(create, preview);

        assertEquals(grant, service.create(create, preview));
        assertEquals(URI.create("https://mcp.example"), grant.origin());
        assertEquals(NOW.plus(Duration.ofHours(1)), grant.expiresAt());
        assertEquals(List.of(grant), service.listLatest(workspace.id()));
        PermissionDecisionTrace allowed = service.evaluate(
                grant.id(),
                grant.revision(),
                workspace.id(),
                PrivateNetworkPurpose.MCP,
                URI.create("https://mcp.example"),
                Set.of("10.0.0.2"));
        assertTrue(allowed.allowed());

        PermissionDecisionTrace changedDns = service.evaluate(
                grant.id(),
                grant.revision(),
                workspace.id(),
                PrivateNetworkPurpose.MCP,
                URI.create("https://mcp.example"),
                Set.of("10.0.0.3"));
        assertFalse(changedDns.allowed());
        assertTrue(changedDns.denialReason().orElseThrow().contains("DNS"));

        SecurityGrantRpcContracts.GrantRevokePayload revokePayload =
                new SecurityGrantRpcContracts.GrantRevokePayload(grant.id());
        PrivateNetworkGrant revoked =
                service.revoke(identity("privateNetworkGrant/revoke", "private-revoke", 1, revokePayload), grant.id());
        assertEquals(SecurityGrantState.REVOKED, revoked.state());
        assertEquals(2, revoked.revision());
        assertEquals(List.of(grant, revoked), service.history(grant.id()));
        assertFalse(service.evaluate(
                        grant.id(),
                        grant.revision(),
                        workspace.id(),
                        PrivateNetworkPurpose.MCP,
                        URI.create("https://mcp.example"),
                        Set.of("10.0.0.2"))
                .allowed());

        SecurityGrantAuditService audit = new SecurityGrantAuditService(database, json);
        List<PermissionDecisionTrace> traces =
                audit.list(workspace.id(), Optional.of(SecurityGrantKind.PRIVATE_NETWORK), Optional.of(grant.id()), 10);
        assertEquals(3, traces.size());
        assertFalse(traces.getFirst().allowed());
        assertThrows(
                PersistenceException.class,
                () -> service.revoke(
                        identity("privateNetworkGrant/revoke", "private-stale", 1, revokePayload), grant.id()));
    }

    @Test
    void 私网预览摘要期限与到期判断不能被绕过() {
        PrivateNetworkGrantService service = new PrivateNetworkGrantService(database, json, clock);
        assertThrows(
                IllegalArgumentException.class,
                () -> service.preview(
                        workspace.id(),
                        PrivateNetworkPurpose.SITE,
                        URI.create("http://site.example"),
                        Set.of("192.168.1.1"),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.preview(
                        workspace.id(),
                        PrivateNetworkPurpose.SITE,
                        URI.create("https://site.example"),
                        Set.of("192.168.1.1"),
                        Optional.of(Duration.ofHours(25))));

        PrivateNetworkGrantPreview valid = service.preview(
                workspace.id(),
                PrivateNetworkPurpose.SITE,
                URI.create("https://site.example"),
                Set.of("192.168.1.1"),
                Optional.of(Duration.ofMinutes(1)));
        PrivateNetworkGrantPreview tampered = new PrivateNetworkGrantPreview(
                valid.workspaceId(),
                valid.purpose(),
                URI.create("https://other.example"),
                valid.dnsAddresses(),
                valid.expiresAt(),
                valid.confirmationDigest());
        assertThrows(
                PersistenceException.class,
                () -> service.create(
                        identity(
                                "privateNetworkGrant/create",
                                "tampered",
                                0,
                                new SecurityGrantRpcContracts.PrivateNetworkCreatePayload(tampered)),
                        tampered));
        clock.advance(Duration.ofMinutes(2));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.create(
                        identity(
                                "privateNetworkGrant/create",
                                "expired",
                                0,
                                new SecurityGrantRpcContracts.PrivateNetworkCreatePayload(valid)),
                        valid));
    }

    @Test
    void 私网绑定端口只返回有效用途并校验精确版本Workspace与Origin() {
        PrivateNetworkGrantService service = new PrivateNetworkGrantService(database, json, clock);
        PrivateNetworkGrantPreview preview = service.preview(
                workspace.id(),
                PrivateNetworkPurpose.SITE,
                URI.create("https://site.example"),
                Set.of("192.168.1.8"),
                Optional.of(Duration.ofMinutes(5)));
        PrivateNetworkGrant grant =
                service.create(identity("privateNetworkGrant/create", "site-bindable", 0, preview), preview);
        PrivateNetworkGrantRef reference = new PrivateNetworkGrantRef(grant.id(), grant.revision());

        assertEquals(List.of(grant), service.available(workspace.id(), PrivateNetworkPurpose.SITE));
        assertTrue(service.available(workspace.id(), PrivateNetworkPurpose.MCP).isEmpty());
        assertEquals(
                grant,
                service.requireBindable(
                        reference,
                        workspace.id(),
                        PrivateNetworkPurpose.SITE,
                        URI.create("https://SITE.example:443/")));
        assertThrows(
                PersistenceException.class,
                () -> service.requireBindable(
                        new PrivateNetworkGrantRef(grant.id(), grant.revision() + 1),
                        workspace.id(),
                        PrivateNetworkPurpose.SITE,
                        grant.origin()));
        assertThrows(
                PersistenceException.class,
                () -> service.requireBindable(
                        reference, workspace.id(), PrivateNetworkPurpose.SITE, URI.create("https://other.example")));

        clock.advance(Duration.ofMinutes(6));
        assertTrue(service.available(workspace.id(), PrivateNetworkPurpose.SITE).isEmpty());
        assertThrows(
                PersistenceException.class,
                () -> service.requireBindable(reference, workspace.id(), PrivateNetworkPurpose.SITE, grant.origin()));
    }

    @Test
    void 私网授权创建拒绝不存在Workspace错误期限和非创建Revision() {
        PrivateNetworkGrantService service = new PrivateNetworkGrantService(database, json, clock);
        PrivateNetworkGrantPreview missingWorkspace = service.preview(
                WorkspaceId.random(),
                PrivateNetworkPurpose.MCP,
                URI.create("https://mcp.example"),
                Set.of("10.0.0.1"),
                Optional.empty());
        assertThrows(
                PersistenceException.class,
                () -> service.create(
                        identity("privateNetworkGrant/create", "missing-workspace", 0, missingWorkspace),
                        missingWorkspace));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.preview(
                        workspace.id(),
                        PrivateNetworkPurpose.MCP,
                        URI.create("https://mcp.example"),
                        Set.of("10.0.0.1"),
                        Optional.of(Duration.ZERO)));
        PrivateNetworkGrantPreview preview = service.preview(
                workspace.id(),
                PrivateNetworkPurpose.MCP,
                URI.create("https://mcp.example"),
                Set.of("10.0.0.1"),
                Optional.of(Duration.ofMinutes(5)));
        assertThrows(
                PersistenceException.class,
                () -> service.create(
                        identity("privateNetworkGrant/create", "bad-create-revision", 1, preview), preview));
    }

    @Test
    void 私网授权强制入口拒绝用途漂移不存在资源和已撤销Grant() {
        PrivateNetworkGrantService service = new PrivateNetworkGrantService(database, json, clock);
        PrivateNetworkGrantPreview preview = service.preview(
                workspace.id(),
                PrivateNetworkPurpose.MCP,
                URI.create("https://mcp.example"),
                Set.of("10.0.0.1"),
                Optional.of(Duration.ofMinutes(5)));
        PrivateNetworkGrant grant =
                service.create(identity("privateNetworkGrant/create", "force-create", 0, preview), preview);

        assertTrue(service.requireAuthorized(
                        grant.id(),
                        grant.revision(),
                        workspace.id(),
                        PrivateNetworkPurpose.MCP,
                        grant.origin(),
                        grant.dnsAddresses())
                .allowed());
        assertThrows(
                SecurityException.class,
                () -> service.requireAuthorized(
                        grant.id(),
                        grant.revision(),
                        workspace.id(),
                        PrivateNetworkPurpose.SITE,
                        grant.origin(),
                        grant.dnsAddresses()));
        assertFalse(service.evaluate(
                        "missing", 1, workspace.id(), PrivateNetworkPurpose.MCP, grant.origin(), grant.dnsAddresses())
                .allowed());
        assertThrows(
                IllegalArgumentException.class,
                () -> service.evaluate(
                        grant.id(),
                        0,
                        workspace.id(),
                        PrivateNetworkPurpose.MCP,
                        grant.origin(),
                        grant.dnsAddresses()));

        PrivateNetworkGrant revoked =
                service.revoke(identity("privateNetworkGrant/revoke", "force-revoke", 1, grant), grant.id());
        assertThrows(
                PersistenceException.class,
                () -> service.revoke(
                        identity("privateNetworkGrant/revoke", "already-revoked", revoked.revision(), revoked),
                        revoked.id()));
    }

    @Test
    void 安全授权审计拒绝无界查询和非法Grant标识() {
        SecurityGrantAuditService audit = new SecurityGrantAuditService(database, json);

        assertThrows(
                IllegalArgumentException.class,
                () -> audit.list(workspace.id(), Optional.empty(), Optional.empty(), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> audit.list(workspace.id(), Optional.empty(), Optional.empty(), 501));
        assertThrows(
                IllegalArgumentException.class,
                () -> audit.list(workspace.id(), Optional.empty(), Optional.of("bad id"), 10));
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, revision, json.encode(payload)), json);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
