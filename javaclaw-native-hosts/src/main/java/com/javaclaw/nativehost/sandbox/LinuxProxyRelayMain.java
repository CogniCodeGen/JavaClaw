package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import com.javaclaw.nativehost.ffm.NativeResourceLimits;

/** Linux network namespace 内的固定 TCP 到 Unix IPC relay；不解析 DNS 或目标仓库。 */
public final class LinuxProxyRelayMain {
    private LinuxProxyRelayMain() {}

    /**
     * 在独立进程组启动 relay，避免 PTY 的前台信号破坏网络监管通道。
     *
     * @param arguments 固定 IPC socket 路径与代理端口
     * @throws Exception 创建监听、连接监管通道或原生进程组失败
     */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Linux relay requires an IPC socket and port");
        }
        Path socket = Path.of(arguments[0]);
        int port = Integer.parseInt(arguments[1]);
        if (!socket.isAbsolute() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("invalid Linux proxy relay endpoint");
        }
        ProcessHandle owner = ProcessHandle.current().parent().orElseThrow();
        NativeResourceLimits.createProcessGroup(ProcessHandle.current().pid());
        try (Relay relay = new Relay(socket, port)) {
            System.out.write(1);
            System.out.flush();
            relay.awaitHost();
        } finally {
            // 控制通道消失后结束原始命令，bubblewrap 的 PID namespace reaper 随即清空其余后代。
            owner.destroyForcibly();
        }
    }

    private static final class Relay implements AutoCloseable {
        private final Path socket;
        private final ServerSocketChannel listener;
        private final SocketChannel control;
        private final Set<SocketChannel> connections = ConcurrentHashMap.newKeySet();
        private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
        private final Semaphore slots = new Semaphore(32);

        Relay(Path socket, int port) throws IOException {
            this.socket = socket;
            listener = ServerSocketChannel.open(StandardProtocolFamily.INET);
            SocketChannel opened = null;
            try {
                listener.bind(new InetSocketAddress("127.0.0.1", port));
                opened = connect(socket, (byte) 0);
                control = opened;
                tasks.submit(this::accept);
            } catch (IOException | RuntimeException failure) {
                SocketRelayPump.close(listener);
                if (opened != null) {
                    SocketRelayPump.close(opened);
                }
                tasks.shutdownNow();
                throw failure;
            }
        }

        void awaitHost() throws IOException {
            ByteBuffer ignored = ByteBuffer.allocate(1);
            while (control.read(ignored.clear()) >= 0) {
                // 宿主只通过关闭通知撤销，不把任何命令数据放入控制通道。
            }
        }

        private void accept() {
            while (listener.isOpen()) {
                try {
                    SocketChannel local = listener.accept();
                    if (!slots.tryAcquire()) {
                        local.close();
                        continue;
                    }
                    connections.add(local);
                    tasks.submit(() -> serve(local));
                } catch (IOException failure) {
                    SocketRelayPump.close(control);
                    return;
                }
            }
        }

        private void serve(SocketChannel local) {
            try (local;
                    SocketChannel upstream = connect(socket, (byte) 1)) {
                connections.add(upstream);
                try {
                    SocketRelayPump.connect(local, upstream, tasks);
                } finally {
                    connections.remove(upstream);
                }
            } catch (IOException ignored) {
                // Broker 拒绝或撤销连接时向 CLI 保留正常连接失败语义。
            } finally {
                connections.remove(local);
                slots.release();
            }
        }

        @Override
        public void close() {
            SocketRelayPump.close(listener);
            SocketRelayPump.close(control);
            connections.forEach(SocketRelayPump::close);
            tasks.shutdownNow();
        }

        private static SocketChannel connect(Path socket, byte type) throws IOException {
            SocketChannel connection = SocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                connection.connect(UnixDomainSocketAddress.of(socket));
                ByteBuffer header = ByteBuffer.wrap(new byte[] {type});
                while (header.hasRemaining()) {
                    connection.write(header);
                }
                return connection;
            } catch (IOException failure) {
                connection.close();
                throw failure;
            }
        }
    }
}
