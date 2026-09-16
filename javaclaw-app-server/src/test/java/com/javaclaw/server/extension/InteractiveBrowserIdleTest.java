package com.javaclaw.server.extension;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveBrowserIdleTest {
    @TempDir
    Path directory;

    @Test
    void 显式操作刷新空闲期限但退休网络租约和后台维护不会续期() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var turn = fixture.turn();
            fixture.service.invoke(fixture.modelInvocation(
                    turn,
                    "browser_open",
                    new BrowserCommands.Open(InteractiveBrowserHostFixture.ORIGIN, Optional.empty(), false)));
            fixture.advance(Duration.ofMinutes(14));
            fixture.service.invoke(fixture.modelInvocation(
                    turn,
                    "browser_act",
                    new BrowserCommands.Act(
                            BrowserContracts.Action.simple(BrowserContracts.Operation.SNAPSHOT), Optional.empty())));
            fixture.advance(Duration.ofMinutes(2));
            assertTrue(fixture.leaseReleased.await(5, TimeUnit.SECONDS), "过期网络租约由真实维护线程释放");
            assertEquals(0, fixture.closes, "窗口最近操作在两分钟前，不能按最初创建时间误关");
            fixture.advance(Duration.ofMinutes(14));
            assertTrue(fixture.sessionClosed.await(5, TimeUnit.SECONDS), "真正超过15分钟空闲后必须回收");
            assertEquals(1, fixture.closes);
        }
    }
}
