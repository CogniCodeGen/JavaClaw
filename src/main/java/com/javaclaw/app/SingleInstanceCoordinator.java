package com.javaclaw.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 按数据目录隔离的单实例协调器。主实例持有文件锁并监听回环端口，
 * 后续进程只发送“显示主窗口”后退出，不进入 JavaFX/数据库/Quartz 初始化。
 */
public final class SingleInstanceCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SingleInstanceCoordinator.class);
    private static final String LOCK_FILE = "javaclaw.instance.lock";
    private static final String ENDPOINT_FILE = "javaclaw.instance.endpoint";
    private static final String SHOW_COMMAND = "SHOW";
    private static final String BUILD_MISMATCH_COMMAND = "BUILD_MISMATCH";
    private static final String OK_RESPONSE = "OK";
    private static final String RESTART_REQUIRED_RESPONSE = "RESTART_REQUIRED";
    private static final AtomicReference<SingleInstanceCoordinator> CURRENT = new AtomicReference<>();

    private final Path endpointFile;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final ServerSocket server;
    private final String token;
    private final String buildFingerprint;
    private final AtomicReference<Runnable> showHandler = new AtomicReference<>();
    private final AtomicReference<Runnable> buildMismatchHandler = new AtomicReference<>();
    private final AtomicBoolean pendingShow = new AtomicBoolean(false);
    private final AtomicBoolean pendingBuildMismatch = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread listenerThread;

    private SingleInstanceCoordinator(Path endpointFile, FileChannel lockChannel,
                                      FileLock lock, ServerSocket server, String token,
                                      String buildFingerprint)
            throws IOException {
        this.endpointFile = endpointFile;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.server = server;
        this.token = token;
        this.buildFingerprint = normalizeFingerprint(buildFingerprint);
        try {
            writeEndpoint();
        } catch (IOException failure) {
            try {
                server.close();
            } catch (IOException closeFailure) {
                log.debug("端点写入失败后关闭单实例服务端失败", closeFailure);
            }
            throw failure;
        }
        this.listenerThread = new Thread(this::listen, "single-instance-listener");
        this.listenerThread.setDaemon(true);
        this.listenerThread.start();
    }

    /**
     * 获取主实例资格。返回 {@code null} 表示已通知现有实例，调用进程应立即退出。
     */
    public static SingleInstanceCoordinator acquire(Path dataDirectory) throws IOException {
        return acquire(dataDirectory, "");
    }

    /**
     * Acquires the primary-instance role for one data directory and publishes the running build.
     * A secondary process with a different non-empty fingerprint asks the primary to show a
     * restart-required notice instead of silently treating the old JVM as the new build.
     */
    public static SingleInstanceCoordinator acquire(Path dataDirectory, String buildFingerprint)
            throws IOException {
        Path dataDir = dataDirectory.toAbsolutePath().normalize();
        String normalizedFingerprint = normalizeFingerprint(buildFingerprint);
        Files.createDirectories(dataDir);
        Path lockFile = dataDir.resolve(LOCK_FILE);
        Path endpointFile = dataDir.resolve(ENDPOINT_FILE);
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            try {
                acquired = channel.tryLock();
            } catch (OverlappingFileLockException alreadyHeldInThisJvm) {
                // 单元测和某些启动器可能在同一 JVM 内模拟第二实例。
            }
            if (acquired == null) {
                channel.close();
                NotifyResult notified = notifyExisting(endpointFile, normalizedFingerprint);
                if (notified == NotifyResult.FAILED) {
                    log.warn("已有 JavaClaw 实例，但主窗口通知未获得回应");
                } else if (notified == NotifyResult.RESTART_REQUIRED) {
                    log.warn("已有 JavaClaw 实例运行不同构建，已提示完整退出后重新启动");
                } else if (notified == NotifyResult.LEGACY_SHOWN) {
                    log.warn("已有 JavaClaw 实例未提供构建指纹；已唤起旧窗口，请完整退出后重新启动一次");
                }
                return null;
            }

            ServerSocket server = new ServerSocket();
            try {
                server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
            } catch (IOException bindFailure) {
                try {
                    server.close();
                } catch (IOException closeFailure) {
                    log.debug("绑定失败后关闭单实例服务端失败", closeFailure);
                }
                throw bindFailure;
            }
            String token = UUID.randomUUID().toString();
            SingleInstanceCoordinator coordinator = new SingleInstanceCoordinator(
                    endpointFile, channel, acquired, server, token, normalizedFingerprint);
            if (!CURRENT.compareAndSet(null, coordinator)) {
                coordinator.close();
                throw new IOException("当前 JVM 已存在单实例协调器");
            }
            log.info("已获取 JavaClaw 单实例锁，数据目录: {}", dataDir);
            return coordinator;
        } catch (IOException | RuntimeException failure) {
            if (acquired != null && acquired.isValid()) {
                try {
                    acquired.release();
                } catch (IOException releaseFailure) {
                    log.debug("启动失败后释放单实例文件锁失败", releaseFailure);
                }
            }
            if (channel.isOpen()) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    log.debug("启动失败后关闭单实例锁文件失败", closeFailure);
                }
            }
            throw failure;
        }
    }

    public static Optional<SingleInstanceCoordinator> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void closeCurrent() {
        SingleInstanceCoordinator coordinator = CURRENT.get();
        if (coordinator != null) coordinator.close();
    }

    /** 窗口尚未创建时的通知会被缓存，注册后立即补执行一次。 */
    public void setShowHandler(Runnable handler) {
        showHandler.set(handler);
        if (handler != null && pendingShow.compareAndSet(true, false)) invokeHandler(handler);
    }

    /** Registers the UI action shown when another process was launched from a different build. */
    public void setBuildMismatchHandler(Runnable handler) {
        buildMismatchHandler.set(handler);
        if (handler != null && pendingBuildMismatch.compareAndSet(true, false)) {
            invokeHandler(handler);
        }
    }

    private void listen() {
        while (!closed.get()) {
            try (Socket socket = server.accept();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                         socket.getOutputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(1500);
                String suppliedToken = reader.readLine();
                String command = reader.readLine();
                if (token.equals(suppliedToken) && SHOW_COMMAND.equals(command)) {
                    dispatchShow();
                    writer.write(OK_RESPONSE + "\n");
                } else if (token.equals(suppliedToken)
                        && BUILD_MISMATCH_COMMAND.equals(command)) {
                    dispatchBuildMismatch();
                    writer.write(RESTART_REQUIRED_RESPONSE + "\n");
                } else {
                    writer.write("DENIED\n");
                }
                writer.flush();
            } catch (IOException failure) {
                if (!closed.get()) log.warn("单实例通知端点异常: {}", failure.getMessage());
            }
        }
    }

    private void dispatchShow() {
        Runnable handler = showHandler.get();
        if (handler != null) {
            invokeHandler(handler);
            return;
        }
        pendingShow.set(true);
        // 补查注册竞态：注册可能恰好发生在 get() 与 set(true) 之间。
        handler = showHandler.get();
        if (handler != null && pendingShow.compareAndSet(true, false)) invokeHandler(handler);
    }

    private void dispatchBuildMismatch() {
        Runnable handler = buildMismatchHandler.get();
        if (handler != null) {
            invokeHandler(handler);
            return;
        }
        pendingBuildMismatch.set(true);
        handler = buildMismatchHandler.get();
        if (handler != null && pendingBuildMismatch.compareAndSet(true, false)) {
            invokeHandler(handler);
        }
    }

    private static void invokeHandler(Runnable handler) {
        try {
            handler.run();
        } catch (RuntimeException failure) {
            log.warn("唤起主窗口失败", failure);
        }
    }

    private void writeEndpoint() throws IOException {
        Path temp = endpointFile.resolveSibling(endpointFile.getFileName() + ".tmp-" + token);
        String endpoint = token + "\n" + server.getLocalPort() + "\n"
                + buildFingerprint + "\n";
        Files.writeString(temp, endpoint, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Files.move(temp, endpointFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, endpointFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static NotifyResult notifyExisting(Path endpointFile, String localFingerprint) {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                List<String> lines = Files.readAllLines(endpointFile, StandardCharsets.UTF_8);
                if (lines.size() < 2) throw new IOException("端点文件不完整");
                String token = lines.get(0).trim();
                int port = Integer.parseInt(lines.get(1).trim());
                String remoteFingerprint = lines.size() >= 3 ? lines.get(2).trim() : "";
                if (token.isBlank() || port < 1 || port > 65535) throw new IOException("端点数据无效");
                boolean mismatch = !localFingerprint.isBlank()
                        && !remoteFingerprint.isBlank()
                        && !localFingerprint.equals(remoteFingerprint);
                boolean legacy = !localFingerprint.isBlank() && remoteFingerprint.isBlank();
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300);
                    socket.setSoTimeout(1000);
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                            socket.getOutputStream(), StandardCharsets.UTF_8));
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.UTF_8));
                    writer.write(token);
                    writer.write('\n');
                    writer.write(mismatch ? BUILD_MISMATCH_COMMAND : SHOW_COMMAND);
                    writer.write('\n');
                    writer.flush();
                    String response = reader.readLine();
                    if (mismatch && RESTART_REQUIRED_RESPONSE.equals(response)) {
                        return NotifyResult.RESTART_REQUIRED;
                    }
                    if (!OK_RESPONSE.equals(response)) return NotifyResult.FAILED;
                    return legacy ? NotifyResult.LEGACY_SHOWN : NotifyResult.SHOWN;
                }
            } catch (IOException | NumberFormatException notReady) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return NotifyResult.FAILED;
                }
            }
        }
        return NotifyResult.FAILED;
    }

    private static String normalizeFingerprint(String fingerprint) {
        return fingerprint == null ? "" : fingerprint.strip();
    }

    private enum NotifyResult { SHOWN, LEGACY_SHOWN, RESTART_REQUIRED, FAILED }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        CURRENT.compareAndSet(this, null);
        try {
            server.close();
        } catch (IOException closeFailure) {
            log.debug("关闭单实例服务端失败", closeFailure);
        }
        listenerThread.interrupt();
        try {
            if (lock.isValid()) lock.release();
        } catch (IOException failure) {
            log.debug("释放单实例文件锁失败: {}", failure.getMessage());
        }
        try {
            lockChannel.close();
        } catch (IOException closeFailure) {
            log.debug("关闭单实例锁文件失败", closeFailure);
        }
        try {
            List<String> lines = Files.exists(endpointFile)
                    ? Files.readAllLines(endpointFile, StandardCharsets.UTF_8) : List.of();
            if (!lines.isEmpty() && token.equals(lines.getFirst().trim())) {
                Files.deleteIfExists(endpointFile);
            }
        } catch (IOException failure) {
            log.debug("删除单实例端点文件失败: {}", failure.getMessage());
        }
    }
}
