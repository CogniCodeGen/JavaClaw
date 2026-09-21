package com.javaclaw.server.extension;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.browser.client.InteractiveBrowserNetworkExchange;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.server.TurnContractFixtures;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.security.BrowserBrokerResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteRegistrationNetworkTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void 公网来源授权不能代替私网精确DNS授权且撤销立即生效() throws Exception {
        try (var fixture = new SiteRegistrationFixture(temporaryDirectory.resolve("data-v6"))) {
            var workspace = new CoreCommandService(fixture.database, fixture.json, fixture.clock)
                    .createWorkspace(
                            SiteRegistrationFixture.identity("workspace"),
                            "Registration",
                            temporaryDirectory.resolve("workspace"))
                    .id();
            var template = session(fixture);
            var session = new SiteRegistrationSession(workspace, template.id, template.permission, template.lease);
            var network = new SiteRegistrationNetwork(
                            fixture.grants,
                            (request, permission, cancellation, privateAuth, realtime) -> {
                                realtime.authorize();
                                privateAuth.authorize(SiteRegistrationFixture.ORIGIN, Set.of("192.168.20.10"));
                                return new BrowserBrokerResponse(200, Map.of(), new byte[0], false);
                            },
                            fixture.clock)
                    .callback(session);
            var request = new BrowserContracts.NetworkRequest(SiteRegistrationFixture.ORIGIN, "GET", Map.of());
            assertThrows(
                    SecurityException.class, () -> network.exchange(request, new byte[0], new CancellationSource()));
            var preview = fixture.grants.preview(
                    session.workspace,
                    PrivateNetworkPurpose.SITE,
                    SiteRegistrationFixture.ORIGIN,
                    Set.of("192.168.20.10"),
                    Optional.empty());
            var grant = fixture.grants.create(SiteRegistrationFixture.identity("grant"), preview);
            assertEquals(
                    200,
                    network.exchange(request, new byte[0], new CancellationSource())
                            .response()
                            .statusCode());
            fixture.grants.revoke(
                    new CommandIdentity("grant/revoke", "revoke", grant.revision(), "b".repeat(64)), grant.id());
            assertThrows(
                    SecurityException.class, () -> network.exchange(request, new byte[0], new CancellationSource()));
        }
    }

    @Test
    void 旧代次在途请求不能使用追加来源后的新租约() throws Exception {
        try (var fixture = new SiteRegistrationFixture(temporaryDirectory.resolve("data-v6"))) {
            var session = session(fixture);
            var lease = session.lease;
            var network = new SiteRegistrationNetwork(
                            fixture.grants,
                            (request, permission, cancellation, privateAuth, realtime) -> {
                                session.lease = new BrowserContracts.AccessLease(
                                        BrowserContracts.ControlMode.HUMAN,
                                        "next",
                                        2,
                                        lease.expiresAt(),
                                        lease.allowedOrigins());
                                assertTrue(cancellation.isCancelled());
                                assertTrue(cancellation.reason().isPresent());
                                realtime.authorize();
                                return new BrowserBrokerResponse(200, Map.of(), new byte[0], false);
                            },
                            fixture.clock)
                    .callback(session);
            var request = new BrowserContracts.NetworkRequest(SiteRegistrationFixture.ORIGIN, "GET", Map.of());
            assertThrows(
                    SecurityException.class, () -> network.exchange(request, new byte[0], new CancellationSource()));
            assertFalse(session.cancellation(session.lease, fixture.clock, new CancellationSource())
                    .isCancelled());
        }
    }

    @Test
    void 来源发现有界且过期旧代次和已授权通知被忽略() throws Exception {
        try (var fixture = new SiteRegistrationFixture(temporaryDirectory.resolve("data-v6"))) {
            var session = session(fixture);
            var network = new SiteRegistrationNetwork(fixture.grants, fixture.broker, fixture.clock).callback(session);
            network.deniedOrigin(SiteRegistrationFixture.ORIGIN, 1);
            network.deniedOrigin(URI.create("https://other.example.com"), 2);
            assertTrue(session.pending.isEmpty());
            for (int index = 0; index < 140; index++) {
                network.deniedOrigin(URI.create("https://host" + index + ".example.com"), 1);
            }
            assertEquals(128, session.pending.size());
            session.pending.clear();
            fixture.clock.advance(Duration.ofMinutes(16));
            network.deniedOrigin(URI.create("https://other.example.com"), 1);
            assertTrue(session.pending.isEmpty());
        }
    }

    @Test
    void 旧撤销和过期授权与有效授权并存时选择完整DNS匹配的有效版本() throws Exception {
        try (var fixture = new SiteRegistrationFixture(temporaryDirectory.resolve("data-v6"))) {
            var workspace = new CoreCommandService(fixture.database, fixture.json, fixture.clock)
                    .createWorkspace(
                            SiteRegistrationFixture.identity("workspace"),
                            "Registration",
                            temporaryDirectory.resolve("workspace"))
                    .id();
            Set<String> addresses = Set.of("192.168.20.10");
            grant(fixture, workspace, "expired", addresses, Duration.ofMinutes(1));
            fixture.clock.advance(Duration.ofMinutes(2));
            var first = grant(fixture, workspace, "first", addresses, Duration.ofHours(1));
            var second = grant(fixture, workspace, "second", addresses, Duration.ofHours(1));
            // 保证撤销记录在有效记录之前，复现旧 findFirst 错选历史授权的失败。
            var revoked = first.id().compareTo(second.id()) < 0 ? first : second;
            var active = revoked == first ? second : first;
            fixture.grants.revoke(
                    new CommandIdentity("grant/revoke", "revoke", revoked.revision(), "b".repeat(64)), revoked.id());
            grant(fixture, workspace, "different-dns", Set.of("192.168.20.10", "192.168.20.11"), Duration.ofHours(1));
            var template = session(fixture);
            var session = new SiteRegistrationSession(workspace, template.id, template.permission, template.lease);
            var network = privateNetwork(fixture, session, addresses);
            var request = new BrowserContracts.NetworkRequest(SiteRegistrationFixture.ORIGIN, "GET", Map.of());
            assertEquals(
                    200,
                    network.exchange(request, new byte[0], new CancellationSource())
                            .response()
                            .statusCode());
            fixture.grants.revoke(
                    new CommandIdentity("grant/revoke", "revoke-active", active.revision(), "c".repeat(64)),
                    active.id());
            // 仍活动但 DNS 是超集的授权不能替代本次完整集合。
            assertThrows(
                    SecurityException.class, () -> network.exchange(request, new byte[0], new CancellationSource()));
        }
    }

    private InteractiveBrowserNetworkExchange privateNetwork(
            SiteRegistrationFixture fixture, SiteRegistrationSession session, Set<String> addresses) {
        return new SiteRegistrationNetwork(
                        fixture.grants,
                        (request, permission, cancellation, privateAuth, realtime) -> {
                            realtime.authorize();
                            privateAuth.authorize(SiteRegistrationFixture.ORIGIN, addresses);
                            return new BrowserBrokerResponse(200, Map.of(), new byte[0], false);
                        },
                        fixture.clock)
                .callback(session);
    }

    private PrivateNetworkGrant grant(
            SiteRegistrationFixture fixture,
            WorkspaceId workspace,
            String key,
            Set<String> addresses,
            Duration validity) {
        var preview = fixture.grants.preview(
                workspace,
                PrivateNetworkPurpose.SITE,
                SiteRegistrationFixture.ORIGIN,
                addresses,
                Optional.of(validity));
        return fixture.grants.create(SiteRegistrationFixture.identity(key), preview);
    }

    private SiteRegistrationSession session(SiteRegistrationFixture fixture) {
        var lease = new BrowserContracts.AccessLease(
                BrowserContracts.ControlMode.HUMAN,
                UUID.randomUUID().toString(),
                1,
                fixture.clock.instant().plus(Duration.ofMinutes(15)),
                Set.of(SiteRegistrationFixture.ORIGIN));
        return new SiteRegistrationSession(
                SiteRegistrationFixture.WORKSPACE,
                UUID.randomUUID().toString(),
                TurnContractFixtures.TOOL_CATALOG.permissionCeiling(),
                lease);
    }
}
