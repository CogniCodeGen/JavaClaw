package com.javaclaw.server.extension;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveBrowserBoundaryTest {
    @TempDir
    Path directory;

    @Test
    void 无平台能力可查询状态但不允许打开且关闭后拒绝调用() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory);
                var offline = new SiteInteractiveBrowserService(
                        fixture.host, Optional.empty(), fixture.grants, fixture.privateGrants, (turn, origin) -> {})) {
            var status = fixture.json.decode(
                    offline.invoke(fixture.invocation("browser.status", Map.of(), 0)), BrowserCommands.Status.class);
            assertFalse(status.available());
            assertThrows(
                    IllegalStateException.class, () -> offline.invoke(fixture.invocation("browser.open", open(), 0)));
            offline.close();
            assertThrows(
                    IllegalStateException.class,
                    () -> offline.invoke(fixture.invocation("browser.status", Map.of(), 0)));
            assertEquals(0, fixture.opens);
        }
    }

    @Test
    void 无窗口及未知管理操作均不能隐式派发页面动作() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.service.invoke(fixture.invocation("browser.act", snapshot(), 0)));
            fixture.service.invoke(fixture.invocation("browser.open", open(), 0));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.service.invoke(fixture.invocation(
                            "browser.unknown", Map.of(), fixture.view.lease().generation())));
            assertEquals(0, fixture.actions);
        }
    }

    @Test
    void 打开与普通动作的成功回执在重复请求或关闭后都不重放() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var turn = fixture.turn();
            var original = fixture.modelInvocation(turn, "browser_open", open());
            var first = fixture.service.invoke(original);
            var navigation = fixture.modelInvocation(turn, "browser_open", open());
            var navigated = fixture.service.invoke(navigation);
            assertEquals(navigated, fixture.service.invoke(navigation));
            var request = fixture.modelInvocation(turn, "browser_act", snapshot());
            var observed = fixture.service.invoke(request);
            assertEquals(observed, fixture.service.invoke(request));
            fixture.service.invoke(fixture.invocation(
                    "browser.close", Map.of(), fixture.view.lease().generation()));
            assertEquals(first, fixture.service.invoke(original));
            assertEquals(1, fixture.opens);
            assertEquals(2, fixture.actions);
        }
    }

    @Test
    void 助手不能读取人工接管窗口也不能从普通动作填入密码() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var turn = fixture.turn();
            fixture.service.invoke(fixture.modelInvocation(turn, "browser_open", open()));
            fixture.service.invoke(fixture.invocation(
                    "browser.takeover", Map.of(), fixture.view.lease().generation()));
            assertThrows(
                    SecurityException.class,
                    () -> fixture.service.invoke(fixture.modelInvocation(turn, "browser_act", snapshot())));
            fixture.service.invoke(fixture.invocation(
                    "browser.return", Map.of(), fixture.view.lease().generation()));
            fixture.service.invoke(fixture.modelInvocation(turn, "browser_act", snapshot()));
            var secret = new BrowserCommands.Act(
                    BrowserContracts.Action.simple(BrowserContracts.Operation.FILL_SECRET), Optional.empty());
            assertThrows(
                    SecurityException.class,
                    () -> fixture.service.invoke(fixture.modelInvocation(turn, "browser_act", secret)));
            assertEquals(1, fixture.actions);
        }
    }

    @Test
    void 操作进行中拒绝第二个操作和账号填充并保留原请求回执() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var turn = fixture.turn();
            fixture.service.invoke(fixture.modelInvocation(turn, "browser_open", open()));
            fixture.blockAction = true;
            var pending = invokeAsync(fixture, fixture.modelInvocation(turn, "browser_act", snapshot()));
            try {
                assertTrue(fixture.actionStarted.await(3, TimeUnit.SECONDS));
                assertThrows(
                        IllegalStateException.class,
                        () -> fixture.service.invoke(fixture.modelInvocation(turn, "browser_act", snapshot())));
                assertThrows(
                        IllegalStateException.class,
                        () -> fixture.service.invoke(fixture.modelInvocation(
                                turn,
                                "browser_fill_account",
                                new BrowserContracts.CredentialsTarget("page", "user", "password"))));
            } finally {
                fixture.actionGate.countDown();
            }
            pending.get(5, TimeUnit.SECONDS);
            assertEquals(1, fixture.actions);
            assertEquals(0, fixture.fills);
        }
    }

    @Test
    void 无账号的专用填充失败仍保留未确认账本禁止同键重放() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var turn = fixture.turn();
            fixture.service.invoke(fixture.modelInvocation(turn, "browser_open", open()));
            var fill = fixture.modelInvocation(
                    turn, "browser_fill_account", new BrowserContracts.CredentialsTarget("page", "user", "password"));
            assertThrows(IllegalStateException.class, () -> fixture.service.invoke(fill));
            assertThrows(IllegalStateException.class, () -> fixture.service.invoke(fill));
            assertEquals(0, fixture.fills);
        }
    }

    @Test
    void 导航新来源先确认续接且不执行原动作() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var turn = fixture.turn();
            fixture.service.invoke(fixture.modelInvocation(turn, "browser_open", open()));
            fixture.originDecision = true;
            var navigate = new BrowserContracts.Action(
                    BrowserContracts.Operation.NEW_TAB,
                    BrowserContracts.Target.current(),
                    BrowserContracts.ActionInput.text("https://new.example.com/page"));
            var result = fixture.service.invoke(
                    fixture.modelInvocation(turn, "browser_act", new BrowserCommands.Act(navigate, Optional.empty())));
            assertEquals(Optional.of(BrowserOriginRequests.CONTINUATION), fixture.json.textField(result, "status"));
            assertEquals(turn, fixture.continuationTurn);
            assertEquals(0, fixture.actions);
        }
    }

    private static CompletableFuture<Void> invokeAsync(
            InteractiveBrowserHostFixture fixture, IsolatedServiceInvocation request) {
        return CompletableFuture.runAsync(() -> {
            try {
                fixture.service.invoke(request);
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        });
    }

    private static BrowserCommands.Open open() {
        return new BrowserCommands.Open(InteractiveBrowserHostFixture.ORIGIN, Optional.empty(), false);
    }

    private static BrowserCommands.Act snapshot() {
        return new BrowserCommands.Act(
                BrowserContracts.Action.simple(BrowserContracts.Operation.SNAPSHOT), Optional.empty());
    }
}
