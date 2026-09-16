package com.javaclaw.server.extension;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.builtin.contracts.BrowserCommands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveBrowserShutdownTest {
    @TempDir
    Path directory;

    @Test
    void 服务关闭必须等待维护中的进程回收及后续持久化完成() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            fixture.blockClose = true;
            fixture.service.invoke(fixture.modelInvocation(
                    fixture.turn(),
                    "browser_open",
                    new BrowserCommands.Open(InteractiveBrowserHostFixture.ORIGIN, Optional.empty(), false)));
            fixture.advance(Duration.ofMinutes(16));
            assertTrue(fixture.sessionClosed.await(5, TimeUnit.SECONDS), "维护已进入 Worker 回收，但尚未提交关闭事件");
            long eventsBeforeClose = events(fixture);
            var started = new CountDownLatch(1);
            CompletableFuture<Void> closing = CompletableFuture.runAsync(() -> {
                started.countDown();
                fixture.service.close();
            });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(
                        TimeoutException.class,
                        () -> closing.get(200, TimeUnit.MILLISECONDS),
                        "不能在维护仍持有账号写租约、尚未提交 H2 通知时返回关闭成功");
            } finally {
                fixture.closeGate.complete(null);
            }
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(1, fixture.closes, "主关闭流程不能再次回收同一会话");
            assertEquals(eventsBeforeClose + 1, events(fixture), "返回关闭成功前必须提交维护线程的最终通知");
        }
    }

    private static long events(InteractiveBrowserHostFixture fixture) throws SQLException {
        String url = "jdbc:h2:file:" + fixture.host.database().dataRoot().resolve("javaclaw")
                + ";AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=FALSE";
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM CORE.EVENT WHERE TOPIC = 'site.browser.changed'");
                var result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }
}
