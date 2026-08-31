package com.javaclaw.server.transport;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.net.ExtendedSocketOptions;
import jdk.net.UnixDomainPrincipal;

import com.javaclaw.agent.runtime.AgentRuntime;
import com.javaclaw.core.api.ThreadEvent;

/** Current-user-only UDS transport for macOS and Linux. Never opens a TCP listener. */
public final class LocalSocketAppServer implements AutoCloseable {
    private static final Set<PosixFilePermission> OWNER_DIRECTORY = Set.copyOf(EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    private static final Set<PosixFilePermission> OWNER_SOCKET =
            Set.copyOf(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

    private final Path socketPath;
    private final Path lockPath;
    private final StdioAppServer connectionServer;
    private final ExecutorService clients = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean serving = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ServerSocketChannel listener;
    private volatile FileChannel endpointLockChannel;
    private volatile FileLock endpointLock;

    /** 创建仅包含基础 Thread/Turn 能力的本地服务；socketPath 指向当前用户独占目录，直到 serve 才绑定端点。 */
    public static LocalSocketAppServer minimal(
            Path socketPath, AgentRuntime runtime, Flow.Publisher<ThreadEvent> events) {
        return new LocalSocketAppServer(socketPath, AppServerEndpointConfig.minimal(runtime, events, true));
    }

    /** 规范化 socketPath 并校验 UTF-8 路径不超过 100 字节；共享 config 的 Runtime，不在构造时绑定套接字。 */
    public LocalSocketAppServer(Path socketPath, AppServerEndpointConfig config) {
        this.socketPath = Objects.requireNonNull(socketPath, "socketPath")
                .toAbsolutePath()
                .normalize();
        this.lockPath =
                this.socketPath.resolveSibling(this.socketPath.getFileName().toString() + ".lock");
        if (this.socketPath.toString().getBytes(StandardCharsets.UTF_8).length > 100) {
            throw new IllegalArgumentException("Unix socket path exceeds the portable 100-byte limit");
        }
        this.connectionServer =
                new StdioAppServer(Objects.requireNonNull(config, "config").asLocalSocket());
    }

    /**
     * 阻塞接受本机连接，逐连接复核 SO_PEERCRED；无法确认当前用户身份时关闭连接。只能调用一次，退出时释放端点锁与自身套接字。
     *
     * @throws IOException 端点权限、独占锁、绑定或接收失败
     */
    public void serve() throws IOException {
        if (!serving.compareAndSet(false, true)) {
            throw new IllegalStateException("local socket server can only be served once");
        }
        requireUnixPlatform();
        try {
            prepareEndpoint();
            try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                listener = server;
                server.bind(UnixDomainSocketAddress.of(socketPath));
                Files.setPosixFilePermissions(socketPath, OWNER_SOCKET);
                while (!closed.get()) {
                    try {
                        SocketChannel channel = server.accept();
                        if (!currentUserPeer(channel)) {
                            channel.close();
                            continue;
                        }
                        try {
                            clients.submit(() -> serve(channel));
                        } catch (RuntimeException rejected) {
                            try {
                                channel.close();
                            } catch (IOException ignored) {
                            }
                            if (!closed.get()) {
                                throw rejected;
                            }
                        }
                    } catch (java.nio.channels.AsynchronousCloseException closedListener) {
                        if (!closed.get()) {
                            throw closedListener;
                        }
                    }
                }
            }
        } finally {
            listener = null;
            deleteOwnedSocket();
            releaseEndpointLock();
        }
    }

    private void serve(SocketChannel channel) {
        try (channel;
                var input = Channels.newReader(channel, StandardCharsets.UTF_8);
                var output = Channels.newWriter(channel, StandardCharsets.UTF_8)) {
            connectionServer.serve(input, output);
        } catch (IOException ignored) {
            // A local client may disconnect at any point; its session is isolated.
        }
    }

    /** UDS filesystem permissions protect connect-by-path; peer credentials protect accepted FDs. */
    private boolean currentUserPeer(SocketChannel channel) {
        try {
            if (!channel.supportedOptions().contains(ExtendedSocketOptions.SO_PEERCRED)) {
                return false;
            }
            UnixDomainPrincipal peer = channel.getOption(ExtendedSocketOptions.SO_PEERCRED);
            if (peer == null || peer.user() == null) {
                return false;
            }
            var endpointOwner = Files.getOwner(socketPath.getParent(), LinkOption.NOFOLLOW_LINKS);
            if (endpointOwner.equals(peer.user())) {
                return true;
            }
            String expected = endpointOwner.getName();
            String actual = peer.user().getName();
            return !expected.isBlank()
                    && (expected.equals(actual)
                            || expected.endsWith("\\" + actual)
                            || actual.endsWith("\\" + expected));
        } catch (IOException | UnsupportedOperationException | SecurityException failure) {
            return false;
        }
    }

    private void prepareEndpoint() throws IOException {
        Path parent = socketPath.getParent();
        if (parent == null) {
            throw new IOException("socket path has no parent");
        }
        if (Files.notExists(parent, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(parent);
            Files.setPosixFilePermissions(parent, OWNER_DIRECTORY);
        }
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("socket parent is not a directory: " + parent);
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS);
        if (!OWNER_DIRECTORY.equals(permissions)) {
            throw new IOException("socket directory is accessible by another user: " + parent);
        }
        String currentUser = System.getProperty("user.name", "");
        String owner = Files.getOwner(parent, LinkOption.NOFOLLOW_LINKS).getName();
        if (currentUser.isBlank() || !(owner.equals(currentUser) || owner.endsWith("\\" + currentUser))) {
            throw new IOException("socket directory is not owned by the current user: " + parent);
        }
        acquireEndpointLock(parent);
        if (Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes attributes =
                    Files.readAttributes(socketPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isOther()
                    || !Files.getOwner(socketPath, LinkOption.NOFOLLOW_LINKS)
                            .equals(Files.getOwner(parent, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("refusing to replace an unowned socket path: " + socketPath);
            }
            Files.delete(socketPath);
        }
    }

    private void acquireEndpointLock(Path parent) throws IOException {
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes attributes =
                    Files.readAttributes(lockPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()
                    || !Files.getOwner(lockPath, LinkOption.NOFOLLOW_LINKS)
                            .equals(Files.getOwner(parent, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("refusing to use an unowned socket lock: " + lockPath);
            }
        }
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(lockPath, OWNER_SOCKET);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException alreadyLocked) {
                lock = null;
            }
            if (lock == null) {
                throw new IOException("another App Server owns the socket endpoint: " + socketPath);
            }
            endpointLockChannel = channel;
            endpointLock = lock;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private void releaseEndpointLock() {
        FileLock lock = endpointLock;
        endpointLock = null;
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException ignored) {
            }
        }
        FileChannel channel = endpointLockChannel;
        endpointLockChannel = null;
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void deleteOwnedSocket() {
        FileLock lock = endpointLock;
        if (lock == null || !lock.isValid()) {
            return;
        }
        try {
            if (Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS)
                    && Files.readAttributes(socketPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                            .isOther()) {
                Files.delete(socketPath);
            }
        } catch (IOException ignored) {
        }
    }

    private static void requireUnixPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!os.contains("mac") && !os.contains("linux")) {
            throw new UnsupportedOperationException("Unix-domain transport is only available on macOS and Linux");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ServerSocketChannel current = listener;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
        clients.shutdownNow();
        deleteOwnedSocket();
    }
}
