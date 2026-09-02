package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;

/** macOS Seatbelt、Linux bubblewrap 与 Windows AppContainer 的 fail-closed SandboxExecutor。 */
public final class PlatformSandboxExecutor implements SandboxExecutor {
    private static final Duration IO_DRAIN_TIMEOUT = Duration.ofSeconds(5);

    /** 创建按当前平台选择已审计 backend 的执行器。 */
    public PlatformSandboxExecutor() {}

    /**
     * 在 OS 隔离、资源限制、输出上限和协作式取消共同约束下执行批处理命令。
     *
     * <p>实现说明：wrapper 环境被清空；命令不会经过 shell。超时或取消会先终止当前可见后代，再强制终止 wrapper。
     */
    @Override
    public SandboxResult execute(SandboxCommand command, PermissionProfile permission, CancellationToken cancellation)
            throws Exception {
        CancellationToken checkedCancellation = Objects.requireNonNull(cancellation, "cancellation");
        checkedCancellation.throwIfCancelled();
        ValidatedSandboxCommand validated = SandboxPolicyValidator.validate(command, permission, SandboxMode.BATCH);
        SandboxLaunchPlan plan = PlatformSandboxCommandBuilder.current().build(validated);
        return run(validated, plan, checkedCancellation);
    }

    /** 打开由 OS Sandbox、controlling terminal、进程树资源核算与单订阅者背压共同约束的 PTY 会话。 */
    @Override
    public SandboxSession open(SandboxCommand command, PermissionProfile permission, CancellationToken cancellation)
            throws Exception {
        CancellationToken checkedCancellation = Objects.requireNonNull(cancellation, "cancellation");
        checkedCancellation.throwIfCancelled();
        ValidatedSandboxCommand validated = SandboxPolicyValidator.validate(command, permission, SandboxMode.PTY);
        if (PlatformSandboxCommandBuilder.current() instanceof WindowsSandboxCommandBuilder) {
            return WindowsSandboxSession.start(validated, checkedCancellation);
        }
        return PlatformSandboxSession.start(validated, checkedCancellation);
    }

    private static SandboxResult run(
            ValidatedSandboxCommand command, SandboxLaunchPlan plan, CancellationToken cancellation) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(plan.command());
        builder.directory(command.workingDirectory().toFile());
        builder.environment().clear();
        builder.environment().putAll(plan.environment());
        long started = System.nanoTime();
        Process process = builder.start();
        try {
            return collect(command, plan, cancellation, process, started);
        } finally {
            if (process.isAlive()) {
                SandboxProcessTerminator.terminate(process);
            }
        }
    }

    private static SandboxResult collect(
            ValidatedSandboxCommand command,
            SandboxLaunchPlan plan,
            CancellationToken cancellation,
            Process process,
            long started)
            throws Exception {
        AtomicLong remaining = new AtomicLong(command.limits().outputBytes());
        try (ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stdout = tasks.submit(new BoundedStreamCollector(process.getInputStream(), remaining));
            Future<byte[]> stderr = tasks.submit(new BoundedStreamCollector(process.getErrorStream(), remaining));
            Future<?> input = tasks.submit(() -> writeInput(process, command.standardInput()));
            SandboxProcessMonitor.Outcome outcome = SandboxProcessMonitor.await(process, command, plan, cancellation);
            closeInput(process);
            awaitInput(input);
            byte[] standardOutput = awaitBytes(stdout);
            byte[] standardError = awaitBytes(stderr);
            if (outcome.processLimitExceeded()) {
                throw new IllegalStateException("sandbox child process limit exceeded");
            }
            if (outcome.memoryLimitExceeded()) {
                throw new IllegalStateException("sandbox process tree memory limit exceeded");
            }
            int exitCode = outcome.timedOut() || outcome.cancelled() ? -1 : process.exitValue();
            return new SandboxResult(
                    exitCode,
                    standardOutput,
                    standardError,
                    outcome.timedOut(),
                    outcome.cancelled(),
                    Duration.ofNanos(System.nanoTime() - started));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private static Void writeInput(Process process, byte[] input) throws IOException {
        try (OutputStream output = process.getOutputStream()) {
            output.write(input);
            output.flush();
        }
        return null;
    }

    private static void closeInput(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // 写线程或目标进程已经关闭同一管道时无需重复报告。
        }
    }

    private static void awaitInput(Future<?> input) throws Exception {
        try {
            input.get(IO_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            input.cancel(true);
            throw new IOException("sandbox standard input did not drain", timeout);
        } catch (ExecutionException failure) {
            throw cause(failure);
        }
    }

    private static byte[] awaitBytes(Future<byte[]> output) throws Exception {
        try {
            return output.get(IO_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            output.cancel(true);
            throw new IOException("sandbox output did not drain", timeout);
        } catch (ExecutionException failure) {
            throw cause(failure);
        }
    }

    private static Exception cause(ExecutionException failure) {
        Throwable cause = failure.getCause();
        return cause instanceof Exception exception
                ? exception
                : new IllegalStateException("sandbox task failed", cause);
    }
}
