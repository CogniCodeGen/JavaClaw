package com.javaclaw.nativehost.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.SandboxFrame;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.api.SandboxSignal;
import com.javaclaw.nativehost.ffm.NativeResourceLimits;
import com.javaclaw.nativehost.ffm.PosixPty;

/** 拥有单个受控 POSIX PTY、目标进程、背压流和异步终态的会话。 */
final class PlatformSandboxSession implements SandboxSession {
    private static final int INITIAL_COLUMNS = 120;
    private static final int INITIAL_ROWS = 40;
    private static final int FRAME_BYTES = 8 * 1024;
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(5);

    private final ValidatedSandboxCommand command;
    private final SandboxLaunchPlan plan;
    private final CancellationToken cancellation;
    private final PosixPty terminal;
    private final Process process;
    private final long started;
    private final PtyFramePublisher frames = new PtyFramePublisher();
    private final CompletableFuture<SandboxResult> completion = new CompletableFuture<>();
    private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService operations = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("javaclaw-pty-operation-", 0).factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean outputLimitExceeded = new AtomicBoolean();

    private PlatformSandboxSession(
            ValidatedSandboxCommand command,
            SandboxLaunchPlan plan,
            CancellationToken cancellation,
            PosixPty terminal,
            Process process,
            long started) {
        this.command = command;
        this.plan = plan;
        this.cancellation = cancellation;
        this.terminal = terminal;
        this.process = process;
        this.started = started;
    }

    static PlatformSandboxSession start(ValidatedSandboxCommand source, CancellationToken cancellation)
            throws IOException {
        PosixPty terminal = PosixPty.open(INITIAL_COLUMNS, INITIAL_ROWS);
        Process process = null;
        try {
            ValidatedSandboxCommand command = source.withTerminal(terminal.slavePath());
            SandboxLaunchPlan plan = PlatformSandboxCommandBuilder.current().build(command);
            ProcessBuilder builder = processBuilder(command, plan, terminal.slavePath());
            long started = System.nanoTime();
            process = builder.start();
            PlatformSandboxSession session =
                    new PlatformSandboxSession(command, plan, cancellation, terminal, process, started);
            session.begin();
            return session;
        } catch (IOException | RuntimeException failure) {
            if (process != null) {
                SandboxProcessTerminator.terminate(process);
            }
            terminal.close();
            throw failure;
        }
    }

    private static ProcessBuilder processBuilder(ValidatedSandboxCommand command, SandboxLaunchPlan plan, Path slave) {
        ProcessBuilder builder = new ProcessBuilder(plan.command());
        builder.directory(command.workingDirectory().toFile());
        builder.environment().clear();
        builder.environment().putAll(plan.environment());
        builder.redirectInput(slave.toFile());
        builder.redirectOutput(slave.toFile());
        builder.redirectError(slave.toFile());
        return builder;
    }

    private void begin() {
        byte[] initialInput = command.standardInput();
        if (initialInput.length > 0) {
            terminal.write(initialInput);
        }
        Future<byte[]> output = tasks.submit(this::readOutput);
        tasks.submit(() -> coordinate(output));
    }

    private void coordinate(Future<byte[]> output) {
        try {
            SandboxProcessMonitor.Outcome outcome = SandboxProcessMonitor.await(process, command, plan, cancellation);
            byte[] captured = awaitOutput(output);
            requireWithinLimits(outcome);
            int exitCode =
                    outcome.timedOut() || outcome.cancelled() || outputLimitExceeded.get() ? -1 : process.exitValue();
            SandboxResult result = new SandboxResult(
                    exitCode,
                    captured,
                    new byte[0],
                    outcome.timedOut(),
                    outcome.cancelled(),
                    Duration.ofNanos(System.nanoTime() - started));
            frames.complete();
            completion.complete(result);
        } catch (Throwable failure) {
            frames.fail(failure);
            completion.completeExceptionally(failure);
        } finally {
            release();
        }
    }

    private byte[] readOutput() throws InterruptedException {
        int initialCapacity = (int) Math.min(command.limits().outputBytes(), FRAME_BYTES);
        ByteArrayOutputStream captured = new ByteArrayOutputStream(initialCapacity);
        long remaining = command.limits().outputBytes();
        byte[] chunk;
        while ((chunk = terminal.read(FRAME_BYTES)) != null) {
            int allowed = (int) Math.min(remaining, chunk.length);
            if (allowed > 0) {
                byte[] frame = allowed == chunk.length ? chunk : Arrays.copyOf(chunk, allowed);
                captured.writeBytes(frame);
                remaining -= allowed;
                frames.emit(new SandboxFrame("terminal", frame, Instant.now()));
            }
            if (allowed < chunk.length) {
                outputLimitExceeded.set(true);
                SandboxProcessTerminator.terminate(process);
                break;
            }
        }
        return captured.toByteArray();
    }

    private byte[] awaitOutput(Future<byte[]> output) throws Exception {
        try {
            return output.get(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            output.cancel(true);
            throw new IOException("PTY output did not drain", timeout);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException("PTY output reader failed", cause);
        }
    }

    private void requireWithinLimits(SandboxProcessMonitor.Outcome outcome) {
        if (outcome.processLimitExceeded()) {
            throw new IllegalStateException("sandbox child process limit exceeded");
        }
        if (outcome.memoryLimitExceeded()) {
            throw new IllegalStateException("sandbox process tree memory limit exceeded");
        }
    }

    @Override
    public Flow.Publisher<SandboxFrame> frames() {
        return frames;
    }

    @Override
    public CompletionStage<Void> send(byte[] bytes) {
        byte[] copy = Objects.requireNonNull(bytes, "bytes").clone();
        return operation(() -> terminal.write(copy));
    }

    @Override
    public CompletionStage<Void> signal(SandboxSignal signal) {
        int nativeSignal =
                switch (Objects.requireNonNull(signal, "signal")) {
                    case INTERRUPT -> 2;
                    case TERMINATE -> 15;
                    case KILL -> 9;
                };
        // PTY helper 先 setsid 再 exec，exec 不改变 PID；该 PID 因而也是受控前台进程组 ID。
        return operation(() -> NativeResourceLimits.signalProcessGroup(process.pid(), nativeSignal));
    }

    @Override
    public CompletionStage<Void> resize(int columns, int rows) {
        return operation(() -> terminal.resize(columns, rows));
    }

    @Override
    public CompletionStage<SandboxResult> completion() {
        return completion;
    }

    private CompletionStage<Void> operation(Runnable action) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("PTY session is closed"));
        }
        return CompletableFuture.runAsync(action, operations);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        SandboxProcessTerminator.terminate(process);
        terminal.close();
        frames.complete();
        completion.completeExceptionally(new CancellationException("PTY session closed"));
        tasks.shutdownNow();
        operations.shutdownNow();
    }

    private void release() {
        closed.set(true);
        if (process.isAlive()) {
            SandboxProcessTerminator.terminate(process);
        }
        terminal.close();
        operations.shutdown();
        tasks.shutdown();
    }
}
