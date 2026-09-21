package com.javaclaw.server.extension;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.Access;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.Page;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.Session;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.State;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.WorkerStatus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 即使宿主前置校验已通过，事务也必须再次拒绝失效会话和不一致的私有导出。 */
class SiteRegistrationStoreBoundaryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void 持久会话状态或授权在保存前改变时整个复合事务回滚() throws Exception {
        for (PersistedMismatch mismatch : PersistedMismatch.values()) {
            try (var fixture = fixture(mismatch.name())) {
                Session active = fixture.begin();
                Session changed = changed(active, mismatch, fixture);
                fixture.store.record(
                        SiteRegistrationFixture.WORKSPACE, SiteRegistrationFixture.identity("changed"), changed);
                WorkerStatus exported = fixture.worker.current;
                if (mismatch == PersistedMismatch.EXPIRED) {
                    exported = new WorkerStatus(active.sessionId(), State.ACTIVE, changed.access(), active.page());
                }
                WorkerStatus status = exported;
                byte[] state = SiteRegistrationFixture.STORAGE.clone();
                var identity = SiteRegistrationFixture.identity("save");
                // 空回调隔离事务自身的最终检查，不能让宿主检查掩盖持久状态竞争。
                assertThrows(
                        IllegalStateException.class,
                        () -> fixture.store.complete(
                                SiteRegistrationFixture.WORKSPACE,
                                fixture.confirmation(active, false),
                                status,
                                identity,
                                state,
                                new byte[0],
                                () -> {}),
                        mismatch.name());
                assertArrayEquals(new byte[state.length], state);
                assertEquals(0, fixture.siteCount());
                assertEquals(0, fixture.vault.status().credentialCount());
                assertTrue(fixture.store.recover(identity).isEmpty());
            }
        }
    }

    @Test
    void 不匹配会话终态代次来源或凭据候选的导出不能密封() throws Exception {
        for (ExportMismatch mismatch : ExportMismatch.values()) {
            try (var fixture = fixture(mismatch.name())) {
                Session active = fixture.begin();
                WorkerStatus exported = changed(fixture.worker.current, mismatch);
                var request = mismatch == ExportMismatch.CANDIDATE
                        ? new SiteRegistrationContracts.CompleteRequest(
                                active.sessionId(),
                                1,
                                active.page().pageRevision(),
                                Optional.of("not-captured"),
                                "示例网站")
                        : fixture.confirmation(active, false);
                byte[] state = SiteRegistrationFixture.STORAGE.clone();
                byte[] secret = mismatch == ExportMismatch.CANDIDATE
                        ? "username\0password".getBytes(StandardCharsets.UTF_8)
                        : new byte[0];
                assertThrows(
                        SecurityException.class,
                        () -> fixture.store.complete(
                                SiteRegistrationFixture.WORKSPACE,
                                request,
                                exported,
                                SiteRegistrationFixture.identity("save"),
                                state,
                                secret,
                                () -> {}),
                        mismatch.name());
                assertArrayEquals(new byte[state.length], state);
                assertArrayEquals(new byte[secret.length], secret);
                assertEquals(0, fixture.siteCount());
                assertEquals(0, fixture.vault.status().credentialCount());
            }
        }
    }

    @Test
    void 已选密码却缺少私有凭据帧时不能只保存登录态() throws Exception {
        try (var fixture = fixture("missing-credential")) {
            Session active = fixture.begin();
            byte[] state = SiteRegistrationFixture.STORAGE.clone();
            assertThrows(
                    SecurityException.class,
                    () -> fixture.store.complete(
                            SiteRegistrationFixture.WORKSPACE,
                            fixture.confirmation(active, true),
                            fixture.worker.current,
                            SiteRegistrationFixture.identity("save"),
                            state,
                            new byte[0],
                            () -> {}));
            assertArrayEquals(new byte[state.length], state);
            assertEquals(0, fixture.siteCount());
            assertEquals(0, fixture.vault.status().credentialCount());
        }
    }

    @Test
    void 已提交回执在重试时优先恢复且不会再次消费私有数据() throws Exception {
        try (var fixture = fixture("recover")) {
            Session active = fixture.begin();
            var request = fixture.confirmation(active, false);
            var identity = SiteRegistrationFixture.identity("save");
            Session saved = fixture.store.complete(
                    SiteRegistrationFixture.WORKSPACE,
                    request,
                    fixture.worker.current,
                    identity,
                    SiteRegistrationFixture.STORAGE.clone(),
                    new byte[0],
                    () -> {});
            byte[] invalidState = {1};
            byte[] invalidSecret = {2};
            Session replay = fixture.store.complete(
                    SiteRegistrationFixture.WORKSPACE,
                    request,
                    fixture.worker.current,
                    identity,
                    invalidState,
                    invalidSecret,
                    () -> {
                        throw new IllegalStateException("禁止重复提交");
                    });
            assertEquals(saved, replay);
            assertArrayEquals(new byte[1], invalidState);
            assertArrayEquals(new byte[1], invalidSecret);
            assertEquals(1, fixture.siteCount());
            assertEquals(1, fixture.vault.status().credentialCount());
            fixture.service.close();
            assertEquals(
                    saved,
                    fixture.store
                            .read(SiteRegistrationFixture.WORKSPACE, active.sessionId())
                            .orElseThrow());
        }
    }

    private Session changed(Session active, PersistedMismatch mismatch, SiteRegistrationFixture fixture) {
        Access access = active.access();
        State state = State.ACTIVE;
        switch (mismatch) {
            case STATE -> state = State.CANCELLED;
            case GENERATION -> access = new Access(2, access.allowedOrigins(), Set.of(), access.expiresAt());
            case ORIGINS ->
                access = new Access(1, Set.of(URI.create("https://other.example.com")), Set.of(), access.expiresAt());
            case EXPIRY ->
                access = new Access(
                        1, access.allowedOrigins(), Set.of(), access.expiresAt().plusSeconds(1));
            case EXPIRED ->
                access = new Access(
                        1,
                        access.allowedOrigins(),
                        Set.of(),
                        fixture.clock.instant().minusSeconds(1));
        }
        return new Session(active.sessionId(), state, access, active.page(), Optional.empty());
    }

    private WorkerStatus changed(WorkerStatus current, ExportMismatch mismatch) {
        String id = current.sessionId();
        State state = current.state();
        Access access = current.access();
        Page page = current.page();
        switch (mismatch) {
            case SESSION -> id = UUID.randomUUID().toString();
            case STATE -> state = State.CANCELLED;
            case GENERATION -> access = new Access(2, access.allowedOrigins(), Set.of(), access.expiresAt());
            case ORIGIN ->
                page = new Page(
                        page.pageRevision(),
                        Optional.of(URI.create("https://other.example.com/account")),
                        page.title(),
                        page.candidates());
            case CANDIDATE -> {}
        }
        return new WorkerStatus(id, state, access, page);
    }

    private SiteRegistrationFixture fixture(String name) throws Exception {
        return new SiteRegistrationFixture(temporaryDirectory.resolve(name).resolve("data-v6"));
    }

    private enum PersistedMismatch {
        STATE,
        GENERATION,
        ORIGINS,
        EXPIRY,
        EXPIRED
    }

    private enum ExportMismatch {
        SESSION,
        STATE,
        GENERATION,
        ORIGIN,
        CANDIDATE
    }
}
