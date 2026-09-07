package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaclaw.nativehost.network.SandboxNetworkAccess;

/**
 * 宿主侧命令代理 IPC，仅转发到冻结的 loopback Broker 端点，不解析远端目标。
 *
 * <p>独占私有 socket 目录和全部连接；关闭先切断控制通道，namespace Worker 随之结束命令树。 连接数有硬上限，缓冲区固定；真正的目标、字节、时限与 Turn 撤销由服务端 Broker 校验。
 */
final class LinuxProxyBridge implements AutoCloseable {
    private final Path directory;
    private final Path socket;
    private final InetSocketAddress endpoint;
    private final Runnable closeTunnels;
    private final ServerSocketChannel listener;
    private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<SocketChannel> connections = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(33);
    private final AtomicBoolean controlConnected = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private LinuxProxyBridge(Path directory, SandboxNetworkAccess access, ServerSocketChannel listener) {
        this.directory = directory;
        this.socket = directory.resolve("p");
        this.endpoint = access.proxyEndpoint().orElseThrow();
        this.closeTunnels = access.closeTunnels();
        this.listener = listener;
    }

    static LinuxProxyBridge open(SandboxNetworkAccess access) throws IOException {
        Path base = access.controlDirectory()
                .orElseThrow(
                        () -> new SecurityException("Linux proxy mode requires an application-owned IPC directory"));
        if (Files.isSymbolicLink(base) || !Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            throw new SecurityException("Linux proxy IPC root must be a real directory");
        }
        Path directory = Files.createTempDirectory(
                base.toRealPath(),
                "p-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        ServerSocketChannel listener = null;
        try {
            Path socket = directory.resolve("p");
            if (socket.toString().getBytes(StandardCharsets.UTF_8).length > 100) {
                throw new IOException("Linux proxy IPC path exceeds the portable Unix socket limit");
            }
            listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            listener.bind(UnixDomainSocketAddress.of(socket));
            LinuxProxyBridge bridge = new LinuxProxyBridge(directory, access, listener);
            bridge.tasks.submit(bridge::accept);
            return bridge;
        } catch (IOException | RuntimeException failure) {
            if (listener != null) {
                SocketRelayPump.close(listener);
            }
            Files.deleteIfExists(directory.resolve("p"));
            Files.deleteIfExists(directory);
            throw failure;
        }
    }

    Path directory() {
        return directory;
    }

    private void accept() {
        while (!closed.get()) {
            try {
                SocketChannel connection = listener.accept();
                if (!slots.tryAcquire()) {
                    connection.close();
                    continue;
                }
                connections.add(connection);
                tasks.submit(() -> serve(connection));
            } catch (IOException failure) {
                close();
            }
        }
    }

    private void serve(SocketChannel connection) {
        try (connection) {
            ByteBuffer header = ByteBuffer.allocate(1);
            if (connection.read(header) != 1) {
                return;
            }
            if (header.array()[0] == 0 && controlConnected.compareAndSet(false, true)) {
                while (connection.read(header.clear()) >= 0) {
                    // 控制通道不携带用户数据；EOF 表示 namespace 执行已结束。
                }
                close();
            } else if (header.array()[0] == 1) {
                proxy(connection);
            }
        } catch (IOException ignored) {
            // 对端结束或撤销已关闭通道，单条连接无需进入宿主日志。
        } finally {
            connections.remove(connection);
            slots.release();
        }
    }

    private void proxy(SocketChannel connection) throws IOException {
        try (SocketChannel upstream = SocketChannel.open(StandardProtocolFamily.INET)) {
            connections.add(upstream);
            try {
                upstream.connect(endpoint);
                SocketRelayPump.connect(connection, upstream, tasks);
            } finally {
                connections.remove(upstream);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        SocketRelayPump.close(listener);
        try {
            closeTunnels.run();
        } finally {
            // Broker 先撤销及切断远端隧道，再关闭控制通道触发 namespace 命令树终止。
            connections.forEach(SocketRelayPump::close);
            tasks.shutdownNow();
            try {
                Files.deleteIfExists(socket);
                Files.deleteIfExists(directory);
            } catch (IOException failure) {
                // 目录仅含 socket 且无可执行数据；保留拒绝连接的残留目录供应用启动清理。
            }
        }
    }
}
