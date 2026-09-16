package com.javaclaw.server.extension;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.browser.client.BrowserWorkerException;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveBrowserTurnLifecycleTest {
    @TempDir
    Path directory;

    @Test
    void 新Turn重新授权但同一对话只启动一次Worker() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var first = fixture.turn();
            fixture.service.invoke(fixture.modelInvocation(first, "browser_open", open()));
            String session = fixture.view.sessionId();
            long generation = fixture.view.lease().generation();
            fixture.finish(first);
            assertEquals(BrowserContracts.ControlMode.NONE, fixture.view.lease().mode());
            var next = fixture.turn();
            fixture.service.invoke(fixture.modelInvocation(next, "browser_act", snapshot()));
            assertEquals(1, fixture.opens);
            assertEquals(1, fixture.actions);
            assertEquals(session, fixture.view.sessionId());
            assertNotEquals(generation, fixture.view.lease().generation());
        }
    }

    @Test
    void 动作未确认后相同身份不重复执行副作用() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var turn = fixture.turn();
            fixture.service.invoke(fixture.modelInvocation(turn, "browser_open", open()));
            fixture.failAction = true;
            var action = fixture.modelInvocation(turn, "browser_act", snapshot());
            assertThrows(BrowserWorkerException.class, () -> fixture.service.invoke(action));
            assertThrows(IllegalStateException.class, () -> fixture.service.invoke(action));
            assertEquals(1, fixture.actions);
        }
    }

    @Test
    void 同一幂等键和载荷不能恢复另一工具的成功回执() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var turn = fixture.turn();
            fixture.service.invoke(fixture.modelInvocation(turn, "browser_open", open()));
            var first = fixture.modelInvocation(turn, "browser_act", snapshot());
            fixture.service.invoke(first);
            var other = new IsolatedServiceInvocation(
                    first.caller(),
                    first.workspaceId(),
                    first.effectivePermissions(),
                    first.serviceId(),
                    fixture.json.encode(
                            new BrowserCommands.Invocation("browser_tabs", fixture.json.encode(snapshot()))),
                    first.cancellation(),
                    first.scope());
            assertThrows(IllegalArgumentException.class, () -> fixture.service.invoke(other));
            assertEquals(1, fixture.actions);
        }
    }

    @Test
    void 人工接管后迟到AI观察不能回写为助手控制() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var turn = fixture.turn();
            fixture.service.invoke(fixture.modelInvocation(turn, "browser_open", open()));
            fixture.blockAction = true;
            var action = fixture.modelInvocation(turn, "browser_act", snapshot());
            var reply = CompletableFuture.runAsync(() -> {
                try {
                    fixture.service.invoke(action);
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });
            try {
                assertTrue(fixture.actionStarted.await(2, TimeUnit.SECONDS));
                fixture.service.invoke(fixture.invocation(
                        "browser.takeover", Map.of(), fixture.view.lease().generation()));
                var human = fixture.view.lease();
                fixture.actionGate.countDown();
                assertThrows(CompletionException.class, reply::join);
                assertEquals(
                        BrowserContracts.ControlMode.HUMAN, fixture.view.lease().mode());
                assertEquals(human, fixture.view.lease());
                fixture.service.finishTurn(turn);
                assertEquals(human, fixture.view.lease());
            } finally {
                fixture.actionGate.countDown();
            }
        }
    }

    private static BrowserCommands.Open open() {
        return new BrowserCommands.Open(InteractiveBrowserHostFixture.ORIGIN.resolve("/page"), Optional.empty(), false);
    }

    private static BrowserCommands.Act snapshot() {
        return new BrowserCommands.Act(
                BrowserContracts.Action.simple(BrowserContracts.Operation.SNAPSHOT), Optional.empty());
    }
}
