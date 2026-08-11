package com.javaclaw.plugin;

import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskContext;
import com.javaclaw.platform.execution.TaskHandle;
import com.javaclaw.platform.execution.TaskSpec;
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
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 插件目录热感知。
 *
 * <p>同时监听插件根目录与一级插件目录，并用三层文件快照补偿平台 WatchService 的延迟。
 * 监听循环运行在全局托管 I/O 虚拟线程中；关闭时先拒绝后续回调、关闭 WatchService，
 * 再协作取消任务并有限等待承载线程退出。</p>
 */
final class PluginWatcher {

    private static final Logger log = LoggerFactory.getLogger(PluginWatcher.class);
    private static final long DEBOUNCE_MS = 500;
    private static final long SNAPSHOT_POLL_MS = 1_000;

    private final Path root;
    private final Runnable onChange;
    private final ManagedTaskExecutor taskExecutor;
    private final Map<Path, WatchKey> registered = new HashMap<>();

    private WatchService watchService;
    private TaskHandle<Void> watchTask;
    private volatile Thread carrier;
    private volatile CountDownLatch terminated = new CountDownLatch(0);
    private volatile boolean running;
    private Map<Path, FileStamp> snapshot = Map.of();

    PluginWatcher(Path root, Runnable onChange, ManagedTaskExecutor taskExecutor) {
        this.root = Objects.requireNonNull(root, "root");
        this.onChange = Objects.requireNonNull(onChange, "onChange");
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
    }

    /** 启动监听；重复调用不会创建第二个任务。 */
    synchronized void start() {
        if (running) {
            return;
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
            registerAll();
            snapshot = scanSnapshot();
        } catch (Exception failure) {
            closeWatchService();
            registered.clear();
            log.warn("插件目录热感知启动失败（将仅支持手动刷新）：{}", failure.toString());
            return;
        }

        running = true;
        terminated = new CountDownLatch(1);
        try {
            watchTask = taskExecutor.submit(TaskSpec.io("plugin-directory-watcher"), context -> {
                carrier = Thread.currentThread();
                try {
                    loop(context);
                } finally {
                    carrier = null;
                    terminated.countDown();
                }
                return null;
            });
            log.info("插件目录热感知已启动：{}（监听 {} 个目录）", root, registered.size());
        } catch (RuntimeException failure) {
            running = false;
            closeWatchService();
            registered.clear();
            terminated.countDown();
            log.warn("插件目录热感知任务提交失败（将仅支持手动刷新）：{}", failure.toString());
        }
    }

    /** 停止监听；关闭可重复调用，最长等待两秒。 */
    void stop() {
        TaskHandle<Void> stoppingTask;
        Thread stoppingCarrier;
        CountDownLatch stoppingLatch;
        synchronized (this) {
            running = false;
            stoppingTask = watchTask;
            stoppingCarrier = carrier;
            stoppingLatch = terminated;
            closeWatchService();
            if (stoppingTask != null) {
                stoppingTask.cancel();
            }
        }
        if (stoppingCarrier != Thread.currentThread()) {
            try {
                stoppingLatch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            watchTask = null;
            registered.clear();
            snapshot = Map.of();
        }
        log.info("插件目录热感知已停止");
    }

    private void loop(TaskContext context) {
        WatchService service = watchService;
        if (service == null) {
            return;
        }
        while (running && !context.cancellation().isCancellationRequested()) {
            boolean changed = pollForChange(service);
            Map<Path, FileStamp> currentSnapshot = scanSnapshot();
            if (!currentSnapshot.equals(snapshot)) {
                changed = true;
            }
            if (!changed) {
                continue;
            }

            drainFor(service, DEBOUNCE_MS, context);
            if (!running || context.cancellation().isCancellationRequested()) {
                break;
            }
            synchronized (this) {
                registerAll();
                snapshot = scanSnapshot();
            }
            log.info("检测到插件目录变化，触发重扫");
            try {
                onChange.run();
            } catch (Exception failure) {
                log.warn("插件目录变化回调异常：{}", failure.toString());
            }
        }
    }

    private boolean pollForChange(WatchService service) {
        try {
            WatchKey key = service.poll(SNAPSHOT_POLL_MS, TimeUnit.MILLISECONDS);
            if (key == null) {
                return false;
            }
            consume(key);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception closedOrFailed) {
            return false;
        }
    }

    private void registerAll() {
        registerDir(root);
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> paths = Files.list(root)) {
            paths.filter(Files::isDirectory).forEach(this::registerDir);
        } catch (Exception failure) {
            log.debug("枚举插件子目录失败：{}", failure.toString());
        }
    }

    private void registerDir(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
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
            if (service != null) {
                registered.put(normalized, normalized.register(service, kinds));
            }
        } catch (Exception failure) {
            log.debug("登记目录监听失败 {}：{}", normalized, failure.toString());
        }
    }

    private void consume(WatchKey key) {
        key.pollEvents();
        if (!key.reset()) {
            registered.values().removeIf(candidate -> candidate == key);
        }
    }

    private void drainFor(WatchService service, long millis, TaskContext context) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        try {
            while (running && !context.cancellation().isCancellationRequested()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                WatchKey key = service.poll(
                        TimeUnit.NANOSECONDS.toMillis(remaining) + 1, TimeUnit.MILLISECONDS);
                if (key != null) {
                    consume(key);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception closedOrFailed) {
            // stop() 通过关闭 WatchService 唤醒监听循环。
        }
    }

    private synchronized void closeWatchService() {
        if (watchService == null) {
            return;
        }
        try {
            watchService.close();
        } catch (Exception failure) {
            log.debug("关闭 WatchService 忽略异常：{}", failure.toString());
        } finally {
            watchService = null;
        }
    }

    /** 快照覆盖插件目录、顶层 jar 与 lib 依赖。 */
    private Map<Path, FileStamp> scanSnapshot() {
        if (!Files.isDirectory(root)) {
            return Map.of();
        }
        Map<Path, FileStamp> result = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root, 3)) {
            paths.sorted().forEach(path -> addStamp(result, path));
        } catch (Exception failure) {
            log.debug("扫描插件目录快照失败：{}", failure.toString());
        }
        return Map.copyOf(result);
    }

    private void addStamp(Map<Path, FileStamp> result, Path path) {
        try {
            boolean directory = Files.isDirectory(path);
            long size = directory ? 0L : Files.size(path);
            long modified = Files.getLastModifiedTime(path).toMillis();
            result.put(root.relativize(path), new FileStamp(directory, size, modified));
        } catch (Exception ignored) {
            // 文件可能正在复制或替换；下一轮快照会再次观察。
        }
    }

    private record FileStamp(boolean directory, long size, long modified) {
    }
}
