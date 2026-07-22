package com.javaclaw.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginWatcherTest {

    @Test
    void 检测子目录文件变化且停止后可重新启动(@TempDir Path root) throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        PluginWatcher watcher = new PluginWatcher(root, () -> {
            int count = callbacks.incrementAndGet();
            if (count == 1) first.countDown();
            if (count == 2) second.countDown();
        });

        try {
            watcher.start();
            Path plugin = Files.createDirectories(root.resolve("demo"));
            Files.writeString(plugin.resolve("demo.jar"), "v1");
            assertTrue(first.await(5, TimeUnit.SECONDS), "首次文件变化应触发回调");

            watcher.stop();
            watcher.start();
            Files.writeString(plugin.resolve("demo.jar"), "v2-longer");
            assertTrue(second.await(5, TimeUnit.SECONDS), "停止后重新启动仍应正常监听");
        } finally {
            watcher.stop();
        }
    }
}
