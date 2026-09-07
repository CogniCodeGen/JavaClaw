package com.javaclaw.server;

import java.nio.file.Path;
import java.time.Clock;

import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.StreamRpcConnection;
import com.javaclaw.server.transport.UnixDomainSocketRpcServer;
import com.javaclaw.server.transport.WindowsNamedPipeSessionServer;

/** JavaClaw 6 本地 App Server 的 stdio、Unix Domain Socket 与 Windows Named Pipe 入口。 */
public final class AppServerMain {
    private AppServerMain() {}

    /**
     * 启动长度前缀本地 RPC 服务；stdio 模式的 stdout 只承载 RPC 帧。
     *
     * @param arguments 本地 transport 的互斥启动参数
     * @throws Exception 初始化或连接失败
     */
    public static void main(String[] arguments) throws Exception {
        AppServerOptions options = AppServerOptions.parse(arguments);
        Path dataRoot = dataRoot();
        try (AppServerBootstrap.Components components = createComponents(options, dataRoot, Clock.systemUTC())) {
            switch (options.mode()) {
                case STDIO -> serveStdio(components);
                case UNIX_SOCKET -> serveUnixSocket(components, options.socketPath());
                case NAMED_PIPE -> serveNamedPipe(components, options.pipeName());
            }
        }
    }

    static AppServerBootstrap.Components createComponents(AppServerOptions options, Path dataRoot, Clock clock) {
        return switch (options.profile()) {
            case NORMAL -> AppServerBootstrap.create(dataRoot, clock);
            case HEALTH_CHECK -> AppServerBootstrap.createHealthCheck(dataRoot, clock);
        };
    }

    static void serveStdio(AppServerBootstrap.Components components) throws Exception {
        try (StreamRpcConnection connection =
                new StreamRpcConnection(System.in, System.out, new JsonRpcCodec(components.json()))) {
            components.newSession().serve(connection);
        }
    }

    private static void serveUnixSocket(AppServerBootstrap.Components components, Path socketPath) throws Exception {
        try (UnixDomainSocketRpcServer server = UnixDomainSocketRpcServer.bind(socketPath)) {
            Thread shutdown = Thread.ofVirtual().name("javaclaw-uds-shutdown").start(() -> {
                try {
                    components.awaitShutdownRequest();
                    server.close();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (Exception failure) {
                    throw new IllegalStateException("cannot close Unix Domain Socket server", failure);
                }
            });
            try {
                server.serve(
                        components.json(), connection -> components.newSession().serve(connection));
            } finally {
                shutdown.interrupt();
            }
        }
    }

    private static void serveNamedPipe(
            AppServerBootstrap.Components components, com.javaclaw.nativehost.transport.WindowsPipeName pipeName)
            throws Exception {
        try (WindowsNamedPipeSessionServer server = WindowsNamedPipeSessionServer.bind(pipeName)) {
            Thread shutdown = Thread.ofVirtual().name("javaclaw-pipe-shutdown").start(() -> {
                try {
                    components.awaitShutdownRequest();
                    server.close();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (Exception failure) {
                    throw new IllegalStateException("cannot close Windows Named Pipe server", failure);
                }
            });
            try {
                server.serve(
                        components.json(), connection -> components.newSession().serve(connection));
            } finally {
                shutdown.interrupt();
            }
        }
    }

    static Path dataRoot() {
        return com.javaclaw.nativehost.LocalRuntimeDirectories.dataDirectory();
    }
}
