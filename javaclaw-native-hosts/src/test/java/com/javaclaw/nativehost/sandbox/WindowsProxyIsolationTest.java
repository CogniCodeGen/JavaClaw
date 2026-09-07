package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
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

/** 必须在已安装真实签名服务的 Windows Runner 执行，缺少服务不能作为通过条件跳过。 */
@EnabledOnOs(OS.WINDOWS)
class WindowsProxyIsolationTest {
    @TempDir
    Path temporary;

    @Test
    void realAppContainerAllowsOnlyExactProxyTcpAndClosesItsLease() throws Exception {
        try (ServerSocket allowed = listener();
                ServerSocket denied = listener();
                DatagramSocket udp = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
                var ipv6 = ServerSocketChannel.open(StandardProtocolFamily.INET6)) {
            ipv6.bind(new InetSocketAddress("::1", allowed.getLocalPort()));
            udp.setSoTimeout(300);
            var endpoint = new InetSocketAddress("127.0.0.1", allowed.getLocalPort());
            assertEquals(
                    "allowed\ndenied\ndenied\n",
                    run(
                            allowed,
                            denied,
                            udp.getLocalPort(),
                            SandboxNetworkAccess.proxyOnly("windows-proxy-test", endpoint, temporary)));
            assertThrows(SocketTimeoutException.class, () -> udp.receive(new DatagramPacket(new byte[64], 64)));
            assertEquals(
                    "denied\ndenied\ndenied\n",
                    run(allowed, denied, udp.getLocalPort(), SandboxNetworkAccess.offline()));
            assertThrows(SocketTimeoutException.class, () -> udp.receive(new DatagramPacket(new byte[64], 64)));
        }
    }

    private String run(ServerSocket allowed, ServerSocket denied, int udpPort, SandboxNetworkAccess network)
            throws Exception {
        var runtime = SandboxJavaRuntime.forWorker(Probe.class);
        var permission = new PermissionProfile(
                "windows-proxy-kernel",
                1,
                new FilePermission(List.of(temporary), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(
                        Set.of(runtime.executable().getFileName().toString()), false, Duration.ofSeconds(15)),
                new ToolPermission(Set.of(), ToolRisk.PROCESS, ApprovalRequirement.NONE),
                new ResourceLimits(512L * 1024 * 1024, 8192, 4, 128));
        var arguments = runtime
                .command(
                        Probe.class,
                        List.of(
                                Integer.toString(allowed.getLocalPort()),
                                Integer.toString(denied.getLocalPort()),
                                Integer.toString(udpPort)),
                        32)
                .stream()
                .map(value ->
                        value.equals("-Djava.net.preferIPv4Stack=true") ? "-Djava.net.preferIPv4Stack=false" : value)
                .toList();
        var command = new SandboxCommand(
                "windows-proxy-probe",
                arguments,
                temporary,
                Map.of(),
                new byte[0],
                SandboxMode.BATCH,
                Duration.ofSeconds(15));
        var result = new PlatformSandboxExecutor()
                .execute(
                        command,
                        permission,
                        new CancellationSource(),
                        new SandboxRuntimeAccess(runtime.readRoots(), List.of(), List.of(runtime.executable())),
                        network);
        assertEquals(0, result.exitCode(), new String(result.standardError(), StandardCharsets.UTF_8));
        return new String(result.standardOutput(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static ServerSocket listener() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.bind(new InetSocketAddress("127.0.0.1", 0));
        return socket;
    }

    /** 在真正 AppContainer 中直接尝试 TCP/IPv6/UDP，环境代理设置不参与此断言。 */
    public static final class Probe {
        private Probe() {}

        /** 固定夹具提供端口；不执行 shell 或远程请求。 */
        public static void main(String[] arguments) {
            connect(StandardProtocolFamily.INET, Integer.parseInt(arguments[0]), "127.0.0.1");
            connect(StandardProtocolFamily.INET, Integer.parseInt(arguments[1]), "127.0.0.1");
            connect(StandardProtocolFamily.INET6, Integer.parseInt(arguments[0]), "::1");
            try (DatagramSocket udp = new DatagramSocket()) {
                udp.send(new DatagramPacket(
                        new byte[] {1}, 1, new InetSocketAddress("127.0.0.1", Integer.parseInt(arguments[2]))));
            } catch (IOException denied) {
                // WFP 可返回访问错误或丢包；最终由宿主监听器断言没有收到报文。
            }
        }

        private static void connect(StandardProtocolFamily family, int port, String address) {
            try (SocketChannel channel = SocketChannel.open(family)) {
                Socket socket = channel.socket();
                socket.connect(new InetSocketAddress(address, port), 2_000);
                System.out.println("allowed");
            } catch (IOException denied) {
                System.out.println("denied");
            }
        }
    }
}
