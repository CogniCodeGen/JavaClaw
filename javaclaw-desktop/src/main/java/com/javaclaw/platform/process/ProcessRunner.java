package com.javaclaw.platform.process;

import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskHandle;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.util.ProcessTerminator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 短生命周期外部进程的统一执行入口。
 *
 * <p>进程等待运行在受限的进程虚拟线程池，stdout/stderr 由 I/O 虚拟线程并行排空，避免管道
 * 反压死锁。超时、中断和任务取消都会强制清理整棵进程树。输出超过上限后继续排空但不再保留，
 * 因而内存占用有明确上界。</p>
 */
public final class ProcessRunner {

    private static final Duration CLEANUP_GRACE = Duration.ofSeconds(3);

    private final ManagedTaskExecutor executor;

    public ProcessRunner(ManagedTaskExecutor executor) {
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
    }

    /** 异步启动，句柄取消会中断等待线程并清理进程树。 */
    public TaskHandle<ProcessResult> start(ProcessRequest request) {
        java.util.Objects.requireNonNull(request, "request");
        TaskSpec spec = TaskSpec.process(request.name())
                .withTimeout(request.timeout().plus(CLEANUP_GRACE));
        return executor.submit(spec, context -> execute(request));
    }

    /** 同步等待结果；中断语义原样向上传播。 */
    public ProcessResult run(ProcessRequest request) throws IOException, InterruptedException {
        TaskHandle<ProcessResult> handle = start(request);
        try {
            return handle.completion().get();
        } catch (InterruptedException interrupted) {
            handle.cancel();
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (CancellationException cancelled) {
            throw new IOException("进程任务被取消: " + request.name(), cancelled);
        } catch (ExecutionException failed) {
            Throwable cause = unwrap(failed);
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("进程执行失败: " + request.name(), cause);
        }
    }

    private ProcessResult execute(ProcessRequest request) throws Exception {
        long startedAt = System.nanoTime();
        Process process = null;
        TaskHandle<CapturedOutput> stdout = null;
        TaskHandle<CapturedOutput> stderr = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(request.command());
            if (request.workingDirectory() != null) {
                builder.directory(request.workingDirectory().toFile());
            }
            builder.environment().putAll(request.environment());
            process = builder.start();
            process.getOutputStream().close();

            Process running = process;
            stdout = capture(request.name() + "-stdout", running.getInputStream(), request);
            stderr = capture(request.name() + "-stderr", running.getErrorStream(), request);

            boolean exited = ProcessTerminator.waitForOrTerminateOnInterrupt(
                    process, request.timeout().toNanos(), TimeUnit.NANOSECONDS);
            if (!exited) {
                ProcessTerminator.destroyTreeForcibly(process);
                process.waitFor(2, TimeUnit.SECONDS);
            }

            CapturedOutput out = awaitCapture(stdout);
            CapturedOutput err = awaitCapture(stderr);
            int exitCode = process.isAlive() ? -1 : process.exitValue();
            return new ProcessResult(exitCode,
                    new String(out.bytes(), request.charset()),
                    new String(err.bytes(), request.charset()),
                    !exited, out.truncated() || err.truncated(),
                    Duration.ofNanos(System.nanoTime() - startedAt));
        } finally {
            if (process != null && process.isAlive()) {
                ProcessTerminator.destroyTreeForcibly(process);
            }
            if (stdout != null && !stdout.state().isTerminal()) {
                stdout.cancel();
            }
            if (stderr != null && !stderr.state().isTerminal()) {
                stderr.cancel();
            }
        }
    }

    private TaskHandle<CapturedOutput> capture(
            String name, InputStream stream, ProcessRequest request) {
        return executor.submit(TaskSpec.io(name), context -> {
            try (stream) {
                ByteArrayOutputStream retained = new ByteArrayOutputStream(
                        Math.min(request.outputLimitBytes(), 16_384));
                byte[] buffer = new byte[8_192];
                int total = 0;
                boolean truncated = false;
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    context.cancellation().throwIfCancellationRequested();
                    int remaining = request.outputLimitBytes() - total;
                    int keep = Math.min(Math.max(remaining, 0), read);
                    if (keep > 0) {
                        retained.write(buffer, 0, keep);
                        total += keep;
                    }
                    if (keep < read) {
                        truncated = true;
                    }
                }
                return new CapturedOutput(retained.toByteArray(), truncated);
            }
        });
    }

    private static CapturedOutput awaitCapture(TaskHandle<CapturedOutput> handle)
            throws Exception {
        try {
            return handle.completion().get(2, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            handle.cancel();
            throw new IOException("等待进程输出管道关闭超时", timeout);
        } catch (ExecutionException failed) {
            Throwable cause = unwrap(failed);
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IOException("读取进程输出失败", cause);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException
                || cause instanceof java.util.concurrent.CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private record CapturedOutput(byte[] bytes, boolean truncated) {
    }
}
