package com.javaclaw.desktop;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.javaclaw.client.sdk.JavaClawClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupWaitingConnectorTest {
    @Test
    void retriesWithinStartupWindowAndReturnsEstablishedSession() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        JavaClawClient expected = server.client(ignored -> {});
        AtomicInteger attempts = new AtomicInteger();
        AtomicLong time = new AtomicLong();
        StartupWaitingConnector connector = connector(
                notifications -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new IOException("server is starting");
                    }
                    return expected;
                },
                Duration.ofSeconds(1),
                time);

        try {
            assertSame(expected, connector.connect(ignored -> {}));
            assertEquals(3, attempts.get());
            assertEquals(Duration.ofMillis(200).toNanos(), time.get());
        } finally {
            expected.close();
        }
    }

    @Test
    void returnsLastConnectionFailureWhenStartupWindowExpires() {
        IOException expected = new IOException("offline");
        AtomicInteger attempts = new AtomicInteger();
        AtomicLong time = new AtomicLong();
        StartupWaitingConnector connector = connector(
                notifications -> {
                    attempts.incrementAndGet();
                    throw expected;
                },
                Duration.ofMillis(250),
                time);

        IOException failure = assertThrows(IOException.class, () -> connector.connect(ignored -> {}));
        assertEquals("无法连接 App Server，请确认服务端已经启动", failure.getMessage());
        assertSame(expected, failure.getCause());
        assertEquals(4, attempts.get());
        assertEquals(Duration.ofMillis(250).toNanos(), time.get());
    }

    @Test
    void interruptionCancelsStartupWaitAndPreservesInterruptFlag() {
        IOException unavailable = new IOException("offline");
        StartupWaitingConnector connector = new StartupWaitingConnector(
                notifications -> {
                    throw unavailable;
                },
                Duration.ofSeconds(1),
                Duration.ofMillis(100),
                () -> 0,
                ignored -> {
                    throw new InterruptedException("cancelled");
                });

        try {
            IOException cancelled = assertThrows(IOException.class, () -> connector.connect(ignored -> {}));
            assertEquals("等待 App Server 启动时被取消", cancelled.getMessage());
            assertSame(unavailable, cancelled.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static StartupWaitingConnector connector(
            DesktopClientConnector delegate, Duration timeout, AtomicLong time) {
        return new StartupWaitingConnector(delegate, timeout, Duration.ofMillis(100), time::get, time::addAndGet);
    }
}
