package com.javaclaw.server.coding;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.SandboxFrame;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.api.ToolExecutionFact;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;
import com.javaclaw.nativehost.sandbox.PlatformSandboxExecutor;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CodingOperationRepository;
import com.javaclaw.server.persistence.CodingTerminalRepository;
import com.javaclaw.server.turn.CodingExecutionAuthority;

/** 当前 Turn 唯一 PTY 槽的原生所有者；终态、取消、撤权与关闭共用幂等资源终结流程。 */
final class CodingTerminalManager implements AutoCloseable {
    private final ConcurrentHashMap<String, LiveTerminal> active = new ConcurrentHashMap<>();
    private final java.util.Set<TurnId> finishedTurns = ConcurrentHashMap.newKeySet();
    private boolean closing;
    private final CanonicalJson json;
    private final CodingTerminalRepository terminals;
    private final CodingOperationRepository operations;
    private final ManagedCommandResolver resolver;
    private final CodingExecutionLocks locks;
    private final CodingExecutionAuthority authority;
    private final CodingProcessSandbox sandbox;

    CodingTerminalManager(
            Services services,
            ManagedCommandResolver resolver,
            CodingExecutionLocks locks,
            CodingExecutionAuthority authority,
            PlatformSandboxExecutor sandbox) {
        this(services, resolver, locks, authority, CodingProcessSandbox.using(sandbox));
    }

    CodingTerminalManager(
            Services services,
            ManagedCommandResolver resolver,
            CodingExecutionLocks locks,
            CodingExecutionAuthority authority,
            CodingProcessSandbox sandbox) {
        json = services.json();
        terminals = services.terminals();
        operations = services.operations();
        this.resolver = resolver;
        this.locks = locks;
        this.authority = authority;
        this.sandbox = sandbox;
    }

    CodingToolResult execute(String operation, CodingInvocation invocation) throws Exception {
        return switch (operation) {
            case "terminal_open" -> open(invocation);
            case "terminal_read" -> read(invocation);
            case "terminal_write" -> write(invocation);
            case "terminal_signal" -> signal(invocation);
            case "terminal_resize" -> resize(invocation);
            case "terminal_close" -> close(invocation);
            default -> throw new IllegalArgumentException("未知 PTY 操作");
        };
    }

    private CodingToolResult open(CodingInvocation invocation) throws Exception {
        var input = json.decode(invocation.request().arguments(), CodingContracts.TerminalOpen.class);
        LiveTerminal live = new LiveTerminal(invocation);
        register(live);
        try {
            live.slot = locks.acquire(invocation.turn().id(), invocation.turn().executionRoot());
            live.resolved = resolver.resolve(invocation, input.command(), SandboxMode.PTY);
            live.cancellation.throwIfCancelled();
            var command = new CorePayloads.Command(
                    invocation.id(),
                    live.resolved.command().argv(),
                    live.resolved.command().workingDirectory(),
                    Optional.empty());
            var operation =
                    operations.find(invocation.workspaceId(), invocation.id()).orElseThrow();
            terminals.create(operation.intent(), command);
            live.recordCreated = true;
            CodingCommandEvidence.store(
                    operations, json, invocation, live.resolved, live.resolved.command(), "OFFLINE");
            operations.start(invocation.id());
            live.session = sandbox.open(
                    live.resolved.command(),
                    live.resolved.permission(),
                    live.cancellation,
                    live.resolved.access(),
                    SandboxNetworkAccess.offline());
            live.cancellation.throwIfCancelled();
            terminals.state(invocation.id(), "RUNNING", Optional.empty());
            live.start();
            resizeInitially(live, input);
            return new CodingToolResult(
                    snapshot(invocation.workspaceId(), invocation.id(), 0, 64 * 1024),
                    List.of(new ToolExecutionFact(command)),
                    true);
        } catch (Exception failure) {
            if (live.started) {
                live.close();
                try {
                    live.finished.get(30, TimeUnit.SECONDS);
                } catch (Exception cleanup) {
                    CodingCleanup.append(failure, cleanup);
                }
            } else {
                live.finish(null, failure);
            }
            throw failure;
        }
    }

