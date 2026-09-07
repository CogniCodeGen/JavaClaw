package com.javaclaw.server.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.TurnId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/** 本地 Socket 仍经过完整目标校验，只有最后的固定 IP 建连副作用被映射到本机夹具。 */
final class CommandProxyFixture implements AutoCloseable {
    final MutableClock clock = new MutableClock();
    final CancellationSource cancellation = new CancellationSource();
    final ConcurrentLinkedQueue<InetSocketAddress> targets = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<Socket> opened = new ConcurrentLinkedQueue<>();
    final ServerSocket server = new ServerSocket();
    final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
    final CommandProxyBroker broker;
    private final ConcurrentLinkedQueue<Socket> clients = new ConcurrentLinkedQueue<>();

    CommandProxyFixture() throws IOException {
        this((host, deadline, cancellation) -> List.of(publicAddress()));
    }

    CommandProxyFixture(BrokerTarget.HostResolver resolver) throws IOException {
        server.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0));
        server.setSoTimeout(3_000);
        broker = new CommandProxyBroker(clock, resolver, () -> {
            Socket socket = new RoutedSocket();
            opened.add(socket);
            return socket;
        });
    }

    CommandProxyLease lease(
            long bytes, int connections, Duration timeout, CommandProxyBroker.RealtimeAuthorization authorization)
            throws IOException {
        return broker.open(grant(bytes, connections, timeout), cancellation, authorization);
    }

    CommandNetworkGrant grant(long bytes, int connections, Duration timeout) {
        return new CommandNetworkGrant(
                "test-grant",
                TurnId.random(),
                "test-operation",
                "a".repeat(64),
                new NetworkPermission(Set.of("repo.example"), Set.of(443), true),
                new CommandNetworkGrant.Limits(bytes, connections, Duration.ofSeconds(2)),
                clock.instant().plus(timeout));
    }

    Socket connect(CommandProxyLease lease, String authority) throws IOException {
        Socket socket = new Socket();
        clients.add(socket);
        socket.connect(lease.endpoint());
        socket.setSoTimeout(3_000);
        socket.getOutputStream()
                .write(("CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
        return socket;
    }

    Future<String> echo() {
        return tasks.submit(() -> {
            try (Socket peer = server.accept()) {
                clients.add(peer);
                peer.setSoTimeout(3_000);
                byte[] data = peer.getInputStream().readAllBytes();
                peer.getOutputStream().write(data);
                peer.shutdownOutput();
                return new String(data, StandardCharsets.US_ASCII);
            }
        });
    }

    static String response(Socket socket) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int tail = 0;
        while (bytes.size() < 1024) {
            int value = socket.getInputStream().read();
            if (value < 0) {
                break;
            }
            bytes.write(value);
            tail = (tail << 8) | value;
            if (tail == 0x0d0a0d0a) {
                break;
            }
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    static void closed(Socket socket) throws IOException {
        try {
            assertEquals(-1, socket.getInputStream().read());
        } catch (SocketException reset) {
            // TCP 可按是否存在未读数据返回 EOF 或 reset，两者都证明连接终态。
        }
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                fail("条件未在有界等待内成立");
            }
            Thread.sleep(10);
        }
    }

    static InetAddress publicAddress() throws IOException {
        return InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34});
    }

    @Override
    public void close() throws IOException {
        broker.close();
        server.close();
        for (Socket socket : clients) {
            socket.close();
        }
        tasks.close();
    }

    private final class RoutedSocket extends Socket {
        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            targets.add((InetSocketAddress) endpoint);
            super.connect(server.getLocalSocketAddress(), timeout);
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        void advance(Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
