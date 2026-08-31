package com.javaclaw.sdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** SDK-owned stdio App Server process with explicit restart supervision. */
public final class AppServerProcess implements AutoCloseable {
    private final List<String> command;
    private final Map<String, String> environmentOverrides;
    private final ReconnectingJsonRpcConnection connection;
    private final JavaClawClient client;
    private Process process;
    private boolean closed;

    /**
     * 启动并监督给定固定 argv 的 stdio App Server；不覆盖环境，close 关闭连接和子进程。
     *
     * @throws java.io.IOException 子进程或初始连接无法建立
     */
    public AppServerProcess(List<String> command) throws IOException {
        this(command, Map.of());
    }

    /** Parses a trusted infrastructure argv array without exposing the SDK JSON implementation. */
    public static List<String> parseInfrastructureCommand(String encoded) throws IOException {
        var value = new com.javaclaw.protocol.JsonRpcCodec().mapper().readTree(encoded);
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > 128) {
            throw new IllegalArgumentException("infrastructure command must be a non-empty argv array");
        }
        ArrayList<String> command = new ArrayList<>();
        value.forEach(argument -> {
            if (!argument.isTextual()
                    || argument.asText().isEmpty()
                    || argument.asText().length() > 32_768) {
                throw new IllegalArgumentException("infrastructure command contains an invalid argument");
            }
            command.add(argument.asText());
        });
        Path executable = Path.of(command.getFirst());
        if (!executable.isAbsolute() || !Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new IllegalArgumentException("infrastructure executable must be absolute and executable");
        }
        command.set(0, executable.toRealPath().toString());
        return List.copyOf(command);
    }

    /** 将受信任基础设施 argv 编码为 JSON 数组；限制参数数量和长度，不接受 Shell 命令片段。 */
    public static String encodeInfrastructureCommand(List<String> command) {
        if (command == null
                || command.isEmpty()
                || command.size() > 128
                || command.stream().anyMatch(value -> value == null || value.isEmpty() || value.length() > 32_768)) {
            throw new IllegalArgumentException("invalid infrastructure command");
        }
        try {
            return new com.javaclaw.protocol.JsonRpcCodec().mapper().writeValueAsString(List.copyOf(command));
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("cannot encode infrastructure command", impossible);
        }
    }

    /** Starts an internal App Server with explicit non-secret infrastructure environment. */
    public AppServerProcess(List<String> command, Map<String, String> environmentOverrides) throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("app server command must not be empty");
        }
        this.command = List.copyOf(command);
        java.util.LinkedHashMap<String, String> safe = new java.util.LinkedHashMap<>();
        if (environmentOverrides != null) {
            environmentOverrides.forEach((name, value) -> {
                if (name == null
                        || !name.matches("JAVACLAW_[A-Z0-9_]{1,100}")
                        || value == null
                        || value.length() > 1_000_000) {
                    throw new IllegalArgumentException("invalid App Server environment override");
                }
                safe.put(name, value);
            });
        }
        this.environmentOverrides = Map.copyOf(safe);
        this.connection = new ReconnectingJsonRpcConnection(this::openConnection);
        this.client = new JavaClawClient(connection);
    }

    /** 返回跨重连保持稳定的 SDK 聚合；监督器已关闭时抛出 IllegalStateException。 */
    public synchronized JavaClawClient client() {
        if (closed) {
            throw new IllegalStateException("app server process is closed");
        }
        return client;
    }

    /** 瞬时查询受监督的 App Server 进程是否存活；不等同于协议已经初始化。 */
    public synchronized boolean isAlive() {
        return process != null && process.isAlive();
    }

    /** 停止当前子进程并返回同一 SDK 实例；连接层观察 EOF 后负责退避重启、握手及游标恢复，不同步等待就绪。 */
    public synchronized JavaClawClient restart() {
        if (closed) {
            throw new IllegalStateException("app server process is closed");
        }
        stopProcess(Duration.ofSeconds(2));
        // The reconnecting connection observes EOF and owns backoff, initialization and replay.
        return client;
    }

    private synchronized JsonRpcConnection openConnection() throws IOException {
        if (closed) {
            throw new IOException("app server process is closed");
        }
        stopProcess(Duration.ofSeconds(1));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().putAll(environmentOverrides);
        process = builder.start();
        return new JsonRpcConnection(process.getInputStream(), process.getOutputStream());
    }

    private void stopProcess(Duration grace) {
        Process currentProcess = process;
        process = null;
        if (currentProcess == null || !currentProcess.isAlive()) {
            return;
        }
        currentProcess.destroy();
        try {
            if (!currentProcess.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                currentProcess.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            currentProcess.destroyForcibly();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        // 先有界停止所拥有的进程：第三方 Provider 线程可能在 EOF 后仍保持 stdout 打开。
        // 若先关闭 BufferedReader，会与正在等待管道的 reader 互锁，导致桌面永远无法退出。
        connection.closeAfterStoppingTransport(() -> stopProcess(Duration.ofSeconds(2)));
    }
}
