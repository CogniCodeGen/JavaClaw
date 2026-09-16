package com.javaclaw.server.extension;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveBrowserDispatchFailureTest {
    private static final URI BLOCKED = URI.create("https://blocked.example.com");

    @TempDir
    Path directory;

    @Test
    void 点击后阻断新来源仍等待真实确认并保留未确认账本不重放() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var turn = fixture.turn();
            fixture.service.invoke(fixture.modelInvocation(turn, "browser_open", open()));
            fixture.denyAction = true;
            fixture.originDecision = true;
            var action = fixture.modelInvocation(turn, "browser_act", action(BrowserContracts.Operation.CLICK));
            var failure =
                    assertThrows(BrowserOperationUnconfirmedException.class, () -> fixture.service.invoke(action));
            assertEquals(turn, fixture.continuationTurn);
            assertEquals(BLOCKED, fixture.continuationOrigin);
            assertTrue(fixture.grants
                    .currentOrigins(fixture.workspace, fixture.thread)
                    .contains(BLOCKED));
            assertThrows(IllegalStateException.class, () -> fixture.service.invoke(action));
            assertEquals(1, fixture.actions);
            assertFalse(failure.payload().json().contains(BLOCKED.toString()));
            assertNull(failure.getCause());
        }
    }

    @Test
    void 用户拒绝来源后仍返回未确认失败但不伪称已请求续接() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var turn = fixture.turn();
            fixture.service.invoke(fixture.modelInvocation(turn, "browser_open", open()));
            fixture.denyAction = true;
            fixture.originDecision = false;
            var action = fixture.modelInvocation(turn, "browser_act", action(BrowserContracts.Operation.CLICK));
            assertThrows(BrowserOperationUnconfirmedException.class, () -> fixture.service.invoke(action));
            assertNull(fixture.continuationTurn);
            assertFalse(fixture.grants
                    .currentOrigins(fixture.workspace, fixture.thread)
                    .contains(BLOCKED));
            assertThrows(IllegalStateException.class, () -> fixture.service.invoke(action));
            assertEquals(1, fixture.actions);
        }
    }

    @Test
    void 首次导航重定向被阻断时清理不丢失专用失败与续接请求() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var turn = fixture.turn();
            fixture.denyOpen = true;
            fixture.originDecision = true;
            var request = fixture.modelInvocation(turn, "browser_open", open());
            assertThrows(BrowserOperationUnconfirmedException.class, () -> fixture.service.invoke(request));
            assertEquals(turn, fixture.continuationTurn);
            assertEquals(1, fixture.closes);
            assertThrows(IllegalStateException.class, () -> fixture.service.invoke(request));
            assertEquals(1, fixture.opens, "未确认启动相同身份不得再次派发导航");
        }
    }

    @Test
    void 有旧待授权来源也不能把上传前置校验失败当作已派发动作() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var turn = fixture.turn();
            fixture.service.invoke(fixture.modelInvocation(turn, "browser_open", open()));
            fixture.network.deniedOrigin(BLOCKED, fixture.view.lease().generation());
            fixture.originDecision = true;
            var request = fixture.modelInvocation(turn, "browser_act", action(BrowserContracts.Operation.UPLOAD));
            assertThrows(IllegalArgumentException.class, () -> fixture.service.invoke(request));
            assertEquals(0, fixture.actions);
            assertNull(fixture.continuationTurn);
            assertTrue(fixture.host.inputs().list(Optional.of(turn), false).isEmpty());
        }
    }

    private static BrowserCommands.Open open() {
        return new BrowserCommands.Open(InteractiveBrowserHostFixture.ORIGIN, Optional.empty(), false);
    }

    private static BrowserCommands.Act action(BrowserContracts.Operation operation) {
        return new BrowserCommands.Act(BrowserContracts.Action.simple(operation), Optional.empty());
    }
}
