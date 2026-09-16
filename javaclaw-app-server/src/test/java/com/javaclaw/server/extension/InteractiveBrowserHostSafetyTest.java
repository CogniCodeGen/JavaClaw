package com.javaclaw.server.extension;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.TurnId;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveBrowserHostSafetyTest {
    @TempDir
    Path directory;

    @Test
    void 没有浏览器窗口时仍查询持久续接失败状态() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var expected = new BrowserCommands.ContinuationStatus(
                    BrowserCommands.ContinuationState.FAILED,
                    BrowserCommands.ContinuationFailure.BUDGET_EXHAUSTED,
                    "原任务预算已用完",
                    TurnId.random(),
                    Optional.empty());
            new H2ManagedExtensionStore(fixture.host.database(), fixture.host.clock())
                    .inTransaction(new ExtensionId(BuiltinExtensionIds.SITE), tx -> {
                        tx.put(
                                "browser.continuation-status." + fixture.workspace,
                                fixture.thread.toString(),
                                0,
                                fixture.json.encode(expected));
                        return null;
                    });
            var status = fixture.json.decode(
                    fixture.service.invoke(fixture.invocation("browser.status", Map.of(), 0)),
                    BrowserCommands.Status.class);
            assertTrue(status.session().isEmpty());
            assertEquals(Optional.of(expected), status.continuation());
            assertEquals(0, fixture.opens);
        }
    }

    @Test
    void 真实宿主打开保留人工窗口且退休Turn不能释放人工控制() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var open = fixture.invocation(
                    "browser.open",
                    new BrowserCommands.Open(
                            InteractiveBrowserHostFixture.ORIGIN.resolve("/page"), Optional.empty(), false),
                    0);
            fixture.service.invoke(open);
            assertEquals(1, fixture.opens);
            var before = fixture.view.lease();
            fixture.service.finishTurn(TurnId.random());
            assertEquals(before, fixture.view.lease());
            fixture.service.invoke(fixture.invocation("browser.takeover", Map.of(), before.generation()));
            assertTrue(fixture.view.lease().generation() > before.generation());
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.service.invoke(fixture.invocation("browser.close", Map.of(), before.generation())));
            fixture.service.invoke(fixture.invocation(
                    "browser.close", Map.of(), fixture.view.lease().generation()));
            assertEquals(1, fixture.closes);
        }
    }

    @Test
    void 人工打开成功回执重试不需要助手租约也不重复导航() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var request = fixture.invocation(
                    "browser.open",
                    new BrowserCommands.Open(InteractiveBrowserHostFixture.ORIGIN, Optional.empty(), false),
                    0);
            var first = fixture.service.invoke(request);
            var second = fixture.service.invoke(request);
            assertEquals(first, second);
            assertEquals(1, fixture.opens);
            assertEquals(0, fixture.actions);
        }
    }

    @Test
    void 旧观察和网络取消不能借用后来的人工租约() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            fixture.service.invoke(fixture.invocation(
                    "browser.open",
                    new BrowserCommands.Open(InteractiveBrowserHostFixture.ORIGIN, Optional.empty(), false),
                    0));
            var session = fixture.session();
            var old = session.access;
            var authority = new BrowserSessionAuthority(fixture.host, fixture.grants);
            var cancellation = authority.cancellation(session, old, new CancellationSource());
            try (var result = fixture.observation(session.view)) {
                authority.release(session);
                session.access = authority.acquire(fixture.invocation("browser.takeover", Map.of(), 2), 3, true);
                assertTrue(cancellation.isCancelled());
                assertThrows(
                        RuntimeException.class,
                        () -> authority.authorize(session, old, InteractiveBrowserHostFixture.ORIGIN));
                assertThrows(
                        SecurityException.class,
                        () -> new BrowserThreadAttachments(fixture.host).store(session, old, "old", result));
                assertEquals(old.lease(), session.view.lease(), "被拒绝的旧结果不再写回会话状态");
            }
        }
    }

    @Test
    void 未授权资源只记录精确Origin且队列有界旧代通知无效() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            fixture.service.invoke(fixture.invocation(
                    "browser.open",
                    new BrowserCommands.Open(InteractiveBrowserHostFixture.ORIGIN, Optional.empty(), false),
                    0));
            var session = fixture.session();
            var authority = new BrowserSessionAuthority(fixture.host, fixture.grants);
            var callback = new BrowserSessionNetwork(fixture.host, authority, fixture.privateGrants).callback(session);
            callback.deniedOrigin(
                    URI.create("https://late.example"), session.access.lease().generation() - 1);
            assertTrue(session.pendingOrigins.isEmpty());
            for (int index = 0; index < 140; index++) {
                callback.deniedOrigin(
                        URI.create("https://resource" + index + ".example"),
                        session.access.lease().generation());
            }
            assertEquals(128, session.pendingOrigins.size());
            assertFalse(session.pendingOrigins.containsKey(URI.create("https://late.example")));
            assertEquals(1, fixture.opens, "来源提示不启动其他浏览器或发起网络");
        }
    }
}
