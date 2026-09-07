package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

import com.javaclaw.nativehost.network.SandboxNetworkAccess;

/** 批处理 helper 与父进程之间的单用途撤销确认；目录由父进程以受保护 DACL 创建。 */
final class WindowsProxyCleanup implements Runnable {
    static final String REQUEST = "close-request";
    static final String ACKNOWLEDGED = "close-ack";
    static final String TERMINATE = "terminate-request";
    private final Path directory;

    WindowsProxyCleanup(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    @Override
    public void run() {
        try {
            request();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private void request() throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("NETWORK_GUARD_CONTROL_DIRECTORY_MISSING");
        }
        Path request = directory.resolve(REQUEST);
        if (!Files.exists(request, LinkOption.NOFOLLOW_LINKS)) {
            Files.write(request, new byte[0], StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(directory.resolve(ACKNOWLEDGED), LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            pause();
        }
        throw new IOException("NETWORK_GUARD_TUNNEL_CLEANUP_TIMEOUT");
    }

    static void pause() throws IOException {
        try {
            Thread.sleep(10);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("NETWORK_GUARD_CONTROL_INTERRUPTED", interrupted);
        }
    }

    static void terminateBeforeHelper(Process process, ValidatedSandboxCommand command) {
        SandboxNetworkAccess access = command.networkAccess();
        if (access.mode() != SandboxNetworkAccess.Mode.PROXY_ONLY
                || !(PlatformSandboxCommandBuilder.current() instanceof WindowsSandboxCommandBuilder)) {
            return;
        }
        Path directory = access.controlDirectory().orElseThrow();
        boolean interrupted = Thread.interrupted();
        try {
            Path request = directory.resolve(TERMINATE);
            if (!Files.exists(request, LinkOption.NOFOLLOW_LINKS)) {
                Files.write(request, new byte[0], StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
            // helper 先撤允许，再等待父进程关闭隧道；服务的独占 Job 最后结束，异常仍执行外层强制回收。
            process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (IOException failure) {
            throw new UncheckedIOException("NETWORK_GUARD_TERMINATION_CONTROL_FAILED", failure);
        } catch (InterruptedException failure) {
            interrupted = true;
            throw new IllegalStateException("NETWORK_GUARD_TERMINATION_INTERRUPTED", failure);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
