package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ResourceLimits;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsNetworkLeaseTest {
    @Test
    void concurrentCloseWaitsUntilTheFirstLeaseCleanupReallyCompletes() throws Exception {
        BlockingChannel channel = new BlockingChannel();
        SandboxNetworkAccess access =
                SandboxNetworkAccess.proxyOnly("test-grant", new InetSocketAddress("127.0.0.1", 1234));
        WindowsNetworkLease lease = new WindowsNetworkLease(channel, access);
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = tasks.submit(() -> {
                lease.close();
                return null;
            });
            assertTrue(channel.revoking.await(5, TimeUnit.SECONDS));
            CountDownLatch secondStarted = new CountDownLatch(1);
            var second = tasks.submit(() -> {
                secondStarted.countDown();
                lease.close();
                return null;
            });
            try {
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
            } finally {
                channel.release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void revokesBeforeClosingBrokerAndTerminatesJobLastOnlyOnce() throws Exception {
        List<String> operations = new ArrayList<>();
        WindowsNetworkLease lease = lease(operations, false);

        lease.close();
        lease.close();

        assertEquals(List.of("REVOKE", "BROKER", "CLOSE", "DISCONNECT"), operations);
    }

    @Test
    void revocationFailureStillClosesTunnelsAndJobAndSurfacesFailure() {
        List<String> operations = new ArrayList<>();
        WindowsNetworkLease lease = lease(operations, true);

        IOException failure = assertThrows(IOException.class, lease::close);

        assertEquals("revoke failed", failure.getMessage());
        assertEquals(List.of("REVOKE", "BROKER", "CLOSE", "DISCONNECT"), operations);
    }

    @Test
    void wireContainsOnlyBoundedIdentityAndResources() {
        String profile = "JavaClaw.Sandbox.v6." + "a".repeat(32);
        String sid = "S-1-15-2-1234-5678";
        ResourceLimits limits = new ResourceLimits(4096, 2048, 2, 8);

        assertEquals(
                "JCG1\tATTACH\t42\t1234\t1500\t4096\t3\t" + profile + "\t" + sid,
                WindowsGuardProtocol.attach(42, profile, sid, 1234, Duration.ofMillis(1500), limits));
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowsGuardProtocol.attach(42, profile + "\tCLOSE", sid, 1234, Duration.ofSeconds(1), limits));
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowsGuardProtocol.attach(42, profile, "S-1-5-18", 1234, Duration.ofSeconds(1), limits));
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowsGuardProtocol.attach(42, profile, sid, 0, Duration.ofSeconds(1), limits));
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowsGuardProtocol.attach(42, profile, sid, 1234, Duration.ofHours(25), limits));
    }

    private WindowsNetworkLease lease(List<String> operations, boolean failRevoke) {
        WindowsNetworkLease.Channel channel = new WindowsNetworkLease.Channel() {
            @Override
            public void request(String operation) throws IOException {
                operations.add(operation);
                if (failRevoke && operation.equals("REVOKE")) {
                    throw new IOException("revoke failed");
                }
            }

            @Override
            public void close() {
                operations.add("DISCONNECT");
            }
        };
        SandboxNetworkAccess access = new SandboxNetworkAccess(
                SandboxNetworkAccess.Mode.PROXY_ONLY,
                java.util.Optional.of("test-grant"),
                java.util.Optional.of(new InetSocketAddress("127.0.0.1", 1234)),
                java.util.Optional.empty(),
                () -> operations.add("BROKER"));
        return new WindowsNetworkLease(channel, access);
    }

    private static final class BlockingChannel implements WindowsNetworkLease.Channel {
        private final CountDownLatch revoking = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void request(String operation) throws IOException {
            if (operation.equals("REVOKE")) {
                revoking.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("test release timeout");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
        }

        @Override
        public void close() {}
    }
}
