package com.javaclaw.server.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.javaclaw.api.CancellationToken;

/**
 * 单操作 CONNECT 租约；关闭即撤销入口和所有已建立隧道，读取不会延长期限。
 *
 * <p>地址来源只能是 BrokerTarget 校验后的固定 IP，不能将主机名再次交给 Socket 解析。
 */
public final class CommandProxyLease implements AutoCloseable {
    private final CommandNetworkGrant grant;
    private final CancellationToken cancellation;
    private final CommandProxyBroker.RealtimeAuthorization authorization;
    private final Clock clock;
    private final BrokerTarget.HostResolver resolver;
    private final ServerSocket listener;
    private final CommandProxyBroker.SocketFactory socketFactory;
    private final BrokerDeadline lifetime;
    private final Object lifecycle = new Object();
    private final Semaphore slots;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong transferred = new AtomicLong();
    private Runnable onClose = () -> {};

    CommandProxyLease(
            CommandNetworkGrant grant,
            CancellationToken cancellation,
            CommandProxyBroker.RealtimeAuthorization authorization,
            Clock clock,
            BrokerTarget.HostResolver resolver,
            CommandProxyBroker.SocketFactory socketFactory)
            throws IOException {
        this.grant = Objects.requireNonNull(grant, "grant");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.socketFactory = Objects.requireNonNull(socketFactory, "socketFactory");
        lifetime = BrokerDeadline.start(Duration.between(clock.instant(), grant.expiresAt()));
        try {
            check();
        } catch (Exception denied) {
            throw new IOException("命令网络租约授权已失效", denied);
        }
        slots = new Semaphore(grant.limits().maximumConnections());
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 16);
        listener.setSoTimeout(200);
    }

    void onClose(Runnable callback) {
        onClose = callback;
    }

    void start() {
        Thread.ofVirtual().name("javaclaw-command-proxy-" + grant.id()).start(this::accept);
    }

    /** @return 固定租约标识 */
    public String id() {
        return grant.id();
    }

    /** @return 仅交给 Native Hosts 的精确本机入口 */
    public InetSocketAddress endpoint() {
        return (InetSocketAddress) listener.getLocalSocketAddress();
    }

    /** @return 已为双向转发预留的字节数；失败写入仍消耗预算，不包含超额拒绝的数据 */
    public long transferredBytes() {
        return transferred.get();
    }

    /** @return 租约是否仍拥有开放入口 */
    public boolean active() {
        return !closed.get();
    }

    private void accept() {
        try {
            while (!closed.get()) {
                check();
                try {
                    Socket socket = listener.accept();
                    if (!slots.tryAcquire()) {
                        socket.close();
                        continue;
                    }
                    try {
                        register(socket);
                        Thread.ofVirtual().name("javaclaw-connect").start(() -> tunnel(socket));
                    } catch (IOException stopped) {
                        slots.release();
                        throw stopped;
                    }
                } catch (SocketTimeoutException pending) {
                    // 即使没有新连接也轮询撤权和绝对期限，已建立隧道不会逃离租约。
                }
            }
        } catch (Exception stopped) {
            close();
        }
    }

    private void tunnel(Socket client) {
        Socket upstream = null;
        boolean established = false;
        try {
            client.setSoTimeout(1_000);
            String authority = ConnectRequestReader.read(client.getInputStream());
            check();
            BrokerDeadline deadline = lifetime;
            BrokerTarget target = BrokerTarget.resolve(
                    URI.create("https://" + authority + "/"), grant.destinations(), resolver, deadline, cancellation);
            check();
            upstream = connect(target, deadline);
            check();
            client.getOutputStream()
                    .write("HTTP/1.1 200 Connection Established\r\n\r\n"
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            established = true;
            relay(client, upstream);
        } catch (Exception rejected) {
            if (!established) {
                reject(client);
            }
        } finally {
            closeSocket(upstream);
            closeSocket(client);
            slots.release();
        }
    }

    private Socket connect(BrokerTarget target, BrokerDeadline deadline) throws Exception {
        IOException last = null;
        for (InetAddress address : target.addresses()) {
            check();
            Socket socket = socketFactory.create();
            register(socket);
            try {
                socket.connect(
                        new InetSocketAddress(address, target.port()), deadline.timeoutMillis(cancellation, 2000));
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(200);
                return socket;
            } catch (IOException failure) {
                closeSocket(socket);
                last = failure;
            }
        }
        throw new IOException("命令代理无法连接已验证的地址", last);
    }

    private void relay(Socket client, Socket upstream) throws Exception {
        client.setSoTimeout(200);
        AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        Thread uploading = Thread.ofVirtual().name("javaclaw-connect-upload").start(() -> {
            try {
                copy(client.getInputStream(), upstream.getOutputStream(), lastActivity);
                upstream.shutdownOutput();
            } catch (Exception stopped) {
                closeSocket(client);
                closeSocket(upstream);
            }
        });
        try {
            copy(upstream.getInputStream(), client.getOutputStream(), lastActivity);
        } finally {
            closeSocket(client);
            closeSocket(upstream);
            // 关闭 Socket 已解除网络等待；不能中断可能正在访问共享 H2 文件通道的权限检查。
            // 仍使用有界 join，迟到检查返回后只能触及已关闭的两个 Socket。
            uploading.join(Duration.ofSeconds(2));
        }
    }

    private void copy(InputStream input, OutputStream output, AtomicLong lastActivity) throws Exception {
        byte[] buffer = new byte[16 * 1024];
        while (true) {
            check();
            if (System.nanoTime() - lastActivity.get()
                    > grant.limits().idleTimeout().toNanos()) {
                throw new IOException("命令代理连接空闲超时");
            }
            int count;
            try {
                count = input.read(buffer);
            } catch (SocketTimeoutException pending) {
                continue;
            }
            if (count < 0) {
                return;
            }
            check();
            reserveBytes(count);
            output.write(buffer, 0, count);
            output.flush();
            lastActivity.set(System.nanoTime());
        }
    }

    private void reserveBytes(int count) throws IOException {
        long observed;
        do {
            observed = transferred.get();
            if (count > grant.limits().maximumBytes() - observed) {
                close();
                throw new IOException("命令代理达到传输上限");
            }
        } while (!transferred.compareAndSet(observed, observed + count));
    }

    private void check() throws Exception {
        cancellation.throwIfCancelled();
        if (closed.get() || !clock.instant().isBefore(grant.expiresAt())) {
            throw new SecurityException("命令网络租约已结束");
        }
        lifetime.remaining(cancellation);
        authorization.check();
    }

    private void register(Socket socket) throws IOException {
        synchronized (lifecycle) {
            if (closed.get()) {
                socket.close();
                throw new IOException("命令代理已关闭");
            }
            sockets.add(socket);
        }
    }

    private static void reject(Socket client) {
        try {
            client.getOutputStream()
                    .write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (IOException ignored) {
            // 拒绝后连接必定关闭；不将请求头、代理正文或目标错误泄漏到日志。
        }
    }

    private void closeSocket(Socket socket) {
        if (socket == null) {
            return;
        }
        sockets.remove(socket);
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * 先关闭入口再关闭全部隧道；重复调用等待同一清理完成。
     *
     * <p>登记连接和关闭共享生命周期锁，关闭快照后不能出现逃离租约的晚到 Socket。
     */
    @Override
    public void close() {
        synchronized (lifecycle) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                listener.close();
            } catch (IOException ignored) {
            }
            for (Socket socket : Set.copyOf(sockets)) {
                closeSocket(socket);
            }
            onClose.run();
        }
    }
}
