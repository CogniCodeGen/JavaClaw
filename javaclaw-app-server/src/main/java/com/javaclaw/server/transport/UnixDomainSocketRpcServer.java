package com.javaclaw.server.transport;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.StreamRpcConnection;

/** 在单个本机 Unix Domain Socket 上承载相互隔离的 Protocol v3 会话。 */
public final class UnixDomainSocketRpcServer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(UnixDomainSocketRpcServer.class);
    private static final int MAXIMUM_CONNECTIONS = 128;
    private static final Set<PosixFilePermission> OWNER_ONLY =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    private final Path socketPath;
    private final ServerSocketChannel listener;
    private final Object socketFileKey;
    private final Semaphore permits = new Semaphore(MAXIMUM_CONNECTIONS);
    private final Set<SocketChannel> connections = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    private UnixDomainSocketRpcServer(Path socketPath, ServerSocketChannel listener, Object socketFileKey) {
        this.socketPath = socketPath;
        this.listener = listener;
        this.socketFileKey = socketFileKey;
    }

    /**
     * 在不存在的绝对路径创建仅当前用户可访问的监听 socket。
     *
     * @param requestedPath socket 绝对路径
     * @return 已绑定服务端
     * @throws IOException 路径已存在、权限不安全或绑定失败
     */
    public static UnixDomainSocketRpcServer bind(Path requestedPath) throws IOException {
        Path socketPath = requireAbsolute(requestedPath);
        Path parent = Objects.requireNonNull(socketPath.getParent(), "socketPath parent");
        prepareParent(parent);
        removeStaleSocket(socketPath, parent);
        ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        boolean ready = false;
        try {
            listener.bind(UnixDomainSocketAddress.of(socketPath));
            Files.setPosixFilePermissions(socketPath, OWNER_ONLY);
            BasicFileAttributes attributes = socketAttributes(socketPath);
            ready = true;
            return new UnixDomainSocketRpcServer(socketPath, listener, attributes.fileKey());
        } finally {
            if (!ready) {
                listener.close();
                Files.deleteIfExists(socketPath);
            }
        }
    }

    /**
     * 接受连接并在虚拟线程中运行独立会话，直到服务端关闭。
     *
     * @param json 规范 JSON codec
     * @param sessions 逐连接会话处理边界
     * @throws IOException 监听器意外失败
     */
    public void serve(CanonicalJson json, RpcSessionHandler sessions) throws IOException {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(sessions, "sessions");
        while (!closed.get()) {
            SocketChannel channel;
            try {
                channel = listener.accept();
            } catch (IOException failure) {
                if (closed.get()) {
                    return;
                }
                throw failure;
            }
            if (!permits.tryAcquire()) {
                channel.close();
                continue;
            }
            connections.add(channel);
            Thread.ofVirtual().name("javaclaw-uds-session").start(() -> serveClient(json, sessions, channel));
        }
    }

    private void serveClient(CanonicalJson json, RpcSessionHandler sessions, SocketChannel channel) {
        try (SocketChannel ownedChannel = channel;
                StreamRpcConnection connection = new StreamRpcConnection(
                        Channels.newInputStream(ownedChannel),
                        Channels.newOutputStream(ownedChannel),
                        new JsonRpcCodec(json))) {
            sessions.serve(connection);
        } catch (IOException failure) {
            if (!closed.get()) {
                LOGGER.debug("UDS client connection ended with an I/O failure", failure);
            }
        } finally {
            connections.remove(channel);
            permits.release();
        }
    }

    private static Path requireAbsolute(Path requestedPath) {
        Path path = Objects.requireNonNull(requestedPath, "requestedPath");
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Unix Domain Socket path must be absolute");
        }
        Path normalized = path.normalize();
        if (normalized.getFileName() == null) {
            throw new IllegalArgumentException("Unix Domain Socket path must name a file");
        }
        return normalized;
    }

    private static BasicFileAttributes socketAttributes(Path path) throws IOException {
        BasicFileAttributes attributes =
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isOther()) {
            throw new IOException("bound path is not a Unix Domain Socket: " + path);
        }
        return attributes;
    }

    private static void prepareParent(Path parent) throws IOException {
        boolean created = !Files.exists(parent, LinkOption.NOFOLLOW_LINKS);
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(parent)) {
            throw new IOException("Unix Domain Socket parent must not be a symbolic link: " + parent);
        }
        if (created) {
            Files.setPosixFilePermissions(parent, PRIVATE_DIRECTORY);
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(parent);
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw new IOException("Unix Domain Socket parent is writable by another user: " + parent);
        }
    }

    private static void removeStaleSocket(Path socketPath, Path parent) throws IOException {
        if (!Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        BasicFileAttributes before = socketAttributes(socketPath);
        UserPrincipal socketOwner = Files.getOwner(socketPath, LinkOption.NOFOLLOW_LINKS);
        UserPrincipal parentOwner = Files.getOwner(parent, LinkOption.NOFOLLOW_LINKS);
        if (!socketOwner.equals(parentOwner)) {
            throw new IOException("Unix Domain Socket is owned by another user: " + socketPath);
        }
        if (acceptsConnections(socketPath)) {
            throw new IOException("Unix Domain Socket already has an active server: " + socketPath);
        }
        BasicFileAttributes current = socketAttributes(socketPath);
        if (!Objects.equals(before.fileKey(), current.fileKey())) {
            throw new IOException("Unix Domain Socket changed while checking staleness: " + socketPath);
        }
        Files.delete(socketPath);
    }

    private static boolean acceptsConnections(Path socketPath) {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            return true;
        } catch (IOException unavailable) {
            return false;
        }
    }

    /** 关闭监听器和全部连接，并且只删除仍属于本实例的 socket 文件。 */
    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        IOException failure = closeListener();
        for (SocketChannel connection : connections) {
            try {
                connection.close();
            } catch (IOException closeFailure) {
                failure = append(failure, closeFailure);
            }
        }
        connections.clear();
        try {
            deleteOwnedSocket();
        } catch (IOException deleteFailure) {
            failure = append(failure, deleteFailure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private IOException closeListener() {
        try {
            listener.close();
            return null;
        } catch (IOException failure) {
            return failure;
        }
    }

    private void deleteOwnedSocket() throws IOException {
        if (!Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        BasicFileAttributes attributes = socketAttributes(socketPath);
        if (!Objects.equals(socketFileKey, attributes.fileKey())) {
            throw new IOException("refusing to delete a replaced Unix Domain Socket: " + socketPath);
        }
        Files.delete(socketPath);
    }

    private static IOException append(IOException prior, IOException next) {
        if (prior == null) {
            return next;
        }
        prior.addSuppressed(next);
        return prior;
    }
}
