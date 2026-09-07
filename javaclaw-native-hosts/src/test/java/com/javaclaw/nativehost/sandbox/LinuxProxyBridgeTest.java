package com.javaclaw.nativehost.sandbox;

import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.javaclaw.nativehost.network.SandboxNetworkAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@DisabledOnOs(OS.WINDOWS)
class LinuxProxyBridgeTest {
    @Test
    void privateIpcRelaysBothDirectionsAndPreservesHalfClose() throws Exception {
        Path base = Files.createTempDirectory(Path.of("/tmp"), "jc-").toRealPath();
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.INET);
                var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            var endpoint = (InetSocketAddress) server.getLocalAddress();
            var response = tasks.submit(() -> {
                try (SocketChannel accepted = server.accept()) {
                    ByteBuffer bytes = ByteBuffer.allocate(16);
                    while (accepted.read(bytes) >= 0) {
                        // 收到上游半关闭后仍须能把响应送回命令。
                    }
                    accepted.write(ByteBuffer.wrap("response".getBytes(StandardCharsets.UTF_8)));
                    return new String(bytes.array(), 0, bytes.position(), StandardCharsets.UTF_8);
                }
            });
            try (LinuxProxyBridge bridge =
                            LinuxProxyBridge.open(SandboxNetworkAccess.proxyOnly("turn-1", endpoint, base));
                    SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                client.connect(UnixDomainSocketAddress.of(bridge.directory().resolve("p")));
                client.write(ByteBuffer.wrap(new byte[] {1}));
                client.write(ByteBuffer.wrap("request".getBytes(StandardCharsets.UTF_8)));
                client.shutdownOutput();
                var reply = tasks.submit(() -> {
                    ByteBuffer bytes = ByteBuffer.allocate(16);
                    while (client.read(bytes) >= 0) {
                        // 等待精确上游响应及 EOF，防止双向 pump 串行锁死。
                    }
                    return new String(bytes.array(), 0, bytes.position(), StandardCharsets.UTF_8);
                });
                assertEquals("response", reply.get(3, TimeUnit.SECONDS));
                assertEquals("request", response.get(3, TimeUnit.SECONDS));
            }
        } finally {
            try (var entries = Files.list(base)) {
                assertFalse(entries.findAny().isPresent());
            }
            Files.delete(base);
        }
    }
}
