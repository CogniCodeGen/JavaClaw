package com.javaclaw.nativehost.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.LengthPrefixedFraming;

/**
 * 监督一个按需启动的本地 Worker；一次只允许一帧请求，取消或超时会销毁整个子进程。
 *
 * <p>成功响应后给一次性 Worker 极短退出窗口，确认退出后再清除句柄，避免下一请求写入正在退出的旧管道。持久 Worker 不受影响。
 */
public final class SupervisedWorkerProcess implements AutoCloseable {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final Duration EXIT_SETTLE_GRACE = Duration.ofMillis(25);

    private final List<String> command;
    private final Duration timeout;
    private final int maximumFrameBytes;
    private final CanonicalJson json = new CanonicalJson();
    private final ExecutorService io = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("javaclaw-worker-io", 0).factory());
    private Process process;
    private boolean closed;

    /**
     * 创建 Worker 监督器；进程直到首次交换才启动。
     *
     * @param command 不经过 shell 的完整命令
     * @param timeout 单次交换上限，范围 1–120 秒
     * @param maximumFrameBytes 单帧上限，范围 1 KiB–16 MiB
     */
    public SupervisedWorkerProcess(List<String> command, Duration timeout, int maximumFrameBytes) {
        this.command = List.copyOf(command);
        if (this.command.isEmpty() || this.command.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("command must contain non-blank arguments");
        }
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofSeconds(120)) > 0) {
            throw new IllegalArgumentException("timeout must be between 1 and 120 seconds");
        }
        if (maximumFrameBytes < 1_024 || maximumFrameBytes > LengthPrefixedFraming.DEFAULT_MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("maximumFrameBytes must be between 1 KiB and 16 MiB");
        }
        this.maximumFrameBytes = maximumFrameBytes;
    }

    /**
     * 写入一帧并等待一帧响应。
     *
     * @param request 规范化请求信封
     * @param cancellation 上层取消信号
     * @return 规范化响应信封
     */
    public synchronized CanonicalPayload exchange(CanonicalPayload request, CancellationToken cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();
        requireOpen();
        Future<CanonicalPayload> exchange = io.submit(() -> exchangeBlocking(request));
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            CanonicalPayload response = await(exchange, cancellation, deadline);
            retireExitedProcess();
            return response;
        } catch (RuntimeException failure) {
            abortProcess();
            exchange.cancel(true);
            throw failure;
        }
    }

    private CanonicalPayload await(Future<CanonicalPayload> exchange, CancellationToken cancellation, long deadline) {
        while (true) {
            cancellation.throwIfCancelled();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new WorkerProcessException("Worker call timed out");
            }
            try {
                return exchange.get(Math.min(remaining, POLL_INTERVAL.toNanos()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException ignored) {
                // 短轮询只传播协作式取消；销毁进程会解锁底层阻塞读取。
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new WorkerProcessException("Worker call was interrupted", failure);
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new WorkerProcessException("Worker exchange failed", cause);
            }
        }
    }

    private CanonicalPayload exchangeBlocking(CanonicalPayload request) {
        Process worker = requireProcess();
        try {
            LengthPrefixedFraming.write(
                    worker.getOutputStream(), request.json().getBytes(StandardCharsets.UTF_8), maximumFrameBytes);
            byte[] response = LengthPrefixedFraming.read(worker.getInputStream(), maximumFrameBytes);
            return json.parse(new String(response, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new WorkerProcessException("Worker transport failed", failure);
        }
    }

    private Process requireProcess() {
        if (process != null && process.isAlive()) {
            return process;
        }
        try {
            process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            return process;
        } catch (IOException failure) {
            throw new WorkerProcessException("Worker could not start", failure);
        }
    }

    private void abortProcess() {
        Process current = process;
        process = null;
        if (current != null) {
            current.destroyForcibly();
        }
    }

    private void retireExitedProcess() {
        Process current = process;
        if (current == null) {
            return;
        }
        try {
            if (current.waitFor(EXIT_SETTLE_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                process = null;
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Worker supervisor is closed");
        }
    }

    /** 销毁子进程并停止 I/O 虚拟线程。 */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        abortProcess();
        io.shutdownNow();
    }
}
