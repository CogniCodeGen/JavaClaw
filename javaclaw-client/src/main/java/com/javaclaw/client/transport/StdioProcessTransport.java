package com.javaclaw.client.transport;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.LocalTransport;
import com.javaclaw.protocol.RpcConnection;
import com.javaclaw.protocol.StreamRpcConnection;
import com.javaclaw.protocol.TransportKind;

/** 直接启动本地 App Server 进程并通过 stdio 通信，不经过 shell。 */
public final class StdioProcessTransport implements LocalTransport {
    private static final Duration GRACEFUL_CLOSE = Duration.ofSeconds(2);

    private final List<String> command;
    private final Optional<Path> workingDirectory;

    /**
     * 创建使用当前工作目录的 transport。
     *
     * @param command 可执行文件与参数；不会交给 shell 解析
     */
    public StdioProcessTransport(List<String> command) {
        this(command, Optional.empty());
    }

    /**
     * 创建 transport。
     *
     * @param command 可执行文件与参数；不会交给 shell 解析
     * @param workingDirectory 子进程工作目录；为空时继承当前目录
     */
    public StdioProcessTransport(List<String> command, Optional<Path> workingDirectory) {
        this.command = validate(command);
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .map(path -> path.toAbsolutePath().normalize());
    }

    /** @return {@link TransportKind#STDIO} */
    @Override
    public TransportKind kind() {
        return TransportKind.STDIO;
    }

    /**
     * 启动进程并接管其标准输入输出。
     *
     * @return 拥有子进程生命周期的 RPC 连接
     * @throws IOException 进程无法启动
     */
    @Override
    public RpcConnection connect() throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
        workingDirectory.ifPresent(path -> builder.directory(path.toFile()));
        Process process = builder.start();
        RpcConnection delegate =
                new StreamRpcConnection(process.getInputStream(), process.getOutputStream(), new JsonRpcCodec());
        return new ProcessConnection(delegate, process);
    }

    private static List<String> validate(List<String> value) {
        Objects.requireNonNull(value, "command");
        if (value.isEmpty() || value.stream().anyMatch(argument -> argument == null || argument.isEmpty())) {
            throw new IllegalArgumentException("command must contain non-empty arguments");
        }
        return List.copyOf(value);
    }

    /** RPC 关闭后有界回收子进程。 */
    static final class ProcessConnection implements RpcConnection {
        private final RpcConnection delegate;
        private final Process process;

        ProcessConnection(RpcConnection delegate, Process process) {
            this.delegate = delegate;
            this.process = process;
        }

        @Override
        public void send(com.javaclaw.protocol.JsonRpcMessage message) throws IOException {
            delegate.send(message);
        }

        @Override
        public com.javaclaw.protocol.JsonRpcMessage receive() throws IOException {
            return delegate.receive();
        }

        @Override
        public void close() throws IOException {
            IOException closeFailure = closeProtocol();
            awaitProcess();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        private IOException closeProtocol() {
            try {
                delegate.close();
                return null;
            } catch (IOException failure) {
                return failure;
            }
        }

        private void awaitProcess() {
            try {
                if (process.waitFor(GRACEFUL_CLOSE.toMillis(), TimeUnit.MILLISECONDS)) {
                    return;
                }
                process.destroy();
                if (!process.waitFor(GRACEFUL_CLOSE.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
