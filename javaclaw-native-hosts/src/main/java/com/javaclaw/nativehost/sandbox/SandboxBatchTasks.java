package com.javaclaw.nativehost.sandbox;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** 拥有本次批处理的三个管道任务；同步输出观察者不得由 Future 或 executor 强制中断。 */
final class SandboxBatchTasks implements AutoCloseable {
    private static final Duration IO_DRAIN_TIMEOUT = Duration.ofSeconds(5);
    private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
    private final Process process;
    private final Future<byte[]> stdout;
    private final Future<byte[]> stderr;
    private final Future<?> input;

    SandboxBatchTasks(Process process, ValidatedSandboxCommand command, SandboxOutputObservation observation) {
        this.process = process;
        AtomicLong remaining = new AtomicLong(command.limits().outputBytes());
        stdout = tasks.submit(new BoundedStreamCollector(
                process.getInputStream(), remaining, observation.channel("stdout"), observation::isCancelled));
        stderr = tasks.submit(new BoundedStreamCollector(
                process.getErrorStream(), remaining, observation.channel("stderr"), observation::isCancelled));
        input = tasks.submit(() -> {
            try (var output = process.getOutputStream()) {
                output.write(command.standardInput());
                output.flush();
            }
            return null;
        });
    }

    void awaitInput() throws Exception {
        try {
            process.getOutputStream().close();
            input.get(IO_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            // 输入线程只拥有本进程 stdin，不运行外部观察者；可中断以解除写入阻塞。
            input.cancel(true);
            throw new IOException("sandbox standard input did not drain", timeout);
        } catch (ExecutionException failure) {
            throw cause(failure);
        }
    }

    byte[] stdout() throws Exception {
        return awaitBytes(stdout);
    }

    byte[] stderr() throws Exception {
        return awaitBytes(stderr);
    }

    private static byte[] awaitBytes(Future<byte[]> output) throws Exception {
        try {
            return output.get(IO_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            throw new IOException("sandbox output did not drain", timeout);
        } catch (ExecutionException failure) {
            throw cause(failure);
        }
    }

    @Override
    public void close() throws IOException {
        // 调用方已终止进程。先关闭自有管道解除 read/write，再只 shutdown，不把外部 interrupt 传播给观察者。
        boolean interrupted = Thread.interrupted();
        IOException failure = null;
        try {
            failure = closePipe(process.getOutputStream(), failure);
            failure = closePipe(process.getInputStream(), failure);
            failure = closePipe(process.getErrorStream(), failure);
            tasks.shutdown();
            long deadline = System.nanoTime() + IO_DRAIN_TIMEOUT.toNanos();
            while (!tasks.isTerminated()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    var incomplete = new IOException("sandbox output observer cleanup incomplete; owner still active");
                    failure = combine(failure, incomplete);
                    break;
                }
                try {
                    tasks.awaitTermination(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException external) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static IOException closePipe(Closeable pipe, IOException failure) {
        try {
            pipe.close();
            return failure;
        } catch (IOException cleanup) {
            return combine(failure, cleanup);
        }
    }

    private static IOException combine(IOException previous, IOException additional) {
        if (previous == null) {
            return additional;
        }
        previous.addSuppressed(additional);
        return previous;
    }

    private static Exception cause(ExecutionException failure) {
        Throwable cause = failure.getCause();
        return cause instanceof Exception exception
                ? exception
                : new IllegalStateException("sandbox task failed", cause);
    }
}
