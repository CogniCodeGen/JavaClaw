package com.javaclaw.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProcessTerminatorTest {

    @Test
    void 等待线程被中断时会终止直接进程() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"),
                "该测试使用类 Unix sleep");
        Process process = new ProcessBuilder("/bin/sleep", "30").start();
        assertInterruptedWaitTerminates(process);
    }

    @Test
    void 系统允许枚举时会终止完整进程树(@TempDir Path dir) throws Exception {
        assumeTrue(canEnumerateDescendants(),
                "当前受限环境不允许枚举进程树，仅验证直接进程降级路径");
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"),
                "该进程树脚本仅适用于类 Unix 环境");
        Path heartbeat = dir.resolve("heartbeat.txt");
        Process parent = new ProcessBuilder("/bin/sh", "-c",
                "while true; do echo tick >> \"$1\"; sleep 0.05; done & "
                        + "child=$!; echo $child; wait $child",
                "javaclaw-process-test", heartbeat.toString())
                .redirectErrorStream(true)
                .start();
        try {
            long childPid;
            try (BufferedReader output = new BufferedReader(
                    new InputStreamReader(parent.getInputStream()))) {
                childPid = Long.parseLong(output.readLine().trim());
            }
            assertTrue(waitUntilWritten(heartbeat, 1_000), "子进程应已开始产生心跳");
            List<Long> descendants = parent.descendants().map(ProcessHandle::pid).toList();
            assertTrue(descendants.contains(childPid),
                    "ProcessHandle 应能枚举派生 shell");

            assertInterruptedWaitTerminates(parent);
            assertTrue(waitUntilStable(heartbeat, 250, 2_000),
                    "取消完成后派生进程不能继续产生副作用");
        } finally {
            ProcessTerminator.destroyTreeForcibly(parent);
        }
    }

    private static void assertInterruptedWaitTerminates(Process process)
            throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = Thread.ofPlatform().start(() -> {
            waiting.countDown();
            try {
                ProcessTerminator.waitForOrTerminateOnInterrupt(
                        process, 30, TimeUnit.SECONDS);
                failure.set(new AssertionError("等待应被中断"));
            } catch (InterruptedException expected) {
                if (!Thread.currentThread().isInterrupted()) {
                    failure.set(new AssertionError("中断位应被恢复"));
                }
            }
        });

        assertTrue(waiting.await(1, TimeUnit.SECONDS));
        waiter.interrupt();
        waiter.join(3_000);
        assertFalse(waiter.isAlive(), "进程等待线程应及时退出");
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertFalse(process.isAlive(), "被等待的进程应被终止");
    }

    private static boolean canEnumerateDescendants() {
        try (var ignored = ProcessHandle.current().descendants()) {
            ignored.count();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean waitUntilWritten(Path path, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            try {
                if (Files.exists(path) && Files.size(path) > 0) return true;
            } catch (java.io.IOException ignored) {
                // 写入与读取竞争时重试。
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static boolean waitUntilStable(
            Path path, long stableMillis, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long lastSize = Files.size(path);
        long stableSince = System.nanoTime();
        while (System.nanoTime() < deadline) {
            Thread.sleep(20);
            long currentSize = Files.size(path);
            if (currentSize != lastSize) {
                lastSize = currentSize;
                stableSince = System.nanoTime();
            } else if (System.nanoTime() - stableSince
                    >= TimeUnit.MILLISECONDS.toNanos(stableMillis)) {
                return true;
            }
        }
        return false;
    }
}
