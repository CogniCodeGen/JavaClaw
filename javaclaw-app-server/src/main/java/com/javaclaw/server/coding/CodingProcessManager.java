package com.javaclaw.server.coding;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.ToolExecutionFact;
import com.javaclaw.api.TurnId;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;
import com.javaclaw.nativehost.sandbox.PlatformSandboxExecutor;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CodingCommandOutputRepository;
import com.javaclaw.server.persistence.CodingCommandStreamRepository;
import com.javaclaw.server.persistence.CodingOperationRepository;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.turn.CodingExecutionAuthority;

/** 托管 batch 进程所有者；与 PTY 共用执行槽、根锁、工具链租约及撤销信号。 */
final class CodingProcessManager implements AutoCloseable {
    private final ConcurrentHashMap<String, Running> active = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.Set<TurnId> finishedTurns = ConcurrentHashMap.newKeySet();
    private final ManagedCommandResolver resolver;
    private final CodingExecutionLocks locks;
    private final CodingExecutionAuthority authority;
    private final CodingProcessSandbox sandbox;
    private final CodingOperationRepository operations;
    private final AttachmentService attachments;
    private final CanonicalJson json;
    private final CodingCommandOutputRepository outputs;
    private final CodingCommandStreamRepository streams;

    CodingProcessManager(
            Services services,
            ManagedCommandResolver resolver,
            CodingExecutionLocks locks,
            CodingExecutionAuthority authority,
            PlatformSandboxExecutor sandbox) {
        this(services, resolver, locks, authority, CodingProcessSandbox.using(sandbox));
    }

    CodingProcessManager(
            Services services,
            ManagedCommandResolver resolver,
            CodingExecutionLocks locks,
            CodingExecutionAuthority authority,
            CodingProcessSandbox sandbox) {
        this.resolver = resolver;
        this.locks = locks;
        this.authority = authority;
        this.sandbox = sandbox;
        operations = services.operations();
        attachments = services.attachments();
        json = services.json();
        outputs = services.outputs();
        streams = services.streams();
    }

    CodingToolResult run(CodingInvocation invocation) throws Exception {
        var input = json.decode(invocation.request().arguments(), CodingContracts.CommandRun.class);
        return run(
                invocation,
                offline(input),
                SandboxNetworkAccess.offline(),
                Map.of("npm_config_offline", "true", "PIP_NO_INDEX", "1"));
    }

    CodingToolResult run(
            CodingInvocation invocation,
            CodingContracts.CommandRun input,
            SandboxNetworkAccess network,
            Map<String, String> additionalEnvironment)
            throws Exception {
        return run(invocation, input, network, additionalEnvironment, Optional.empty());
    }

    CodingToolResult run(
            CodingInvocation invocation,
            CodingContracts.CommandRun input,
            SandboxNetworkAccess network,
            Map<String, String> additionalEnvironment,
            Optional<java.nio.file.Path> configuration)
            throws Exception {
        CodingCancellation cancellation = new CodingCancellation(invocation, authority);
        Running running = new Running(invocation.turn().id(), cancellation, new CompletableFuture<>());
        register(invocation.id(), running);
        Exception failure = null;
        try {
            // 先注册所有者再获取资源，使并发 finish 能取消正在解析或启动的操作。
            try (var slot = locks.acquire(
                            invocation.turn().id(), invocation.turn().executionRoot());
                    var resolved = resolver.resolve(invocation, input, SandboxMode.BATCH)) {
                cancellation.throwIfCancelled();
                SandboxCommand command = command(resolved.command(), additionalEnvironment);
                CodingCommandEvidence.store(
                        operations,
                        json,
                        invocation,
                        resolved,
                        command,
                        network.mode().name());
                var observer = new CodingCommandStreamCollector(
                        streams,
                        streams.create(
                                invocation.workspaceId(),
                                invocation.turn().id(),
                                invocation.id(),
                                resolved.permission().resources().outputBytes()));
                operations.start(invocation.id());
                var access = resolved.access();
                if (configuration.isPresent()) {
                    var reads = new java.util.ArrayList<>(access.readRoots());
                    reads.add(configuration.orElseThrow());
                    access = new com.javaclaw.nativehost.sandbox.SandboxRuntimeAccess(
                            reads, access.writeRoots(), access.executableRoots());
                }
                SandboxResult result =
                        sandbox.execute(command, resolved.permission(), cancellation, access, network, observer);
                observer.complete(result);
                storeOutputs(invocation, result);
                return result(invocation, input, command, result);
            }
        } catch (Exception problem) {
            failure = problem;
            try {
                authority.reportIsolationFailure(invocation.turn().id(), problem);
            } catch (RuntimeException auditFailure) {
                problem.addSuppressed(auditFailure);
            }
            throw problem;
        } finally {
            // try-with-resources 已完成逆序释放；此后完成信号才允许 Turn 结束或复用执行根。
            if (failure == null || CodingCleanup.cleanCancellation(failure)) {
                running.finished().complete(null);
            } else {
                running.finished().completeExceptionally(failure);
            }
            active.remove(invocation.id(), running);
        }
    }

    private synchronized void register(String id, Running running) {
        if (closed.get() || finishedTurns.contains(running.turnId()) || active.putIfAbsent(id, running) != null) {
            throw new IllegalStateException("Coding 进程执行器已关闭或操作重复");
        }
    }

