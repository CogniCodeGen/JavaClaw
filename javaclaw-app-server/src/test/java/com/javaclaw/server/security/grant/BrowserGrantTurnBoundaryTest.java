package com.javaclaw.server.security.grant;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.BrowserGrantContracts.Grant;
import com.javaclaw.builtin.contracts.BrowserGrantContracts.GrantRef;
import com.javaclaw.server.TurnContractFixtures;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.TurnStartRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserGrantTurnBoundaryTest {
    private static final URI ORIGIN = URI.create("https://browser.example");

    @TempDir
    Path directory;

    private BrowserGrantFixture fixture;

    @BeforeEach
    void 初始化固定时钟和真实Core事务() {
        fixture = new BrowserGrantFixture(directory);
    }

    @Test
    void 静止时钟下批准的新来源只进入后继且不抬高预算起始时间() {
        TurnId parent = fixture.turn();
        Grant grant = confirm(ORIGIN);
        assertTrue(grant.createdAt().isAfter(fixture.clock.instant()));
        assertTrue(fixture.service
                .freeze(fixture.workspace, fixture.thread, parent)
                .origins()
                .isEmpty());
        fixture.finish(parent);
        AgentTurn child = continueTurn(parent);
        assertEquals(fixture.clock.instant(), child.createdAt());
        var frozen = fixture.service.freeze(fixture.workspace, fixture.thread, child.id());
        assertEquals(Map.of(ORIGIN, new GrantRef(grant.id(), grant.revision())), frozen.grants());
        assertEquals(grant, fixture.service.requireAuthorized(frozen, ORIGIN));
    }

    @Test
    void 回拨时钟后授权与多段续接按事务次序固定且幂等恢复不补入后来来源() {
        fixture.clock.advance(Duration.ofSeconds(10));
        TurnId parent = fixture.turn();
        fixture.clock.advance(Duration.ofSeconds(-9));
        Grant grant = confirm(ORIGIN);
        fixture.finish(parent);
        var request = request(parent);
        var identity = fixture.identity("turn/start", 0, request);
        AgentTurn child = fixture.core.startTurn(identity, request);
        var frozen = fixture.service.freeze(fixture.workspace, fixture.thread, child.id());
        assertEquals(grant, fixture.service.requireAuthorized(frozen, ORIGIN));
        URI later = URI.create("https://later.example");
        confirm(later);
        var restartedCore = new CoreCommandService(fixture.database, fixture.json, fixture.clock);
        var restartedGrants = new BrowserGrantService(fixture.database, fixture.json, fixture.clock);
        assertEquals(child, restartedCore.startTurn(identity, request));
        assertEquals(child, restartedCore.recoverTurnStart(identity).orElseThrow());
        assertEquals(frozen, restartedGrants.freeze(fixture.workspace, fixture.thread, child.id()));
        assertThrows(SecurityException.class, () -> restartedGrants.requireAuthorized(frozen, later));
        fixture.finish(child.id());
        fixture.clock.advance(Duration.ofSeconds(-5));
        var next = continueTurn(child.id());
        var nextSnapshot = restartedGrants.freeze(fixture.workspace, fixture.thread, next.id());
        assertTrue(nextSnapshot.origins().containsAll(java.util.Set.of(ORIGIN, later)));
        assertEquals(parent, restartedCore.originalInputTurn(next.id()));
    }

    @Test
    void 撤销再批准不会恢复旧版本且重启续接只冻结创建时已提交的新授权() {
        Grant first = confirm(ORIGIN);
        TurnId parent = fixture.turn();
        var old = fixture.service.freeze(fixture.workspace, fixture.thread, parent);
        fixture.service.revoke(fixture.identity("site/browser.grant.revoke", first.revision(), first.id()), first.id());
        fixture.clock.advance(Duration.ofSeconds(-1));
        Grant replacement = confirm(ORIGIN);
        assertNotEquals(first.id(), replacement.id());
        fixture.finish(parent);
        AgentTurn child = continueTurn(parent);
        var restarted = new BrowserGrantService(fixture.database, fixture.json, fixture.clock);
        assertThrows(SecurityException.class, () -> restarted.requireAuthorized(old, ORIGIN));
        var current = restarted.freeze(fixture.workspace, fixture.thread, child.id());
        assertEquals(Map.of(ORIGIN, new GrantRef(replacement.id(), 1)), current.grants());
        assertEquals(replacement, restarted.requireAuthorized(current, ORIGIN));
        fixture.service.revoke(
                fixture.identity("site/browser.grant.revoke", replacement.revision(), replacement.id()),
                replacement.id());
        assertThrows(SecurityException.class, () -> restarted.requireAuthorized(current, ORIGIN));
        assertEquals(current, restarted.freeze(fixture.workspace, fixture.thread, child.id()));
    }

    @Test
    void 创建后尚未首次读取快照时撤销不会被时间回拨隐藏() {
        Grant grant = confirm(ORIGIN);
        TurnId turn = fixture.turn();
        fixture.clock.advance(Duration.ofSeconds(-5));
        fixture.service.revoke(fixture.identity("site/browser.grant.revoke", grant.revision(), grant.id()), grant.id());
        var restarted = new BrowserGrantService(fixture.database, fixture.json, fixture.clock);
        var frozen = restarted.freeze(fixture.workspace, fixture.thread, turn);
        assertEquals(Map.of(ORIGIN, new GrantRef(grant.id(), 1)), frozen.grants());
        assertThrows(SecurityException.class, () -> restarted.requireAuthorized(frozen, ORIGIN));
    }

    @Test
    void 固定时钟撤销后可签发新预览但旧确认幂等恢复不能复活旧授权() {
        var firstPreview = fixture.service.preview(fixture.workspace, fixture.thread, ORIGIN);
        var firstIdentity = fixture.identity("site/browser.grant.confirm", 0, firstPreview);
        Grant first = fixture.service.confirm(firstIdentity, firstPreview);
        fixture.service.revoke(fixture.identity("site/browser.grant.revoke", 1, first.id()), first.id());
        var nextPreview = fixture.service.preview(fixture.workspace, fixture.thread, ORIGIN);
        assertNotEquals(firstPreview.digest(), nextPreview.digest());
        assertTrue(!nextPreview.expiresAt().isAfter(fixture.clock.instant().plus(Duration.ofMinutes(5))));
        assertTrue(nextPreview
                .expiresAt()
                .isAfter(fixture.clock.instant().plus(Duration.ofMinutes(5)).minusMillis(1)));
        var nextIdentity = fixture.identity("site/browser.grant.confirm", 0, nextPreview);
        Grant replacement = fixture.service.confirm(nextIdentity, nextPreview);
        assertNotEquals(first.id(), replacement.id());
        var restarted = new BrowserGrantService(fixture.database, fixture.json, fixture.clock);
        assertEquals(first, restarted.confirm(firstIdentity, firstPreview));
        assertEquals(replacement, restarted.confirm(nextIdentity, nextPreview));
        assertEquals(replacement, restarted.requireHumanAuthorized(fixture.workspace, fixture.thread, ORIGIN));
        assertThrows(
                PersistenceException.class,
                () -> restarted.confirm(fixture.identity("site/browser.grant.confirm", 0, firstPreview), firstPreview));
    }

    @Test
    void 回拨时钟下真实Core取消保持Turn可读和版本推进且不改创建时间预算或取消墙钟() throws Exception {
        TurnId turn = fixture.turn();
        AgentTurn before = fixture.core.findTurn(turn).orElseThrow();
        fixture.clock.advance(Duration.ofSeconds(-10));
        var identity = fixture.identity("turn/cancel", before.revision(), turn);
        AgentTurn requested = fixture.core.requestTurnCancellation(identity, turn, "用户取消");
        assertEquals(TurnStatus.QUEUED, requested.status());
        assertEquals(before.revision() + 1, requested.revision());
        assertEquals(before.createdAt(), requested.createdAt());
        assertEquals(before.updatedAt(), requested.updatedAt());
        assertEquals(before.budget(), requested.budget());
        assertEquals(requested, fixture.core.findTurn(turn).orElseThrow());
        assertEquals(requested, fixture.core.requestTurnCancellation(identity, turn, "用户取消"));
        assertTrue(fixture.core.cancellationRequested(turn));
        new com.javaclaw.server.persistence.H2Transactions(fixture.database).execute(connection -> {
            try (var statement =
                    connection.prepareStatement("SELECT REQUESTED_AT FROM CORE.TURN_CANCELLATION WHERE TURN_ID=?")) {
                statement.setString(1, turn.toString());
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(
                            fixture.clock.instant(),
                            rows.getObject(1, java.time.OffsetDateTime.class).toInstant());
                }
            }
            return null;
        });
        fixture.finish(turn);
        AgentTurn cancelled = fixture.core.findTurn(turn).orElseThrow();
        assertEquals(TurnStatus.CANCELLED, cancelled.status());
        assertEquals(before.revision() + 2, cancelled.revision());
        assertEquals(before.createdAt(), cancelled.createdAt());
        assertEquals(before.updatedAt(), cancelled.updatedAt());
        assertEquals(before.budget(), cancelled.budget());
    }

    private Grant confirm(URI origin) {
        var preview = fixture.service.preview(fixture.workspace, fixture.thread, origin);
        return fixture.service.confirm(fixture.identity("site/browser.grant.confirm", 0, preview), preview);
    }

    private AgentTurn continueTurn(TurnId parent) {
        var request = request(parent);
        return fixture.core.startTurn(fixture.identity("turn/start", 0, request), request);
    }

    private TurnStartRequest request(TurnId parent) {
        return TurnContractFixtures.request(
                        fixture.thread,
                        new TurnBudget(1000, 1000, 2, 0, Duration.ofMinutes(1)),
                        fixture.core.turnUserMessage(parent))
                .withContinuedFrom(parent);
    }
}