    private synchronized void register(LiveTerminal live) {
        if (closing
                || finishedTurns.contains(live.invocation.turn().id())
                || active.putIfAbsent(live.invocation.id(), live) != null) {
            throw new IllegalStateException("PTY 执行器已关闭、Turn 已结束或操作重复");
        }
    }

    private void resizeInitially(LiveTerminal live, CodingContracts.TerminalOpen input) throws Exception {
        try {
            live.session
                    .resize(input.columns(), input.rows())
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            var completion = live.session.completion().toCompletableFuture();
            if (!completion.isDone() || completion.isCompletedExceptionally() || completion.isCancelled()) {
                throw failure;
            }
            // 短命令可在初始 resize 前正常退出；保留真实退出和输出，不把已关闭终端误报为启动失败。
        }
    }

    private CodingToolResult read(CodingInvocation invocation) {
        var input = json.decode(invocation.request().arguments(), CodingContracts.TerminalRead.class);
        var owner = owner(invocation, input.sessionId());
        long deadline =
                System.nanoTime() + Duration.ofMillis(input.waitMillis()).toNanos();
        while (owner.state().equals("RUNNING")
                && owner.outputBytes() <= input.offsetBytes()
                && System.nanoTime() < deadline) {
            invocation.cancellation().throwIfCancelled();
            LockSupport.parkNanos(20_000_000L);
            owner = owner(invocation, input.sessionId());
        }
        return CodingToolResult.value(
                snapshot(invocation.workspaceId(), input.sessionId(), input.offsetBytes(), input.maxBytes()));
    }

    private CodingToolResult write(CodingInvocation invocation) throws Exception {
        var input = json.decode(invocation.request().arguments(), CodingContracts.TerminalWrite.class);
        var owner = owner(invocation, input.sessionId());
        LiveTerminal live = requireLive(invocation, input.sessionId());
        String digest = json.encode(input).sha256();
        operations.start(invocation.id());
        synchronized (live.inputLock) {
            if (terminals.inputIntent(owner, input.inputSequence(), digest)) {
                try {
                    live.session
                            .send(input.text().getBytes(StandardCharsets.UTF_8))
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
                    terminals.inputDelivered(input.sessionId(), input.inputSequence(), digest);
                } catch (Exception unknown) {
                    live.close();
                    throw unknown;
                }
            }
        }
        return CodingToolResult.value(snapshot(invocation.workspaceId(), input.sessionId(), 0, 1));
    }

