package com.javaclaw.server.extension;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.Access;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.Page;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.State;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.WorkerStatus;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.IsolatedServiceCallScope;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 宿主必须独立校验 Worker 和平台调用边界，不能把合法 JSON 等同于合法执行身份。 */
class SiteRegistrationBoundaryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void Worker返回其他会话或改变授权及最终来源时宿主拒绝结果() throws Exception {
        for (WorkerMismatch mismatch : WorkerMismatch.values()) {
            try (var fixture = fixture(mismatch.name())) {
                var active = fixture.begin();
                fixture.worker.current = mismatched(fixture.worker.current, mismatch);
                assertThrows(SecurityException.class, () -> fixture.status(active.sessionId()), mismatch.name());
                assertEquals(State.FAILED, fixture.status(active.sessionId()).state(), mismatch.name());
                assertEquals(0, fixture.siteCount());
                assertEquals(0, fixture.vault.status().credentialCount());
            }
        }
    }

    @Test
    void 非Site调用错误服务或文档版本不能启动登记且关闭宿主不再接受新操作() throws Exception {
        for (InvocationMismatch mismatch : InvocationMismatch.values()) {
            try (var fixture = fixture(mismatch.name())) {
                var request = fixture.invocation(
                        SiteRegistrationFixture.WORKSPACE,
                        "registration.begin",
                        new SiteRegistrationContracts.BeginRequest(SiteRegistrationFixture.ORIGIN),
                        "begin");
                if (mismatch == InvocationMismatch.CLOSED) {
                    fixture.service.close();
                }
                var altered = mismatched(request, mismatch);
                assertThrows(SecurityException.class, () -> fixture.service.invoke(altered), mismatch.name());
                assertEquals(0, fixture.worker.starts);
                assertEquals(0, fixture.siteCount());
            }
        }
    }

    @Test
    void 查询携带写身份或未知命令不能绕过人工登记契约() throws Exception {
        try (var fixture = fixture("commands")) {
            var input = new SiteRegistrationContracts.SessionRequest(
                    UUID.randomUUID().toString());
            assertThrows(
                    IllegalArgumentException.class, () -> fixture.call("registration.status", input, "query-write"));
            assertThrows(
                    IllegalArgumentException.class, () -> fixture.call("registration.unsupported", input, "unknown"));
            assertThrows(SecurityException.class, () -> fixture.call("registration.cancel", input, null));
            assertEquals(0, fixture.worker.starts);
        }
    }

    @Test
    void 错配会话和已取消会话不能追加来源且遗留会话查询不替换活动窗口() throws Exception {
        try (var fixture = fixture("ownership")) {
            var active = fixture.begin();
            String oldId = UUID.randomUUID().toString();
            var old = new SiteRegistrationContracts.Session(
                    oldId, State.ACTIVE, active.access(), active.page(), Optional.empty());
            fixture.store.record(SiteRegistrationFixture.WORKSPACE, SiteRegistrationFixture.identity("old"), old);
            assertEquals(State.EXPIRED, fixture.status(oldId).state());
            var wrong = new SiteRegistrationContracts.OriginRequest(oldId, 1, URI.create("https://extra.example.com"));
            assertThrows(SecurityException.class, () -> fixture.call("registration.origin", wrong, "wrong"));
            assertEquals(State.ACTIVE, fixture.status(active.sessionId()).state());
            fixture.call(
                    "registration.cancel", new SiteRegistrationContracts.SessionRequest(active.sessionId()), "cancel");
            var cancelled = new SiteRegistrationContracts.OriginRequest(active.sessionId(), 1, wrong.origin());
            assertThrows(SecurityException.class, () -> fixture.call("registration.origin", cancelled, "cancelled"));
        }
    }

    @Test
    void Worker发现来源与宿主发现合并时仍保持有界且不自动授权() throws Exception {
        try (var fixture = fixture("pending")) {
            var active = fixture.begin();
            URI workerOnly = URI.create("https://worker.example.com");
            fixture.worker.current = withPending(fixture.worker.current, workerOnly);
            assertEquals(
                    Set.of(workerOnly),
                    fixture.status(active.sessionId()).access().pendingOrigins());
            for (int index = 0; index < 127; index++) {
                fixture.worker.network.deniedOrigin(URI.create("https://pending" + index + ".example.com"), 1);
            }
            fixture.worker.current = withPending(fixture.worker.current, URI.create("https://overflow.example.com"));
            var refreshed = fixture.status(active.sessionId());
            assertEquals(128, refreshed.access().pendingOrigins().size());
            assertEquals(active.access().allowedOrigins(), refreshed.access().allowedOrigins());
        }
    }

    @Test
    void Worker报告关闭但清理仍活动时不得发布取消成功或重复清理() throws Exception {
        try (var fixture = fixture("cleanup")) {
            var active = fixture.begin();
            fixture.worker.current =
                    new WorkerStatus(active.sessionId(), State.CANCELLED, active.access(), active.page());
            fixture.worker.cancelState = State.ACTIVE;
            assertThrows(IllegalStateException.class, () -> fixture.status(active.sessionId()));
            assertEquals(State.FAILED, fixture.status(active.sessionId()).state());
            assertEquals(1, fixture.worker.cancels);
            assertTrue(fixture.broker.requests.isEmpty());
        }
    }

    private WorkerStatus mismatched(WorkerStatus current, WorkerMismatch mismatch) {
        Access access = current.access();
        String id = current.sessionId();
        Page page = current.page();
        switch (mismatch) {
            case SESSION -> id = UUID.randomUUID().toString();
            case ORIGINS ->
                access = new Access(
                        access.generation(),
                        Set.of(URI.create("https://other.example.com")),
                        access.pendingOrigins(),
                        access.expiresAt());
            case EXPIRY ->
                access = new Access(
                        access.generation(),
                        access.allowedOrigins(),
                        access.pendingOrigins(),
                        access.expiresAt().plusSeconds(1));
            case PAGE ->
                page = new Page(
                        page.pageRevision(),
                        Optional.of(URI.create("https://other.example.com/account")),
                        page.title(),
                        page.candidates());
        }
        return new WorkerStatus(id, current.state(), access, page);
    }

    private IsolatedServiceInvocation mismatched(IsolatedServiceInvocation current, InvocationMismatch mismatch) {
        ExtensionId caller =
                mismatch == InvocationMismatch.CALLER ? new ExtensionId("com.javaclaw.skill") : current.caller();
        String service = mismatch == InvocationMismatch.SERVICE ? "unexpected.service" : current.serviceId();
        var scope = current.scope();
        if (mismatch == InvocationMismatch.REVISION) {
            scope = new IsolatedServiceCallScope(scope.threadId(), scope.turnId(), scope.idempotencyKey(), 1);
        }
        return new IsolatedServiceInvocation(
                caller,
                current.workspaceId(),
                current.effectivePermissions(),
                service,
                current.request(),
                current.cancellation(),
                scope);
    }

    private WorkerStatus withPending(WorkerStatus current, URI origin) {
        Access access = current.access();
        return new WorkerStatus(
                current.sessionId(),
                current.state(),
                new Access(access.generation(), access.allowedOrigins(), Set.of(origin), access.expiresAt()),
                current.page());
    }

    private SiteRegistrationFixture fixture(String name) throws Exception {
        return new SiteRegistrationFixture(temporaryDirectory.resolve(name).resolve("data-v6"));
    }

    private enum WorkerMismatch {
        SESSION,
        ORIGINS,
        EXPIRY,
        PAGE
    }

    private enum InvocationMismatch {
        CALLER,
        SERVICE,
        REVISION,
        CLOSED
    }
}
