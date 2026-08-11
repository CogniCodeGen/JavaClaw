package com.javaclaw.plugin;

import com.javaclaw.platform.execution.ManagedTaskExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginWatcherTest {

    @TempDir
    Path pluginsDirectory;

    @Test
    void watcherRestartsOnManagedVirtualThreadAndReleasesTasks() throws Exception {
        CountDownLatch firstChange = new CountDownLatch(1);
        CountDownLatch secondChange = new CountDownLatch(1);
        AtomicInteger phase = new AtomicInteger(1);
        AtomicBoolean callbacksWereVirtual = new AtomicBoolean(true);
        try (ManagedTaskExecutor executor = new ManagedTaskExecutor()) {
            PluginWatcher watcher = new PluginWatcher(pluginsDirectory, () -> {
                if (!Thread.currentThread().isVirtual()) {
                    callbacksWereVirtual.set(false);
                }
                if (phase.get() == 1) {
                    firstChange.countDown();
                } else {
                    secondChange.countDown();
                }
            }, executor);
            try {
                watcher.start();
                Path pluginDirectory = Files.createDirectory(pluginsDirectory.resolve("demo"));
                Files.writeString(pluginDirectory.resolve("demo.jar"), "test");
                assertTrue(firstChange.await(5, TimeUnit.SECONDS),
                        "首次目录变化没有触发插件重扫");

                watcher.stop();
                phase.set(2);
                watcher.start();
                Files.writeString(pluginDirectory.resolve("demo.jar"), "updated-content");
                assertTrue(secondChange.await(5, TimeUnit.SECONDS),
                        "监听任务重启后没有触发插件重扫");
                assertTrue(callbacksWereVirtual.get(), "监听回调应由托管虚拟线程执行");
            } finally {
                watcher.stop();
            }

            awaitNoTasks(executor);
            assertEquals(0, executor.activeTaskCount());
        }
    }

    private static void awaitNoTasks(ManagedTaskExecutor executor) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (executor.activeTaskCount() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }
}
