package com.javaclaw.memory;

import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.store.MemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryServiceLifecycleTest {

    @Test
    void 关闭会拒绝新后台写入并等待已有写入排空() throws Exception {
        MemoryTaskTracker tracker = new MemoryTaskTracker();
        tracker.startAccepting();
        MemoryTaskTracker.WorkLease active = tracker.tryAcquire();
        assertNotNull(active);

        tracker.stopAccepting();
        assertNull(tracker.tryAcquire(), "开始关闭后不能再接受写入");

        CountDownLatch waiterStarted = new CountDownLatch(1);
        CountDownLatch drained = new CountDownLatch(1);
        Thread waiter = Thread.ofVirtual().start(() -> {
            waiterStarted.countDown();
            tracker.awaitDrained();
            drained.countDown();
        });

        assertTrue(waiterStarted.await(1, TimeUnit.SECONDS));
        assertFalse(drained.await(100, TimeUnit.MILLISECONDS),
                "仍有后台写入时关闭流程不能继续");

        active.close();
        active.close(); // 租约释放幂等，Reactor 取消/终止竞态不会把计数减成负数
        assertTrue(drained.await(1, TimeUnit.SECONDS));
        waiter.join();

        tracker.startAccepting();
        MemoryTaskTracker.WorkLease reopened = tracker.tryAcquire();
        assertNotNull(reopened, "切换工作区重开后应重新接受写入");
        reopened.close();
    }

    @Test
    void 有界等待超时后可取消存量任务并完成排空() {
        MemoryTaskTracker tracker = new MemoryTaskTracker();
        tracker.startAccepting();
        MemoryTaskTracker.WorkLease active = tracker.tryAcquire();
        assertNotNull(active);
        AtomicBoolean cancelled = new AtomicBoolean();
        active.onCancel(() -> {
            cancelled.set(true);
            active.close();
        });

        tracker.stopAccepting();
        assertFalse(tracker.awaitDrained(20, TimeUnit.MILLISECONDS),
                "未释放的任务必须让有界等待按时返回，而不是永久卡住");

        tracker.cancelAll();

        assertTrue(cancelled.get());
        assertTrue(tracker.awaitDrained(1, TimeUnit.SECONDS));
    }

    @Test
    void 取消早于工作线程绑定时仍会补发取消动作() {
        MemoryTaskTracker tracker = new MemoryTaskTracker();
        tracker.startAccepting();
        MemoryTaskTracker.WorkLease active = tracker.tryAcquire();
        assertNotNull(active);

        tracker.stopAccepting();
        tracker.cancelAll();
        assertTrue(active.isCancellationRequested());

        AtomicBoolean invoked = new AtomicBoolean();
        active.onCancel(() -> {
            invoked.set(true);
            active.close();
        });

        assertTrue(invoked.get(), "调度/关闭竞态下不能漏掉取消信号");
        assertTrue(tracker.awaitDrained(1, TimeUnit.SECONDS));
    }

    @Test
    void 延迟释放动作由最后一个任务退出线程执行且只执行一次() throws Exception {
        MemoryTaskTracker tracker = new MemoryTaskTracker();
        tracker.startAccepting();
        MemoryTaskTracker.WorkLease first = tracker.tryAcquire();
        MemoryTaskTracker.WorkLease last = tracker.tryAcquire();
        AtomicBoolean released = new AtomicBoolean();

        tracker.stopAccepting();
        tracker.whenDrained(() -> {
            assertTrue(Thread.currentThread().isVirtual());
            released.set(true);
        });
        first.close();
        assertFalse(released.get());

        Thread.ofVirtual().start(last::close).join();

        assertTrue(released.get());
        assertThrows(IllegalStateException.class,
                () -> tracker.whenDrained(() -> { }),
                "同一代记忆任务只能登记一个延迟释放动作");
    }

    @Test
    void 延迟租约未释放时同路径重建会复用已打开的存储(@TempDir Path dir) {
        MemoryStoreRegistry.Lease oldRuntime = MemoryStoreRegistry.acquire(dir, 4);
        MemoryStoreRegistry.Lease replacement = null;
        MemoryStore shared = oldRuntime.store();
        try {
            replacement = MemoryStoreRegistry.acquire(dir.resolve("."), 4);
            assertSame(shared, replacement.store(),
                    "旧后台任务尚未退出时，新运行时不能再次打开同一个 EclipseStore 目录");

            oldRuntime.close();
            shared.addPendingEpisode(new Episode("test", "question", "answer"), "test");
            assertTrue(shared.allPendingEpisodes().stream()
                    .anyMatch(ep -> "question".equals(ep.userInput)),
                    "旧租约释放后，新运行时租约必须继续持有可写存储");
        } finally {
            oldRuntime.close();
            if (replacement != null) replacement.close();
        }

        assertThrows(IllegalStateException.class,
                () -> shared.addPendingEpisode(
                        new Episode("closed", "question", "answer"), "test"),
                "最后一个租约释放后才应真正关闭存储");

        MemoryStoreRegistry.Lease reopened = MemoryStoreRegistry.acquire(dir, 4);
        try {
            assertNotSame(shared, reopened.store(),
                    "最终关库后再次打开应创建新 MemoryStore 实例");
        } finally {
            reopened.close();
        }
    }
}
