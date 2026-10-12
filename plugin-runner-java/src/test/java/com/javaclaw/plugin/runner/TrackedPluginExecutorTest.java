package com.javaclaw.plugin.runner;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** 验证后台接入关闭后，旧 RPC 和既有工作仍可完成必要的派生任务。 */
class TrackedPluginExecutorTest {
    @Test void refusesNewBackgroundEntrancesAndCountsExistingWorkUntilItsChildrenComplete() throws Exception {
        AtomicBoolean inRpc = new AtomicBoolean();
        try (var executor = new TrackedPluginExecutor(inRpc::get, getClass()::getClassLoader, () -> { }, () -> null)) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            Future<?> existing = executor.submit(() -> {
                started.countDown(); release.await();
                executor.submit(() -> assertSame(getClass().getClassLoader(), Thread.currentThread().getContextClassLoader())).get();
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            executor.beginDrain();
            assertEquals(1, executor.activeCount());
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
            inRpc.set(true);
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            inRpc.set(false);
            release.countDown(); existing.get(5, TimeUnit.SECONDS);
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, executor.activeCount());
        }
    }
}
