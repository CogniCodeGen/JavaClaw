package com.javaclaw.server.security.grant;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BrowserGrantContracts.Grant;
import com.javaclaw.builtin.contracts.BrowserGrantContracts.GrantRef;
import com.javaclaw.builtin.contracts.BrowserGrantContracts.Preview;
import com.javaclaw.builtin.contracts.BrowserGrantContracts.Snapshot;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserGrantServiceTest {
    private static final URI ORIGIN = URI.create("https://browser.example");

    @TempDir
    Path directory;

    private BrowserGrantFixture fixture;

    @BeforeEach
    void 创建本地数据与独立对话() {
        fixture = new BrowserGrantFixture(directory);
    }

    @Test
    void 预览本身不授权且精确来源确认可幂等恢复() {
        Preview preview =
                fixture.service.preview(fixture.workspace, fixture.thread, URI.create("https://BROWSER.example:443/"));
        assertTrue(fixture.service
                .currentOrigins(fixture.workspace, fixture.thread)
                .isEmpty());
        assertThrows(
                SecurityException.class,
                () -> fixture.service.requireHumanAuthorized(fixture.workspace, fixture.thread, ORIGIN));
        var identity = fixture.identity("site/browser.grant.confirm", 0, preview);
        Grant grant = fixture.service.confirm(identity, preview);
        assertEquals(ORIGIN, grant.origin());
        assertEquals(grant, fixture.service.confirm(identity, preview));
        assertEquals(grant, fixture.service.requireHumanAuthorized(fixture.workspace, fixture.thread, ORIGIN));
        assertThrows(
                PersistenceException.class,
                () -> fixture.service.confirm(fixture.identity("site/browser.grant.confirm", 0, preview), preview));
        assertFalse(fixture.service.audit(fixture.workspace, fixture.thread, 10).stream()
                .allMatch(BrowserGrantService.Decision::allowed));
    }

    @Test
    void 新来源即使在首次冻结前获批也不能进入既有Turn且重启保留快照() {
        Grant first = confirm(ORIGIN);
        fixture.clock.advance(Duration.ofSeconds(1));
        TurnId turn = fixture.turn();
        fixture.clock.advance(Duration.ofSeconds(1));
        URI later = URI.create("https://later.example");
        confirm(later);
        Snapshot frozen = fixture.service.freeze(fixture.workspace, fixture.thread, turn);
        assertEquals(Map.of(ORIGIN, new GrantRef(first.id(), 1)), frozen.grants());
        assertThrows(SecurityException.class, () -> fixture.service.requireAuthorized(frozen, later));
        BrowserGrantService restarted = new BrowserGrantService(fixture.database, fixture.json, fixture.clock);
        assertEquals(frozen, restarted.freeze(fixture.workspace, fixture.thread, turn));
        assertEquals(first, restarted.requireAuthorized(frozen, ORIGIN));
        fixture.finish(turn);
        fixture.clock.advance(Duration.ofSeconds(1));
        assertTrue(restarted
                .freeze(fixture.workspace, fixture.thread, fixture.turn())
                .origins()
                .contains(later));
    }

    @Test
    void 相同时间戳的新授权保守排除并且伪造快照不能扩大权限() {
        TurnId turn = fixture.turn();
        Grant grant = confirm(ORIGIN);
        Snapshot empty = fixture.service.freeze(fixture.workspace, fixture.thread, turn);
        assertTrue(empty.origins().isEmpty());
        Snapshot forged = new Snapshot(
                empty.snapshotId(),
                fixture.workspace,
                fixture.thread,
                turn,
                Map.of(ORIGIN, new GrantRef(grant.id(), grant.revision())),
                empty.frozenAt());
        assertThrows(SecurityException.class, () -> fixture.service.requireAuthorized(forged, ORIGIN));
        Snapshot invented = new Snapshot(
                UUID.randomUUID().toString(),
                fixture.workspace,
                fixture.thread,
                turn,
                forged.grants(),
                empty.frozenAt());
        assertThrows(SecurityException.class, () -> fixture.service.requireAuthorized(invented, ORIGIN));
    }

    @Test
    void 系统时钟回拨仍不能把后确认授权装入已经创建的Turn() {
        fixture.clock.advance(Duration.ofSeconds(10));
        TurnId turn = fixture.turn();
        fixture.clock.advance(Duration.ofSeconds(-9));
        Grant grant = confirm(ORIGIN);
        assertTrue(grant.createdAt().isAfter(fixture.clock.instant()));
        assertTrue(fixture.service
                .freeze(fixture.workspace, fixture.thread, turn)
                .origins()
                .isEmpty());
    }

    @Test
    void 撤销跨服务实例即时生效且重授不能恢复旧快照() throws Exception {
        Grant grant = confirm(ORIGIN);
        fixture.clock.advance(Duration.ofSeconds(1));
        Snapshot snapshot = fixture.service.freeze(fixture.workspace, fixture.thread, fixture.turn());
        BrowserGrantService restarted = new BrowserGrantService(fixture.database, fixture.json, fixture.clock);
        AtomicInteger changes = new AtomicInteger();
        try (var ignored = fixture.service.onChanged(scope -> changes.incrementAndGet())) {
            var identity = fixture.identity("site/browser.grant.revoke", 1, grant.id());
            Grant revoked = fixture.service.revoke(identity, grant.id());
            assertEquals(SecurityGrantState.REVOKED, revoked.state());
            assertEquals(2, revoked.revision());
            assertEquals(revoked, fixture.service.revoke(identity, grant.id()));
            assertTrue(changes.get() >= 1);
            assertThrows(SecurityException.class, () -> restarted.requireAuthorized(snapshot, ORIGIN));
            assertThrows(
                    SecurityException.class,
                    () -> restarted.requireHumanAuthorized(fixture.workspace, fixture.thread, ORIGIN));
            fixture.clock.advance(Duration.ofSeconds(1));
            confirm(ORIGIN);
            assertThrows(SecurityException.class, () -> restarted.requireAuthorized(snapshot, ORIGIN));
        }
        assertTrue(fixture.service.audit(fixture.workspace, fixture.thread, 20).stream()
                .anyMatch(decision -> decision.operation().equals("revoke")));
    }

    @Test
    void 作用域与HTTPS边界拒绝跨对话和不精确来源() {
        confirm(ORIGIN);
        var another = fixture.thread();
        assertThrows(
                SecurityException.class,
                () -> fixture.service.requireHumanAuthorized(fixture.workspace, another, ORIGIN));
        assertThrows(
                PersistenceException.class,
                () -> fixture.service.preview(WorkspaceId.random(), fixture.thread, ORIGIN));
        for (String uri : new String[] {
            "http://browser.example",
            "https://browser.example/private",
            "https://u:p@browser.example",
            "https://*.example",
            "https://browser.example?token=secret"
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.service.preview(fixture.workspace, fixture.thread, URI.create(uri)));
        }
        fixture.clock.advance(Duration.ofSeconds(1));
        TurnId turn = fixture.turn();
        assertThrows(PersistenceException.class, () -> fixture.service.freeze(fixture.workspace, another, turn));
    }

    @Test
    void 预览过期或修改内容不能确认且错误撤销版本不改变授权() {
        Preview original = fixture.service.preview(fixture.workspace, fixture.thread, ORIGIN);
        Preview modified = new Preview(
                fixture.workspace,
                fixture.thread,
                URI.create("https://different.example"),
                original.expiresAt(),
                original.digest());
        assertThrows(
                PersistenceException.class,
                () -> fixture.service.confirm(fixture.identity("site/browser.grant.confirm", 0, modified), modified));
        fixture.clock.advance(Duration.ofMinutes(6));
        assertThrows(
                PersistenceException.class,
                () -> fixture.service.confirm(fixture.identity("site/browser.grant.confirm", 0, original), original));
        Grant grant = confirm(ORIGIN);
        assertThrows(
                PersistenceException.class,
                () -> fixture.service.revoke(fixture.identity("site/browser.grant.revoke", 2, grant.id()), grant.id()));
        assertEquals(grant, fixture.service.requireHumanAuthorized(fixture.workspace, fixture.thread, ORIGIN));
    }

    private Grant confirm(URI origin) {
        Preview preview = fixture.service.preview(fixture.workspace, fixture.thread, origin);
        return fixture.service.confirm(fixture.identity("site/browser.grant.confirm", 0, preview), preview);
    }
}
