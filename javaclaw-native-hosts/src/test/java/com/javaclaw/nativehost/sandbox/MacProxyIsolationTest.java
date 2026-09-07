package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledOnOs(OS.MAC)
class MacProxyIsolationTest {
    @TempDir
    Path temporary;

    @Test
    void kernelAllowsOnlyFrozenProxyPortWithoutAnyProxyEnvironment() throws Exception {
        try (ServerSocket allowed = listener();
                ServerSocket denied = listener();
                DatagramSocket udp = new DatagramSocket(new InetSocketAddress("127.0.0.1", allowed.getLocalPort()));
                var ipv6 = ServerSocketChannel.open(StandardProtocolFamily.INET6)) {
            ipv6.bind(new InetSocketAddress("::1", allowed.getLocalPort()));
            udp.setSoTimeout(300);
            var endpoint = new InetSocketAddress("127.0.0.1", allowed.getLocalPort());
            assertEquals(
                    "allowed\ndenied\ndenied\n",
                    run(allowed, denied, udp.getLocalPort(), SandboxNetworkAccess.proxyOnly("turn-test", endpoint)));
            assertThrows(SocketTimeoutException.class, () -> udp.receive(new DatagramPacket(new byte[64], 64)));
            assertEquals(
                    "denied\ndenied\ndenied\n",
                    run(allowed, denied, udp.getLocalPort(), SandboxNetworkAccess.offline()));
            assertThrows(SocketTimeoutException.class, () -> udp.receive(new DatagramPacket(new byte[64], 64)));
        }
    }

    private String run(ServerSocket allowed, ServerSocket denied, int udpPort, SandboxNetworkAccess network)
            throws Exception {
        SandboxJavaRuntime runtime = SandboxJavaRuntime.current();
        var permission = new PermissionProfile(
                "network-kernel-test",
                1,
                new FilePermission(List.of(temporary), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(
                        Set.of(runtime.executable().getFileName().toString()), false, Duration.ofSeconds(8)),
                new ToolPermission(Set.of(), ToolRisk.EXTERNAL_EFFECT, ApprovalRequirement.EVERY_CALL),
                new ResourceLimits(512L * 1024 * 1024, 8192, 4, 128));
        var command = new SandboxCommand(
                "probe",
                runtime
                        .command(
                                Probe.class,
                                List.of(
                                        Integer.toString(allowed.getLocalPort()),
                                        Integer.toString(denied.getLocalPort()),
                                        Integer.toString(udpPort)),
                                32)
                        .stream()
                        .map(value -> value.equals("-Djava.net.preferIPv4Stack=true")
                                ? "-Djava.net.preferIPv4Stack=false"
                                : value)
                        .toList(),
                temporary,
                Map.of("NO_PROXY", "*", "no_proxy", "*"),
                new byte[0],
                SandboxMode.BATCH,
                Duration.ofSeconds(8));
        var access = new SandboxRuntimeAccess(runtime.readRoots(), List.of(), List.of(runtime.executable()));
        var result =
                new PlatformSandboxExecutor().execute(command, permission, new CancellationSource(), access, network);
        assertEquals(0, result.exitCode(), new String(result.standardError(), StandardCharsets.UTF_8));
        return new String(result.standardOutput(), StandardCharsets.UTF_8);
    }

    private static ServerSocket listener() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.bind(new InetSocketAddress("127.0.0.1", 0));
        return socket;
    }

    /** 在真实沙箱子 JVM 中直接调用 socket，验证环境变量之外的 OS 边界。 */
    public static final class Probe {
        private Probe() {}

        /** 连接已监听的 IPv4 两端口与同端口 IPv6；参数只由测试夹具提供。 */
        public static void main(String[] ports) {
            for (int index = 0; index < 2; index++) {
                connect(StandardProtocolFamily.INET, "127.0.0.1", Integer.parseInt(ports[index]));
            }
            connect(StandardProtocolFamily.INET6, "::1", Integer.parseInt(ports[0]));
            sendDatagram(Integer.parseInt(ports[2]));
        }

        private static void sendDatagram(int port) {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.send(new DatagramPacket(new byte[] {1}, 1, new InetSocketAddress("127.0.0.1", port)));
            } catch (IOException denied) {
                // UDP 可以在 send 时拒绝或被内核丢弃；宿主监听器最终确认不存在绕过传输。
            }
        }

        private static void connect(StandardProtocolFamily family, String address, int port) {
            try (SocketChannel socket = SocketChannel.open(family)) {
                // 模拟 Node/libuv 的 TCP 初始化，防止只用默认 NIO 选项掩盖代理连接无法启动。
                socket.socket().setOOBInline(true);
                socket.connect(new InetSocketAddress(address, port));
                System.out.println("allowed");
            } catch (IOException denied) {
                System.out.println("denied");
            }
        }
    }
}