    private CodingToolResult signal(CodingInvocation invocation) throws Exception {
        var input = json.decode(invocation.request().arguments(), CodingContracts.TerminalSignal.class);
        LiveTerminal live = requireLive(invocation, input.sessionId());
        operations.start(invocation.id());
        live.session.signal(input.signal()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        return CodingToolResult.value(snapshot(invocation.workspaceId(), input.sessionId(), 0, 1));
    }

    private CodingToolResult resize(CodingInvocation invocation) throws Exception {
        var input = json.decode(invocation.request().arguments(), CodingContracts.TerminalResize.class);
        LiveTerminal live = requireLive(invocation, input.sessionId());
        operations.start(invocation.id());
        live.session.resize(input.columns(), input.rows()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        return CodingToolResult.value(snapshot(invocation.workspaceId(), input.sessionId(), 0, 1));
    }

    private CodingToolResult close(CodingInvocation invocation) throws Exception {
        var input = json.decode(invocation.request().arguments(), CodingContracts.TerminalClose.class);
        owner(invocation, input.sessionId());
        operations.start(invocation.id());
        LiveTerminal live = active.get(input.sessionId());
        if (live != null) {
            live.close();
            live.finished.get(30, TimeUnit.SECONDS);
        }
        return CodingToolResult.value(snapshot(invocation.workspaceId(), input.sessionId(), 0, 64 * 1024));
    }

    CodingResults.TerminalResult snapshot(WorkspaceId workspaceId, String id, long offset, int maximum) {
        return snapshot(workspaceId, id, offset, maximum, true);
    }

    CodingResults.TerminalResult output(WorkspaceId workspaceId, String id, long offset, int maximum) {
        return snapshot(workspaceId, id, offset, maximum, false);
    }

    private CodingResults.TerminalResult snapshot(
            WorkspaceId workspaceId, String id, long offset, int maximum, boolean describeUnknown) {
        var owner = terminals.read(workspaceId, id);
        byte[] bytes = terminals.output(owner, offset, maximum);
        CodingResults.ProcessState state =
                switch (owner.state()) {
                    case "STARTING", "RUNNING" -> CodingResults.ProcessState.RUNNING;
                    case "UNKNOWN_OUTCOME" -> CodingResults.ProcessState.FAILED;
                    default -> CodingResults.ProcessState.valueOf(owner.state());
                };
        byte[] prefix =
                offset == 0 ? new byte[0] : terminals.output(owner, Math.max(0, offset - 3), (int) Math.min(3, offset));
        String text = CodingOutputText.decode(prefix, bytes);
        // 字节流 RPC 只返回进程原始内容；状态说明不能制造游标不前进的输出页。
        if (describeUnknown && owner.state().equals("UNKNOWN_OUTCOME") && offset == 0) {
            text = "[UNKNOWN_OUTCOME: 进程结果未知；未恢复 PID 或 stdin，未重新启动命令]\n" + text;
        }
        return new CodingResults.TerminalResult(
                id,
                state,
                new CodingResults.Output(text, "", offset + bytes.length, offset + bytes.length < owner.outputBytes()),
                owner.exitCode());
    }

    private CodingTerminalRepository.Snapshot owner(CodingInvocation invocation, String id) {
        var owner = terminals.read(invocation.workspaceId(), id);
        if (!owner.turnId().equals(invocation.turn().id())) {
            throw new SecurityException("终端工具不能访问其他 Turn 的会话");
        }
        authority.requireUnchanged(invocation.turn().id(), invocation.permission());
        return owner;
    }

    private LiveTerminal requireLive(CodingInvocation invocation, String id) {
        owner(invocation, id);
        LiveTerminal live = active.get(id);
        if (live == null || live.closed.get() || !live.started) {
            throw new IllegalStateException("PTY 已结束或结果未知");
        }
        return live;
    }

    void finish(TurnId turnId) throws Exception {
        List<LiveTerminal> selected;
        synchronized (this) {
            finishedTurns.add(turnId);
            selected = active.values().stream()
                    .filter(live -> live.invocation.turn().id().equals(turnId))
                    .toList();
        }
        stop(selected);
    }

    @Override
    public void close() throws Exception {
        List<LiveTerminal> selected;
        synchronized (this) {
            closing = true;
            selected = List.copyOf(active.values());
        }
        stop(selected);
    }

    private void stop(List<LiveTerminal> selected) throws Exception {
        selected.forEach(LiveTerminal::close);
        CodingCleanup.awaitAll(selected.stream().map(live -> live.finished).toList());
    }

    record Services(CanonicalJson json, CodingTerminalRepository terminals, CodingOperationRepository operations) {}

    private final class LiveTerminal implements AutoCloseable, Flow.Subscriber<SandboxFrame> {
        private final CodingInvocation invocation;
        private final CodingCancellation cancellation;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean stoppingSession = new AtomicBoolean();
        private final AtomicBoolean finalizing = new AtomicBoolean();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private final CompletableFuture<Void> sessionClosed = new CompletableFuture<>();
        private final CompletableFuture<Void> outputDrained = new CompletableFuture<>();
        private final CompletableFuture<Void> authorityStopped = new CompletableFuture<>();
        private final Object inputLock = new Object();
        private final Object outputLock = new Object();
        private volatile SandboxSession session;
        private volatile ManagedCommandResolver.Resolved resolved;
        private volatile CodingExecutionLocks.ProcessLease slot;
        private volatile boolean started;
        private boolean recordCreated;
        private volatile Flow.Subscription subscription;
        private CompletableFuture<SandboxResult> exit;

        private LiveTerminal(CodingInvocation invocation) {
            this.invocation = invocation;
            cancellation = new CodingCancellation(invocation, authority);
        }

        private void start() {
            exit = session.completion().toCompletableFuture().copy();
            session.frames().subscribe(this);
            started = true;
            Thread.ofVirtual().name("javaclaw-pty-authority").start(() -> {
                try {
                    while (!closed.get()) {
                        if (cancellation.isCancelled()) {
                            close();
                            return;
                        }
                        LockSupport.parkNanos(100_000_000L);
                    }
                } finally {
                    authorityStopped.complete(null);
                }
            });
            Thread.ofVirtual().name("javaclaw-pty-owner").start(this::awaitExit);
        }

        private void awaitExit() {
            SandboxResult result = null;
            Exception failure = null;
            try {
                result = exit.get();
                outputDrained.get(5, TimeUnit.SECONDS);
            } catch (Exception problem) {
                failure = problem;
            }
            finish(result, failure);
        }

        private void finish(SandboxResult result, Exception failure) {
            if (!finalizing.compareAndSet(false, true)) {
                return;
            }
            close();
            Exception problem = failure;
            try {
                // 监视器可能正在权限数据库中；共享同一清理期限，等待其离开而不以中断关闭共享文件通道。
                CompletableFuture.allOf(
                                started ? authorityStopped : CompletableFuture.completedFuture(null),
                                session != null ? sessionClosed : CompletableFuture.completedFuture(null))
                        .get(20, TimeUnit.SECONDS);
            } catch (Exception cleanup) {
                problem = CodingCleanup.append(problem, cleanup);
            }
            synchronized (outputLock) {
                if (subscription != null && !outputDrained.isDone()) {
                    subscription.cancel();
                }
            }
            problem = CodingCleanup.close(problem, resolved, slot);
            try {
                authority.reportIsolationFailure(invocation.turn().id(), problem);
            } catch (RuntimeException auditFailure) {
                problem = CodingCleanup.append(problem, auditFailure);
            }
            problem = recordFinalState(result, problem);
            // 所有资源和最终证据已处理后才允许 Turn finish 返回，移除 active 也必须在此边界之后。
            if (problem == null || CodingCleanup.cleanCancellation(problem)) {
                finished.complete(null);
            } else {
                finished.completeExceptionally(problem);
            }
            active.remove(invocation.id(), this);
        }

        private Exception recordFinalState(SandboxResult result, Exception problem) {
            if (recordCreated) {
                try {
                    String state = result == null
                            ? "UNKNOWN_OUTCOME"
                            : problem == null
                                    ? CodingProcessManager.state(result).name()
                                    : "FAILED";
                    terminals.state(
                            invocation.id(), state, result == null ? Optional.empty() : Optional.of(result.exitCode()));
                } catch (RuntimeException ledgerFailure) {
                    problem = CodingCleanup.append(problem, ledgerFailure);
                }
            }
            return problem;
        }

        @Override
        public void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null) {
                incoming.cancel();
                return;
            }
            subscription = incoming;
            incoming.request(1);
        }

        @Override
        public void onNext(SandboxFrame frame) {
            synchronized (outputLock) {
                if (finalizing.get()) {
                    return;
                }
                append(frame);
            }
        }

        private void append(SandboxFrame frame) {
            try {
                byte[] content = frame.bytes();
                for (int offset = 0; offset < content.length; offset += 64 * 1024) {
                    var owner = terminals.read(invocation.workspaceId(), invocation.id());
                    byte[] part = Arrays.copyOfRange(content, offset, Math.min(content.length, offset + 64 * 1024));
                    terminals.append(
                            owner, part, resolved.permission().resources().outputBytes());
                }
                subscription.request(1);
            } catch (RuntimeException failure) {
                outputDrained.completeExceptionally(failure);
                subscription.cancel();
                close();
            }
        }

        @Override
        public void onError(Throwable failure) {
            outputDrained.completeExceptionally(failure);
            close();
        }

        @Override
        public void onComplete() {
            outputDrained.complete(null);
        }

        @Override
        public void close() {
            closed.set(true);
            cancellation.cancel("PTY 资源作用域结束");
            SandboxSession current = session;
            if (current != null && stoppingSession.compareAndSet(false, true)) {
                // Flow 回调不能同步等待会话退出，否则会阻塞原生输出排空；关闭仅有一个异步所有者。
                Thread.ofVirtual().name("javaclaw-pty-stop").start(() -> {
                    try {
                        current.close();
                        sessionClosed.complete(null);
                    } catch (Exception failure) {
                        sessionClosed.completeExceptionally(failure);
                        if (exit != null) {
                            exit.completeExceptionally(failure);
                        }
                    }
                });
            }
        }
    }
}
