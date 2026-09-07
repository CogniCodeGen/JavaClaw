package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaclaw.nativehost.ffm.WindowsGuardPipe;
import com.javaclaw.nativehost.ffm.WindowsNetworkGuardNative;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;

/** 在应用进程保留 Broker 的清理权；helper 只能提出一次无参数关闭请求。 */
final class WindowsNetworkScope implements AutoCloseable {
    private final Path directory;
    private final Runnable closeTunnels;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean tunnelsClosed = new AtomicBoolean();
    private final Thread monitor;
    private volatile IOException failure;

    private WindowsNetworkScope(Path directory, Runnable closeTunnels) {
        this.directory = directory;
        this.closeTunnels = closeTunnels;
        monitor = Thread.ofVirtual().name("javaclaw-windows-proxy-cleanup").start(this::monitor);
    }

    static WindowsNetworkScope open(SandboxNetworkAccess access) throws IOException {
        try (WindowsGuardPipe ready = WindowsGuardPipe.connect()) {
            // 首次 UAC 在创建挂起目标之前完成；实际租约会重新核验 SCM 身份。
        }
        Path directory = Files.createTempDirectory("javaclaw-network-control-");
        try {
            WindowsNetworkGuardNative.protectControlDirectory(directory);
            return new WindowsNetworkScope(directory, access.closeTunnels());
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(directory);
            throw failure;
        }
    }

    Path directory() {
        return directory;
    }

    private void monitor() {
        try {
            while (!closed.get()) {
                if (Files.isRegularFile(directory.resolve(WindowsProxyCleanup.REQUEST), LinkOption.NOFOLLOW_LINKS)) {
                    closeTunnels();
                    Files.write(
                            directory.resolve(WindowsProxyCleanup.ACKNOWLEDGED),
                            new byte[0],
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE);
                    return;
                }
                WindowsProxyCleanup.pause();
            }
        } catch (IOException | RuntimeException problem) {
            if (!closed.get()) {
                failure = new IOException("NETWORK_GUARD_TUNNEL_CLEANUP_FAILED", problem);
            }
        }
    }

    private synchronized void closeTunnels() {
        if (!tunnelsClosed.get()) {
            closeTunnels.run();
            tunnelsClosed.set(true);
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        monitor.interrupt();
        try {
            monitor.join();
            closeTunnels();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("NETWORK_GUARD_CONTROL_INTERRUPTED", interrupted);
        } catch (UncheckedIOException problem) {
            throw problem.getCause();
        } finally {
            Files.deleteIfExists(directory.resolve(WindowsProxyCleanup.REQUEST));
            Files.deleteIfExists(directory.resolve(WindowsProxyCleanup.ACKNOWLEDGED));
            Files.deleteIfExists(directory.resolve(WindowsProxyCleanup.TERMINATE));
            Files.deleteIfExists(directory);
        }
        if (failure != null) {
            throw failure;
        }
    }
}
