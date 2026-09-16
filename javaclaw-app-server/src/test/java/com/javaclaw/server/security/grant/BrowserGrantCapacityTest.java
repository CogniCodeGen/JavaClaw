package com.javaclaw.server.security.grant;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.builtin.contracts.BrowserGrantContracts.Grant;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserGrantCapacityTest {
    @TempDir
    Path directory;

    @Test
    void 当前来源达到上限先拒绝确认且撤销后同预览仍能确认() throws Exception {
        var fixture = new BrowserGrantFixture(directory);
        List<Grant> existing = seedHistoricalGrants(fixture, 128);
        URI nextOrigin = URI.create("https://next.example");
        var preview = fixture.service.preview(fixture.workspace, fixture.thread, nextOrigin);
        var identity = fixture.identity("site/browser.grant.confirm", 0, preview);
        assertThrows(PersistenceException.class, () -> fixture.service.confirm(identity, preview));
        assertEquals(
                128,
                fixture.service
                        .currentOrigins(fixture.workspace, fixture.thread)
                        .size());
        Grant first = existing.getFirst();
        fixture.service.revoke(fixture.identity("site/browser.grant.revoke", 1, first.id()), first.id());
        Grant approved = fixture.service.confirm(identity, preview);
        assertEquals(nextOrigin, approved.origin());
        assertEquals(
                128,
                fixture.service
                        .currentOrigins(fixture.workspace, fixture.thread)
                        .size());
        assertEquals(approved, fixture.service.confirm(identity, preview));
    }

    @Test
    void 相同来源另次确认不占新来源容量() throws Exception {
        var fixture = new BrowserGrantFixture(directory);
        List<Grant> existing = seedHistoricalGrants(fixture, 128);
        var preview = fixture.service.preview(
                fixture.workspace, fixture.thread, existing.getFirst().origin());
        Grant granted = fixture.service.confirm(fixture.identity("site/browser.grant.confirm", 0, preview), preview);
        assertEquals(existing.getFirst().origin(), granted.origin());
        assertEquals(
                128,
                fixture.service
                        .currentOrigins(fixture.workspace, fixture.thread)
                        .size());
    }

    @Test
    void 历史超限不会阻止普通Turn但快照只按确定顺序保留一百二十八来源() throws Exception {
        var fixture = new BrowserGrantFixture(directory);
        List<Grant> existing = seedHistoricalGrants(fixture, 129);
        var turn = fixture.turn();
        var snapshot = fixture.service.freeze(fixture.workspace, fixture.thread, turn);
        assertEquals(128, snapshot.origins().size());
        assertFalse(snapshot.origins().contains(existing.getFirst().origin()));
        assertTrue(snapshot.origins().contains(existing.getLast().origin()));
        assertThrows(
                SecurityException.class,
                () -> fixture.service.requireAuthorized(
                        snapshot, existing.getFirst().origin()));
        var reopened = new BrowserGrantService(fixture.database, fixture.json, fixture.clock);
        assertEquals(snapshot, reopened.freeze(fixture.workspace, fixture.thread, turn));
    }

    /** 模拟旧版本已保存的授权；仅夹具绕过新确认上限，生产命令仍由真实服务验证。 */
    private static List<Grant> seedHistoricalGrants(BrowserGrantFixture fixture, int count) throws Exception {
        return new H2Transactions(fixture.database).execute(connection -> {
            var repository = new BrowserGrantRepository(fixture.json);
            repository.requireThread(connection, fixture.workspace, fixture.thread);
            List<Grant> grants = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                Grant grant = new Grant(
                        UUID.randomUUID().toString(),
                        1,
                        SecurityGrantState.ACTIVE,
                        fixture.workspace,
                        fixture.thread,
                        URI.create("https://origin-" + index + ".example"),
                        fixture.clock.instant().plusNanos(index));
                repository.insertGrant(connection, grant);
                grants.add(grant);
            }
            return List.copyOf(grants);
        });
    }
}
