package com.javaclaw.launcher;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.javaclaw.client.transport.UnixDomainSocketTransport;
import com.javaclaw.launcher.tray.TrayServerProcess;
import com.javaclaw.nativehost.ManagedRuntimeDirectory;
import com.javaclaw.protocol.LocalTransport;

/** 启动或复用当前用户的 Unix Domain Socket App Server。 */
final class UnixServerSupervisor implements TrayServerProcess {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(10);

    private final RuntimeLayout layout;
    private final Path socketPath;
    private final Path logFile;

    UnixServerSupervisor(RuntimeLayout layout) {
        this.layout = layout;
        socketPath = layout.dataDirectory().resolve("run/app-server-v6.sock");
        logFile = layout.logDirectory().resolve("app-server.log");
    }

    Path ensureRunning() throws IOException, InterruptedException {
        ManagedRuntimeDirectory.prepare(layout.dataDirectory());
        if (canConnect()) {
            return socketPath;
        }
        Files.createDirectories(socketPath.getParent());
        Files.createDirectories(logFile.getParent());
        Process server = startServer();
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (canConnect()) {
                return socketPath;
            }
            if (!server.isAlive()) {
                throw new IOException("App Server 启动失败，请检查日志: " + logFile);
            }
            Thread.sleep(Duration.ofMillis(50));
        }
        server.destroyForcibly();
        throw new IOException("App Server 在十秒内未创建本地 socket，请检查日志: " + logFile);
    }

    @Override
    public boolean running() {
        return canConnect();
    }

    @Override
    public void start() throws IOException, InterruptedException {
        ensureRunning();
    }

    @Override
    public LocalTransport transport() {
        return new UnixDomainSocketTransport(socketPath);
    }

    private Process startServer() throws IOException {
        ArrayList<String> command = new ArrayList<>();
        command.add(layout.javaExecutable().toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.addAll(layout.appServerProperties());
        command.addAll(List.of(
                "-cp", layout.classpath(), "com.javaclaw.server.AppServerMain", "--socket", socketPath.toString()));
        return new ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                .start();
    }

    private boolean canConnect() {
        if (!Files.exists(socketPath)) {
            return false;
        }
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }
}
