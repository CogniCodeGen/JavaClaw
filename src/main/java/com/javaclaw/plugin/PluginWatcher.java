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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
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
    /** 标准 WatchService 在部分平台延迟较高；周期快照保证变化在此窗口内被发现。 */
    private static final long SNAPSHOT_POLL_MS = 1_000;

    private final Path root;
    private final Runnable onChange;

    private WatchService watchService;
    private Thread thread;
    private volatile boolean running = false;
    /** 已登记监听的目录及 key（根 + 各插件子目录），失效 key 会被移除以允许同名目录重建。 */
    private final Map<Path, WatchKey> registered = new HashMap<>();
    private Map<Path, FileStamp> snapshot = Map.of();

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
            snapshot = scanSnapshot();
        } catch (Exception e) {
            closeWatchService();
            registered.clear();
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
    void stop() {
        Thread stoppingThread;
        synchronized (this) {
            running = false;
            stoppingThread = thread;
            closeWatchService();
            if (stoppingThread != null) stoppingThread.interrupt();
        }
        if (stoppingThread != null && stoppingThread != Thread.currentThread()) {
            try {
                stoppingThread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            thread = null;
            registered.clear();
            snapshot = Map.of();
        }
        log.info("插件目录热感知已停止");
    }

    private void closeWatchService() {
        if (watchService == null) return;
        try {
            watchService.close();
        } catch (Exception e) {
            log.debug("关闭 WatchService 忽略异常：{}", e.toString());
        } finally {
            watchService = null;
        }
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

    private void registerDir(Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        WatchKey existing = registered.get(normalized);
        if ((existing != null && existing.isValid()) || !Files.isDirectory(normalized)) {
            return;
        }
        WatchEvent.Kind<?>[] kinds = {
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY
        };
        try {
            WatchService service = watchService;
            if (service == null) return;
            registered.put(normalized, normalized.register(service, kinds));
        } catch (Exception e) {
            log.debug("登记目录监听失败 {}：{}", normalized, e.toString());
        }
    }

    private void loop() {
        WatchService service = watchService;
        if (service == null) return;
        while (running) {
            boolean changed = false;
            try {
                WatchKey key = service.poll(SNAPSHOT_POLL_MS, TimeUnit.MILLISECONDS);
                if (key != null) {
                    consume(key);
                    changed = true;
                }
            } catch (Exception e) {
                break;   // 关闭或中断
            }

            Map<Path, FileStamp> currentSnapshot = scanSnapshot();
            if (!currentSnapshot.equals(snapshot)) changed = true;
            if (!changed) continue;

            // 去抖：吸收文件复制/批量变更的后续事件，再统一回调一次
            drainFor(service, DEBOUNCE_MS);
            if (!running) {
                break;
            }
            // 补登新出现的插件子目录，使其内 jar 的后续变化也能被感知
            synchronized (this) {
                registerAll();
                snapshot = scanSnapshot();
            }
            log.info("检测到插件目录变化，触发重扫");
            try {
                onChange.run();
            } catch (Exception e) {
                log.warn("插件目录变化回调异常：{}", e.toString());
            }
        }
    }

    private void consume(WatchKey key) {
        key.pollEvents();
        if (!key.reset()) {
            registered.values().removeIf(candidate -> candidate == key);
        }
    }

    /** 在去抖窗口内吸收并丢弃后续事件，避免连续触发。 */
    private void drainFor(WatchService service, long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000;
        try {
            while (running) {
                long remain = deadline - System.nanoTime();
                if (remain <= 0) {
                    break;
                }
                WatchKey k = service.poll(remain / 1_000_000 + 1, TimeUnit.MILLISECONDS);
                if (k != null) {
                    consume(k);
                }
            }
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 快照覆盖插件根下三层：插件目录、顶层 jar 以及 lib/ 依赖。 */
    private Map<Path, FileStamp> scanSnapshot() {
        if (!Files.isDirectory(root)) return Map.of();
        Map<Path, FileStamp> result = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root, 3)) {
            paths.sorted().forEach(path -> {
                try {
                    boolean directory = Files.isDirectory(path);
                    long size = directory ? 0L : Files.size(path);
                    long modified = Files.getLastModifiedTime(path).toMillis();
                    result.put(root.relativize(path), new FileStamp(directory, size, modified));
                } catch (Exception ignored) {
                    // 文件可能正在被复制/替换；下一轮快照会再次观察。
                }
            });
        } catch (Exception e) {
            log.debug("扫描插件目录快照失败：{}", e.toString());
        }
        return Map.copyOf(result);
    }

    private record FileStamp(boolean directory, long size, long modified) {
    }
}
