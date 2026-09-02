package com.javaclaw.server.security;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.FutureTask;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketBrokerTransportTest {
    private static final InetAddress LOOPBACK = address("127.0.0.1");
    private static final InetAddress UNUSED_LOOPBACK = address("127.0.0.2");

    @Test
    void 明文连接跳过首个失败地址并交还可关闭输入输出流() throws Exception {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(LOOPBACK, 0));
            FutureTask<Integer> exchange = new FutureTask<>(() -> {
                try (var accepted = server.accept()) {
                    int request = accepted.getInputStream().read();
                    accepted.getOutputStream().write(request + 1);
                    return request;
                }
            });
            Thread.ofVirtual().name("socket-broker-test-server").start(exchange);
            BrokerTarget target = target(server.getLocalPort(), List.of(UNUSED_LOOPBACK, LOOPBACK));
            ArrayList<AutoCloseable> pending = new ArrayList<>();

            try (HttpWireExchange.Connection connection = new SocketBrokerTransport()
                    .open(
                            target,
                            BrokerDeadline.start(Duration.ofSeconds(8)),
                            new CancellationSource(),
                            pending::add)) {
                connection.output().write(41);
                connection.output().flush();
                assertEquals(42, connection.input().read());
            }

            assertEquals(41, exchange.get());
            assertEquals(2, pending.size());
        }
    }

    @Test
    void 全部固定地址失败时聚合原始连接异常() throws Exception {
        int unusedPort;
        try (ServerSocket reservation = new ServerSocket(0, 1, LOOPBACK)) {
            unusedPort = reservation.getLocalPort();
        }
        BrokerTarget target = target(unusedPort, List.of(LOOPBACK, UNUSED_LOOPBACK));

        Exception failure = assertThrows(
                Exception.class,
                () -> new SocketBrokerTransport()
                        .open(
                                target,
                                BrokerDeadline.start(Duration.ofSeconds(2)),
                                new CancellationSource(),
                                ignored -> {}));

        assertTrue(failure.getMessage().contains("could not connect"));
        assertEquals(2, failure.getSuppressed().length);
    }

    @Test
    void 已取消调用在创建Socket前失败关闭() {
        CancellationSource cancellation = new CancellationSource();
        cancellation.cancel("测试取消");
        ArrayList<AutoCloseable> pending = new ArrayList<>();

        assertThrows(
                RuntimeException.class,
                () -> new SocketBrokerTransport()
                        .open(
                                target(443, List.of(LOOPBACK)),
                                BrokerDeadline.start(Duration.ofSeconds(1)),
                                cancellation,
                                pending::add));
        assertTrue(pending.isEmpty());
        assertEquals(Optional.of("测试取消"), cancellation.reason());
    }

    private static BrokerTarget target(int port, List<InetAddress> addresses) {
        return new BrokerTarget(URI.create("http://example.test:" + port), "example.test", port, false, addresses);
    }

    private static InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
