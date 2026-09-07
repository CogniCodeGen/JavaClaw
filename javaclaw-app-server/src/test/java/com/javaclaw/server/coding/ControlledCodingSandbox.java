package com.javaclaw.server.coding;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxFrame;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.api.SandboxSignal;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;
import com.javaclaw.nativehost.sandbox.SandboxRuntimeAccess;

/** 测试只控制原生会话的时序，不模拟权限验证或声称 OS 隔离已通过。 */
final class ControlledCodingSandbox implements CodingProcessSandbox {
    final Session session = new Session();
    final AtomicInteger starts = new AtomicInteger();
    volatile RuntimeException openFailure;

    @Override
    public SandboxResult execute(
            SandboxCommand command,
            PermissionProfile permission,
            CancellationToken cancellation,
            SandboxRuntimeAccess access,
            SandboxNetworkAccess network) {
        starts.incrementAndGet();
        return success();
    }

    @Override
    public SandboxSession open(
            SandboxCommand command,
            PermissionProfile permission,
            CancellationToken cancellation,
            SandboxRuntimeAccess access,
            SandboxNetworkAccess network) {
        starts.incrementAndGet();
        if (openFailure != null) {
            throw openFailure;
        }
        return session;
    }

    static SandboxResult success() {
        return new SandboxResult(0, new byte[0], new byte[0], false, false, Duration.ofMillis(1));
    }

    static final class Session implements SandboxSession {
        final CompletableFuture<SandboxResult> exit = new CompletableFuture<>();
        final AtomicInteger closes = new AtomicInteger();
        final CopyOnWriteArrayList<String> inputs = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<SandboxSignal> signals = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<String> dimensions = new CopyOnWriteArrayList<>();
        volatile RuntimeException sendFailure;
        volatile Runnable beforeClose = () -> {};
        volatile boolean shortCommand;
        volatile boolean resizeFails;
        volatile boolean completeOutputOnClose = true;
        private final AtomicBoolean firstDemand = new AtomicBoolean();
        private final AtomicBoolean outputCompleted = new AtomicBoolean();
        private volatile Flow.Subscriber<? super SandboxFrame> subscriber;

        @Override
        public Flow.Publisher<SandboxFrame> frames() {
            return incoming -> {
                subscriber = incoming;
                incoming.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long count) {
                        if (shortCommand && firstDemand.compareAndSet(false, true)) {
                            emit("short command output");
                            exit.complete(success());
                            finishOutput();
                        }
                    }

                    @Override
                    public void cancel() {}
                });
            };
        }

        void emit(String text) {
            subscriber.onNext(new SandboxFrame("terminal", text.getBytes(StandardCharsets.UTF_8), Instant.now()));
        }

        void finishOutput() {
            if (outputCompleted.compareAndSet(false, true)) {
                subscriber.onComplete();
            }
        }

        void failOutput(Throwable failure) {
            subscriber.onError(failure);
        }

        @Override
        public CompletionStage<Void> send(byte[] bytes) {
            inputs.add(new String(bytes, StandardCharsets.UTF_8));
            return sendFailure == null
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(sendFailure);
        }

        @Override
        public CompletionStage<Void> signal(SandboxSignal signal) {
            signals.add(signal);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> resize(int columns, int rows) {
            dimensions.add(columns + "x" + rows);
            return resizeFails
                    ? CompletableFuture.failedFuture(new IllegalStateException("terminal already exited"))
                    : CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<SandboxResult> completion() {
            return exit;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            beforeClose.run();
            exit.complete(new SandboxResult(-1, new byte[0], new byte[0], false, true, Duration.ofMillis(1)));
            if (completeOutputOnClose) {
                finishOutput();
            }
        }
    }
}
