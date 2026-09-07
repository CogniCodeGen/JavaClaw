package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledOnOs(OS.LINUX)
class LinuxProxyIsolationTest {
    @TempDir
    Path workspace;

    @Test
    void realNamespaceRelayAllowsProxyAndSeccompRejectsMountedHostUnixSocket() throws Exception {
        Path control = Files.createTempDirectory(Path.of("/tmp"), "jc-proxy-").toRealPath();
        Path hostSocket = workspace.resolve("host.sock");
        AtomicInteger revoked = new AtomicInteger();
        try (var proxy = ServerSocketChannel.open(StandardProtocolFamily.INET);
                var denied = ServerSocketChannel.open(StandardProtocolFamily.INET);
                DatagramSocket udp = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
                var host = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            proxy.bind(new InetSocketAddress("127.0.0.1", 0));
            denied.bind(new InetSocketAddress("127.0.0.1", 0));
            host.bind(UnixDomainSocketAddress.of(hostSocket));
            udp.setSoTimeout(300);
            var endpoint = (InetSocketAddress) proxy.getLocalAddress();
            var network = SandboxNetworkAccess.proxyOnly("linux-real", endpoint, control, revoked::incrementAndGet);
            assertEquals(
                    "allowed\ndenied\ndenied\n",
                    run(
                            endpoint.getPort(),
                            ((InetSocketAddress) denied.getLocalAddress()).getPort(),
                            hostSocket,
                            udp.getLocalPort(),
                            network));
            assertEquals(1, revoked.get());
            assertThrows(SocketTimeoutException.class, () -> udp.receive(new DatagramPacket(new byte[64], 64)));
            assertEquals(
                    "denied\ndenied\ndenied\n",
                    run(
                            endpoint.getPort(),
                            ((InetSocketAddress) denied.getLocalAddress()).getPort(),
                            hostSocket,
                            udp.getLocalPort(),
                            SandboxNetworkAccess.offline()));
            assertThrows(SocketTimeoutException.class, () -> udp.receive(new DatagramPacket(new byte[64], 64)));
        } finally {
            Files.deleteIfExists(hostSocket);
            try (var entries = Files.list(control)) {
                assertFalse(entries.findAny().isPresent());
            }
            Files.delete(control);
        }
    }

    private String run(int proxy, int denied, Path hostSocket, int udpPort, SandboxNetworkAccess network)
            throws Exception {
        var runtime = SandboxJavaRuntime.current();
        var permission = new PermissionProfile(
                "linux-real",
                1,
                new FilePermission(List.of(workspace), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(
                        Set.of(runtime.executable().getFileName().toString()), false, Duration.ofSeconds(15)),
                new ToolPermission(Set.of(), ToolRisk.EXTERNAL_EFFECT, ApprovalRequirement.EVERY_CALL),
                new ResourceLimits(1024L * 1024 * 1024, 8192, 8, 128));
        var command = new SandboxCommand(
                "linux-real",
                runtime.command(
                        Probe.class,
                        List.of(
                                Integer.toString(proxy),
                                Integer.toString(denied),
                                hostSocket.toString(),
                                Integer.toString(udpPort)),
                        32),
                workspace,
                Map.of("NO_PROXY", "*", "no_proxy", "*"),
                new byte[0],
                SandboxMode.BATCH,
                Duration.ofSeconds(15));
        var runtimeAccess = new SandboxRuntimeAccess(runtime.readRoots(), List.of(), List.of(runtime.executable()));
        var result = new PlatformSandboxExecutor()
                .execute(command, permission, new CancellationSource(), runtimeAccess, network);
        assertEquals(0, result.exitCode(), new String(result.standardError(), StandardCharsets.UTF_8));
        return new String(result.standardOutput(), StandardCharsets.UTF_8);
    }

    /** 真实 namespace 内尝试代理、其他本机端口以及已挂载宿主 pathname Unix socket。 */
    public static final class Probe {
        private Probe() {}

        /** 参数由测试夹具冻结；不依赖代理环境变量或远程网站。 */
        public static void main(String[] arguments) {
            connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(arguments[0])), StandardProtocolFamily.INET);
            connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(arguments[1])), StandardProtocolFamily.INET);
            connect(UnixDomainSocketAddress.of(arguments[2]), StandardProtocolFamily.UNIX);
            sendDatagram(Integer.parseInt(arguments[3]));
        }

        private static void sendDatagram(int port) {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.send(new DatagramPacket(new byte[] {1}, 1, new InetSocketAddress("127.0.0.1", port)));
            } catch (IOException denied) {
                // UDP 可以在 send 时拒绝或被内核丢弃；宿主监听器最终确认不存在绕过传输。
            }
        }

        private static void connect(java.net.SocketAddress address, StandardProtocolFamily family) {
            try (SocketChannel channel = SocketChannel.open(family)) {
                channel.connect(address);
                System.out.println("allowed");
            } catch (IOException rejected) {
                System.out.println("denied");
            }
        }
    }
}
