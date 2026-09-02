package com.javaclaw.nativehost.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import com.javaclaw.nativehost.ffm.WindowsSandbox;
import com.javaclaw.nativehost.ffm.WindowsSandboxContext;
import com.javaclaw.nativehost.ffm.WindowsSandboxRequest;

/** 拥有 Windows ConPTY、背压输出、协作式取消与异步终态的会话。 */
final class WindowsSandboxSession implements SandboxSession {
    private static final int INITIAL_COLUMNS = 120;
    private static final int INITIAL_ROWS = 40;
    private static final int FRAME_BYTES = 8 * 1024;
    private static final long POLL_MILLIS = 20;
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(5);
    private static final int CANCELLED_EXIT = 130;
    private static final int TIMEOUT_EXIT = 124;
    private static final int OUTPUT_LIMIT_EXIT = 125;

    private final ValidatedSandboxCommand command;
    private final CancellationToken cancellation;
    private final WindowsSandbox.PseudoConsoleSession terminal;
    private final long started;
    private final PtyFramePublisher frames = new PtyFramePublisher();
    private final CompletableFuture<SandboxResult> completion = new CompletableFuture<>();
    private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService operations = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("javaclaw-conpty-operation-", 0).factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();
    private final AtomicBoolean outputLimitExceeded = new AtomicBoolean();

    private WindowsSandboxSession(
            ValidatedSandboxCommand command,
            CancellationToken cancellation,
            WindowsSandbox.PseudoConsoleSession terminal,
            long started) {
        this.command = command;
        this.cancellation = cancellation;
        this.terminal = terminal;
        this.started = started;
    }

    static WindowsSandboxSession start(ValidatedSandboxCommand command, CancellationToken cancellation)
            throws IOException {
        if (!WindowsSandbox.isSupported()) {
            throw new UnsupportedOperationException("Windows AppContainer/ConPTY APIs are unavailable");
        }
        WindowsSandbox.PseudoConsoleSession terminal =
                WindowsSandbox.openPseudoConsole(request(command), INITIAL_COLUMNS, INITIAL_ROWS);
        WindowsSandboxSession session = new WindowsSandboxSession(command, cancellation, terminal, System.nanoTime());
        try {
            session.begin();
            return session;
        } catch (IOException | RuntimeException failure) {
            try {
                terminal.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static WindowsSandboxRequest request(ValidatedSandboxCommand command) {
        return new WindowsSandboxRequest(
                command.argv(),
                new WindowsSandboxContext(command.workingDirectory(), command.environment()),
                command.readRoots(),
                command.writeRoots(),
                command.allowDelete(),
                command.timeout(),
                command.limits());
    }

    private void begin() throws IOException {
        byte[] initialInput = command.standardInput();
        if (initialInput.length > 0) {
            terminal.write(initialInput);
        }
        Future<byte[]> output = tasks.submit(this::readOutput);
        tasks.submit(() -> coordinate(output));
    }

    private void coordinate(Future<byte[]> output) {
        try {
            TargetOutcome outcome = awaitTarget();
            terminal.terminate(137);
            terminal.finishOutput();
            byte[] captured = awaitOutput(output);
            int exitCode = outcome.forced() || outputLimitExceeded.get() ? -1 : outcome.exitCode();
            SandboxResult result = new SandboxResult(
                    exitCode,
                    captured,
                    new byte[0],
                    outcome.timedOut(),
                    outcome.cancelled(),
                    Duration.ofNanos(System.nanoTime() - started));
            releaseNative();
            closed.set(true);
            frames.complete();
            completion.complete(result);
        } catch (Throwable failure) {
            closeAfterFailure(failure);
        } finally {
            operations.shutdown();
            tasks.shutdown();
        }
    }

    private TargetOutcome awaitTarget() throws IOException, InterruptedException {
        long deadline = started + command.timeout().toNanos();
        while (true) {
            if (cancellation.isCancelled()) {
                return terminate(CANCELLED_EXIT, false, true);
            }
            if (outputLimitExceeded.get()) {
                return terminate(OUTPUT_LIMIT_EXIT, false, false);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return terminate(TIMEOUT_EXIT, true, false);
            }
            long waitMillis = Math.min(POLL_MILLIS, Duration.ofNanos(remaining).toMillis() + 1);
            Integer exitCode = terminal.awaitExit(Duration.ofMillis(waitMillis));
            if (exitCode != null) {
                return new TargetOutcome(exitCode, false, false, false);
            }
        }
    }

    private TargetOutcome terminate(int exitCode, boolean timedOut, boolean cancelled) throws IOException {
        terminal.terminate(exitCode);
        terminal.awaitExit(DRAIN_TIMEOUT);
        return new TargetOutcome(-1, timedOut, cancelled, true);
    }

    private byte[] readOutput() throws InterruptedException, IOException {
        int initialCapacity = Math.toIntExact(Math.min(command.limits().outputBytes(), FRAME_BYTES));
        ByteArrayOutputStream captured = new ByteArrayOutputStream(initialCapacity);
        long remaining = command.limits().outputBytes();
        byte[] chunk;
        while ((chunk = terminal.read(FRAME_BYTES)) != null) {
            int allowed = Math.toIntExact(Math.min(remaining, chunk.length));
            if (allowed > 0) {
                byte[] frame = allowed == chunk.length ? chunk : Arrays.copyOf(chunk, allowed);
                captured.writeBytes(frame);
                remaining -= allowed;
                if (!frames.emit(new SandboxFrame("terminal", frame, Instant.now()))) {
                    terminal.terminate(CANCELLED_EXIT);
                    break;
                }
            }
            if (allowed < chunk.length) {
                outputLimitExceeded.set(true);
                terminal.terminate(OUTPUT_LIMIT_EXIT);
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
            throw new IOException("ConPTY output did not drain", timeout);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException("ConPTY output reader failed", cause);
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
        return operation(() -> {
            switch (Objects.requireNonNull(signal, "signal")) {
                case INTERRUPT -> terminal.interrupt();
                case TERMINATE -> terminal.terminate(143);
                case KILL -> terminal.terminate(137);
            }
        });
    }

    @Override
    public CompletionStage<Void> resize(int columns, int rows) {
        return operation(() -> terminal.resize(columns, rows));
    }

    @Override
    public CompletionStage<SandboxResult> completion() {
        return completion;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            releaseNative();
            frames.complete();
            completion.completeExceptionally(new CancellationException("ConPTY session closed"));
        } catch (IOException failure) {
            frames.fail(failure);
            completion.completeExceptionally(failure);
        } finally {
            tasks.shutdownNow();
            operations.shutdownNow();
        }
    }

    private CompletionStage<Void> operation(IoAction action) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("ConPTY session is closed"));
        }
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        action.run();
                    } catch (IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                },
                operations);
    }

    private void releaseNative() throws IOException {
        if (released.compareAndSet(false, true)) {
            terminal.close();
        }
    }

    private void closeAfterFailure(Throwable failure) {
        closed.set(true);
        try {
            releaseNative();
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
        frames.fail(failure);
        completion.completeExceptionally(failure);
    }

    private record TargetOutcome(int exitCode, boolean timedOut, boolean cancelled, boolean forced) {}

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
