package com.javaclaw.nativehost.sandbox;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.javaclaw.nativehost.network.SandboxNetworkAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SandboxNetworkAccessTest {
    @Test
    void rejectsDnsIpv6OtherLoopbackAndUnspecifiedPort() {
        for (InetSocketAddress endpoint : new InetSocketAddress[] {
            InetSocketAddress.createUnresolved("localhost", 1234),
            new InetSocketAddress("127.0.0.2", 1234),
            new InetSocketAddress("::1", 1234),
            new InetSocketAddress("127.0.0.1", 0)
        }) {
            assertThrows(IllegalArgumentException.class, () -> SandboxNetworkAccess.proxyOnly("turn", endpoint));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxNetworkAccess.proxyOnly("turn\nREVOKE", new InetSocketAddress("127.0.0.1", 1234)));
    }

    @Test
    void offlineCannotCarryGrantAndProxyPreservesTrustedCallback() {
        var endpoint = new InetSocketAddress("127.0.0.1", 1234);
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxNetworkAccess(
                        SandboxNetworkAccess.Mode.OFFLINE,
                        Optional.of("turn"),
                        Optional.of(endpoint),
                        Optional.empty(),
                        () -> {}));
        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxNetworkAccess.proxyOnly("turn", endpoint, Path.of("relative")));
        AtomicInteger closures = new AtomicInteger();
        var access = SandboxNetworkAccess.proxyOnly("turn", endpoint, Path.of("/tmp"), closures::incrementAndGet);
        access.closeTunnels().run();
        assertEquals(1, closures.get());
        assertEquals(endpoint, access.proxyEndpoint().orElseThrow());
    }
}
