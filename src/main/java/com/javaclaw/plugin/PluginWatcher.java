package com.javaclaw.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 插件目录热感知 —— 监听 {@code plugins/} 根目录及其各插件子目录的变化，触发回调
 * （由 {@link PluginManager} 重扫 + 通知 UI 刷新），实现"放入插件子目录即出现在插件中心"。
 *
 * <p>插件按 {@code plugins/{名称}/{jar} + lib/} 组织，jar 在子目录里，故需同时监听根目录
 * （感知新增/删除插件子目录）与各子目录（感知子目录内 jar 增删）；新子目录出现后会动态补登监听。
 * 单 daemon 线程；事件去抖合并，避免文件复制过程中的抖动。</p>
 *
 * @author JavaClaw
 */
final class PluginWatcher {

    private static final Logger log = LoggerFactory.getLogger(PluginWatcher.class);

    /** 去抖窗口：收到事件后等待该时长再统一回调一次 */
    private static final long DEBOUNCE_MS = 500;

    private final Path root;
    private final Runnable onChange;

    private WatchService watchService;
    private Thread thread;
    private volatile boolean running = false;
    /** 已登记监听的目录（根 + 各插件子目录），避免重复注册 */
    private final Set<Path> registered = new HashSet<>();

    PluginWatcher(Path root, Runnable onChange) {
        this.root = root;
        this.onChange = onChange;
    }

    /** 启动监听（幂等）。 */
    synchronized void start() {
        if (running) {
            return;
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
            registerAll();
        } catch (Exception e) {
            log.warn("插件目录热感知启动失败（将仅支持手动刷新）：{}", e.toString());
            return;
        }
        running = true;
        thread = new Thread(this::loop, "plugin-watcher");
        thread.setDaemon(true);
        thread.start();
        log.info("插件目录热感知已启动：{}（监听 {} 个目录）", root, registered.size());
    }

    /** 停止监听（幂等）。 */
    synchronized void stop() {
        running = false;
        if (watchService != null) {
            try {
                watchService.close();   // 唤醒阻塞中的 take()
            } catch (Exception e) {
                log.debug("关闭 WatchService 忽略异常：{}", e.toString());
            }
        }
        if (thread != null) {
            thread.interrupt();
        }
        log.info("插件目录热感知已停止");
    }

    /** 登记根目录与全部一级子目录的监听（已登记的跳过）。 */
    private void registerAll() {
        registerDir(root);
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> s = Files.list(root)) {
            s.filter(Files::isDirectory).forEach(this::registerDir);
        } catch (Exception e) {
            log.debug("枚举插件子目录失败：{}", e.toString());
        }
    }

    @SuppressWarnings("removal")
    private void registerDir(Path dir) {
        if (registered.contains(dir) || !Files.isDirectory(dir)) {
            return;
        }
        WatchEvent.Kind<?>[] kinds = {
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY
        };
        try {
            // macOS 轮询 WatchService 用 HIGH 灵敏度降到 ~2s（API 标记 deprecated-for-removal，JDK25 仍可用）
            try {
                dir.register(watchService, kinds, com.sun.nio.file.SensitivityWatchEventModifier.HIGH);
            } catch (Throwable t) {
                dir.register(watchService, kinds);
            }
            registered.add(dir);
        } catch (Exception e) {
            log.debug("登记目录监听失败 {}：{}", dir, e.toString());
        }
    }

    private void loop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (Exception e) {
                break;   // 关闭或中断
            }
            key.pollEvents();   // 消费事件（具体内容不重要，任何变化都重扫）
            key.reset();
            // 去抖：吸收文件复制/批量变更的后续事件，再统一回调一次
            drainFor(DEBOUNCE_MS);
            if (!running) {
                break;
            }
            // 补登新出现的插件子目录，使其内 jar 的后续变化也能被感知
            synchronized (this) {
                registerAll();
            }
            log.info("检测到插件目录变化，触发重扫");
            try {
                onChange.run();
            } catch (Exception e) {
                log.warn("插件目录变化回调异常：{}", e.toString());
            }
        }
    }

    /** 在去抖窗口内吸收并丢弃后续事件，避免连续触发。 */
    private void drainFor(long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000;
        try {
            while (running) {
                long remain = deadline - System.nanoTime();
                if (remain <= 0) {
                    break;
                }
                WatchKey k = watchService.poll(remain / 1_000_000 + 1, TimeUnit.MILLISECONDS);
                if (k != null) {
                    k.pollEvents();
                    k.reset();
                }
            }
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    }
}