    static SandboxCommand command(SandboxCommand original, Map<String, String> additional) {
        LinkedHashMap<String, String> environment = new LinkedHashMap<>(original.environment());
        environment.putAll(additional);
        List<String> argv = new java.util.ArrayList<>(original.argv());
        String executable = java.nio.file.Path.of(argv.getFirst()).getFileName().toString();
        if ((executable.equals("java") || executable.equals("java.exe")) && additional.containsKey("HTTPS_PROXY")) {
            var proxy = java.net.URI.create(additional.get("HTTPS_PROXY"));
            MavenJvmArguments.requireProperties(
                    argv,
                    Map.of(
                            "http.proxyHost",
                            proxy.getHost(),
                            "http.proxyPort",
                            Integer.toString(proxy.getPort()),
                            "https.proxyHost",
                            proxy.getHost(),
                            "https.proxyPort",
                            Integer.toString(proxy.getPort()),
                            "http.nonProxyHosts",
                            "",
                            "https.nonProxyHosts",
                            "",
                            "java.net.useSystemProxies",
                            "false"));
            argv.addAll(
                    1,
                    List.of(
                            "-Dhttp.proxyHost=" + proxy.getHost(),
                            "-Dhttp.proxyPort=" + proxy.getPort(),
                            "-Dhttps.proxyHost=" + proxy.getHost(),
                            "-Dhttps.proxyPort=" + proxy.getPort(),
                            "-Dhttp.nonProxyHosts="));
        }
        return new SandboxCommand(
                original.id(),
                argv,
                original.workingDirectory(),
                environment,
                original.standardInput(),
                original.mode(),
                original.timeout());
    }

    private static CodingContracts.CommandRun offline(CodingContracts.CommandRun input) {
        List<String> argv = new java.util.ArrayList<>(input.argv());
        if (argv.getFirst().equals("mvn")) {
            argv.add(1, "--offline");
        }
        if (argv.getFirst().equals("gradle")) {
            argv.add(1, "--offline");
        }
        return new CodingContracts.CommandRun(
                argv, input.workingDirectory(), input.timeoutSeconds(), input.maxOutputBytes());
    }

    private CodingToolResult result(
            CodingInvocation invocation,
            CodingContracts.CommandRun input,
            SandboxCommand command,
            SandboxResult result) {
        CodingResults.ProcessState state = state(result);
        var summary = new CodingResults.CommandSummary(
                invocation.id(),
                input.argv(),
                input.workingDirectory(),
                Optional.of(result.exitCode()),
                state,
                result.elapsed().toMillis());
        long size = (long) result.standardOutput().length + result.standardError().length;
        var output = new CodingResults.Output(
                new String(result.standardOutput(), StandardCharsets.UTF_8),
                new String(result.standardError(), StandardCharsets.UTF_8),
                size,
                size >= Math.min(invocation.permission().resources().outputBytes(), input.maxOutputBytes()));
        var fact = new CorePayloads.Command(
                invocation.id(), command.argv(), command.workingDirectory(), Optional.of(result.exitCode()));
        return new CodingToolResult(
                new CodingResults.CommandResult(summary, output),
                List.of(new ToolExecutionFact(fact)),
                state == CodingResults.ProcessState.COMPLETED);
    }

    private void storeOutputs(CodingInvocation invocation, SandboxResult result) {
        String stdout = store(invocation, "stdout", result.standardOutput());
        String stderr = store(invocation, "stderr", result.standardError());
        outputs.record(
                invocation.workspaceId(),
                invocation.id(),
                new CodingCommandOutputRepository.Output(
                        stdout, result.standardOutput().length, stderr, result.standardError().length));
    }

    private String store(CodingInvocation invocation, String channel, byte[] bytes) {
        var identity = json.encode(new OutputIdentity(invocation.id(), channel, bytes));
        return attachments
                .store(
                        AttachmentScope.workspace(invocation.workspaceId()),
                        new CommandIdentity(
                                "coding/command-output", "command-output-" + identity.sha256(), 0, identity.sha256()),
                        "application/octet-stream",
                        bytes)
                .digest();
    }

    static CodingResults.ProcessState state(SandboxResult result) {
        if (result.cancelled()) {
            return CodingResults.ProcessState.CANCELLED;
        }
        if (result.timedOut()) {
            return CodingResults.ProcessState.TIMED_OUT;
        }
        return result.exitCode() == 0 ? CodingResults.ProcessState.COMPLETED : CodingResults.ProcessState.FAILED;
    }

    void finish(TurnId turnId) throws Exception {
        List<Running> selected;
        synchronized (this) {
            finishedTurns.add(turnId);
            selected = active.values().stream()
                    .filter(running -> running.turnId().equals(turnId))
                    .toList();
        }
        stop(selected, "Turn 资源作用域结束");
    }

    @Override
    public void close() throws Exception {
        List<Running> selected;
        synchronized (this) {
            closed.set(true);
            selected = List.copyOf(active.values());
        }
        stop(selected, "App Server 正在关闭");
    }

    private void stop(List<Running> selected, String reason) throws Exception {
        selected.forEach(running -> running.cancellation().cancel(reason));
        CodingCleanup.awaitAll(selected.stream().map(Running::finished).toList());
    }

    record Services(
            CanonicalJson json,
            CodingOperationRepository operations,
            AttachmentService attachments,
            CodingCommandOutputRepository outputs,
            CodingCommandStreamRepository streams) {
        Services(
                CanonicalJson json,
                CodingOperationRepository operations,
                AttachmentService attachments,
                CodingCommandOutputRepository outputs) {
            this(json, operations, attachments, outputs, outputs.streaming(attachments));
        }
    }

    private record Running(TurnId turnId, CodingCancellation cancellation, CompletableFuture<Void> finished) {}

    private record OutputIdentity(String operationId, String channel, byte[] bytes) {}
}
