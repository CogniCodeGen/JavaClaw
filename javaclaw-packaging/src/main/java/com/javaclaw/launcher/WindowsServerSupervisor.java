package com.javaclaw.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.javaclaw.launcher.tray.TrayServerProcess;
import com.javaclaw.nativehost.transport.WindowsNamedPipeTransport;
import com.javaclaw.nativehost.transport.WindowsPipeName;
import com.javaclaw.protocol.LocalTransport;

/** 启动或复用当前登录会话的 Windows Named Pipe App Server。 */
final class WindowsServerSupervisor implements TrayServerProcess {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PROBE_TIMEOUT = Duration.ofMillis(100);

    private final RuntimeLayout layout;
    private final WindowsPipeName pipeName;
    private final Path logFile;

    WindowsServerSupervisor(RuntimeLayout layout) {
        this.layout = layout;
        pipeName = WindowsPipeName.currentUserDefault();
        Path stateRoot = Path.of(System.getProperty("user.home"), ".javaclaw")
                .toAbsolutePath()
                .normalize();
        logFile = stateRoot.resolve("data-v5/logs/app-server.log");
    }

    WindowsPipeName ensureRunning() throws IOException, InterruptedException {
        if (!WindowsNamedPipeTransport.isSupported()) {
            throw new IOException("当前 Java Runtime 不支持 Windows Named Pipe FFM");
        }
        if (canConnect()) {
            return pipeName;
        }
        Files.createDirectories(logFile.getParent());
        Process server = startServer();
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (canConnect()) {
                return pipeName;
            }
            if (!server.isAlive()) {
                throw new IOException("App Server 启动失败，请检查日志: " + logFile);
            }
            Thread.sleep(Duration.ofMillis(50));
        }
        server.destroyForcibly();
        throw new IOException("App Server 在十秒内未创建 Named Pipe，请检查日志: " + logFile);
    }

    @Override
    public boolean running() {
        return WindowsNamedPipeTransport.isSupported() && canConnect();
    }

    @Override
    public void start() throws IOException, InterruptedException {
        ensureRunning();
    }

    @Override
    public LocalTransport transport() {
        return new WindowsNamedPipeTransport(pipeName, STARTUP_TIMEOUT);
    }

    private Process startServer() throws IOException {
        ArrayList<String> command = new ArrayList<>();
        command.add(layout.javaExecutable().toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.addAll(layout.appServerProperties());
        command.addAll(
                List.of("-cp", layout.classpath(), "com.javaclaw.server.AppServerMain", "--pipe", pipeName.value()));
        return new ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                .start();
    }

    private boolean canConnect() {
        try (var connection = new WindowsNamedPipeTransport(pipeName, PROBE_TIMEOUT).connect()) {
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }
}
