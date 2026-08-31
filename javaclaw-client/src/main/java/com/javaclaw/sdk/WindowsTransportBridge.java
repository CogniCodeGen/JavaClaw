package com.javaclaw.sdk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** SDK-owned client bridge for the native Windows Named Pipe Host. Contains no FFM access. */
public final class WindowsTransportBridge {
    private WindowsTransportBridge() {}

    /**
     * 通过受信任 Native Host 的 client bridge 连接 Named Pipe；SDK 不直接调用 FFM，调用方负责关闭客户端。
     *
     * @throws java.io.IOException 辅助进程或管道连接失败
     * @throws UnsupportedOperationException 当前平台不是 Windows
     */
    public static JavaClawClient connect(List<String> hostCommand, String pipeName) throws IOException {
        requireWindows();
        List<String> prefix = List.copyOf(Objects.requireNonNull(hostCommand, "hostCommand"));
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException("hostCommand is empty");
        }
        String endpoint = Objects.requireNonNull(pipeName, "pipeName").strip();
        if (!endpoint.matches("javaclaw-v4-[A-Za-z0-9._-]{1,120}")) {
            throw new IllegalArgumentException("invalid JavaClaw Named Pipe name");
        }
        return new JavaClawClient(new ReconnectingJsonRpcConnection(() -> open(prefix, endpoint)));
    }

    /**
     * Starts and supervises the trusted server-mode Native Transport Host, then connects the SDK through separate
     * client-mode bridge processes. The App Server remains a child of the Host and never loads FFM itself.
     */
    public static ManagedTransport startLocalAppServer(
            List<String> hostCommand,
            String pipeName,
            List<String> appServerCommand,
            Map<String, String> infrastructureEnvironment)
            throws IOException {
        requireWindows();
        HostSupervisor supervisor =
                new HostSupervisor(hostCommand, pipeName, appServerCommand, infrastructureEnvironment);
        try {
            supervisor.start();
            JavaClawClient client = connect(hostCommand, pipeName);
            return new ManagedTransport(client, supervisor);
        } catch (Throwable failure) {
            supervisor.close();
            if (failure instanceof IOException io) {
                throw io;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("cannot start Windows local App Server", failure);
        }
    }

    private static JsonRpcConnection open(List<String> prefix, String pipeName) throws IOException {
        ArrayList<String> command = new ArrayList<>(prefix);
        command.addAll(List.of("client", "--pipe", pipeName));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        retainInfrastructureEnvironment(builder.environment());
        Process process = builder.start();
        JsonRpcConnection connection = new JsonRpcConnection(process.getInputStream(), process.getOutputStream());
        connection.termination().thenRun(() -> terminate(process));
        process.onExit().thenAccept(ignored -> {
            if (!connection.isClosed()) {
                connection.close();
            }
        });
        return connection;
    }

    private static void retainInfrastructureEnvironment(Map<String, String> target) {
        Map<String, String> source = Map.copyOf(target);
        target.clear();
        for (String name : List.of("SystemRoot", "WINDIR", "TEMP", "TMP")) {
            String value = source.get(name);
            if (value != null && !value.isBlank()) {
                target.put(name, value);
            }
        }
    }

    private static void terminate(Process process) {
        if (!process.isAlive()) {
            return;
        }
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException ignored) {
        }
        process.destroyForcibly();
    }

    /** SDK 客户端和 Windows Host 监督器的共同所有者；close 先断开客户端再终止基础设施进程。 */
    public static final class ManagedTransport implements AutoCloseable {
        private final JavaClawClient client;
        private final HostSupervisor supervisor;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ManagedTransport(JavaClawClient client, HostSupervisor supervisor) {
            this.client = client;
            this.supervisor = supervisor;
        }

        /** 返回由此托管句柄持有的 SDK 客户端；应通过托管句柄统一关闭。 */
        public JavaClawClient client() {
            return client;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            client.close();
            supervisor.close();
        }
    }

    private static final class HostSupervisor implements AutoCloseable {
        private static final long INITIAL_BACKOFF_MILLIS = 100;
        private static final long MAX_BACKOFF_MILLIS = 5_000;
        private final List<String> command;
        private final Map<String, String> environment;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Process process;
        private volatile Thread monitor;

        private HostSupervisor(
                List<String> hostCommand,
                String pipeName,
                List<String> appServerCommand,
                Map<String, String> infrastructureEnvironment) {
            List<String> host = List.copyOf(Objects.requireNonNull(hostCommand, "hostCommand"));
            List<String> server = List.copyOf(Objects.requireNonNull(appServerCommand, "appServerCommand"));
            if (host.isEmpty() || server.isEmpty()) {
                throw new IllegalArgumentException("host and App Server commands must not be empty");
            }
            ArrayList<String> value = new ArrayList<>(host);
            value.addAll(List.of("server", "--pipe", requiredPipeName(pipeName), "--"));
            value.addAll(server);
            command = List.copyOf(value);
            java.util.LinkedHashMap<String, String> safe = new java.util.LinkedHashMap<>();
            if (infrastructureEnvironment != null) {
                infrastructureEnvironment.forEach((name, configured) -> {
                    if (name == null
                            || !name.matches("JAVACLAW_[A-Z0-9_]{1,100}")
                            || configured == null
                            || configured.length() > 1_000_000) {
                        throw new IllegalArgumentException("invalid Windows Host infrastructure environment");
                    }
                    safe.put(name, configured);
                });
            }
            environment = Map.copyOf(safe);
        }

        private synchronized void start() throws IOException {
            if (closed.get()) {
                throw new IOException("Windows Host supervisor is closed");
            }
            startProcess();
            monitor =
                    Thread.ofVirtual().name("javaclaw-windows-host-supervisor").start(this::monitorLoop);
        }

        private void monitorLoop() {
            long backoff = INITIAL_BACKOFF_MILLIS;
            while (!closed.get()) {
                Process current = process;
                if (current == null) {
                    return;
                }
                try {
                    current.waitFor();
                    if (closed.get()) {
                        return;
                    }
                    Thread.sleep(backoff);
                    synchronized (this) {
                        if (!closed.get() && process == current) {
                            startProcess();
                        }
                    }
                    backoff = Math.min(MAX_BACKOFF_MILLIS, backoff * 2);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException restartFailure) {
                    backoff = Math.min(MAX_BACKOFF_MILLIS, backoff * 2);
                }
            }
        }

        private void startProcess() throws IOException {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            builder.environment().putAll(environment);
            process = builder.start();
        }

        @Override
        public synchronized void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Thread currentMonitor = monitor;
            if (currentMonitor != null) {
                currentMonitor.interrupt();
            }
            Process current = process;
            process = null;
            if (current != null) {
                terminate(current);
                try {
                    current.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static String requiredPipeName(String pipeName) {
        String endpoint = Objects.requireNonNull(pipeName, "pipeName").strip();
        if (!endpoint.matches("javaclaw-v4-[A-Za-z0-9._-]{1,120}")) {
            throw new IllegalArgumentException("invalid JavaClaw Named Pipe name");
        }
        return endpoint;
    }

    private static void requireWindows() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            throw new UnsupportedOperationException("Windows Transport Bridge is available only on Windows");
        }
    }
}
