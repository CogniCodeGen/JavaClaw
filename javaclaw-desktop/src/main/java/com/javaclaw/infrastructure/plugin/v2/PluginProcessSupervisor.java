package com.javaclaw.infrastructure.plugin.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.plugin.v2.PluginManagementService.*;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.plugin.protocol.v2.*;
import com.javaclaw.util.ProcessTerminator;
import com.javaclaw.util.SensitiveDataRedactor;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** 每个实例一个独立 JVM，控制与回调使用方向不同的凭据。 */
final class PluginProcessSupervisor implements AutoCloseable {
    private static final Metadata.Key<String> AUTH = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Context.Key<Session> CALLER = Context.key("plugin-v2-caller");
    private static final int MAX_MESSAGE = 8 * 1024 * 1024;
    private final ObjectMapper json;
    private final ManagedTaskExecutor tasks;
    private final List<Path> runnerClasspath;
    private final PluginHostServiceRegistry registry;
    private final ExecutorService callbacks = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore callbackSlots = new Semaphore(64);
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Server hostServer;
    private final Consumer<Session> exited;
    private final Consumer<Session> backgroundReleased;

    PluginProcessSupervisor(ObjectMapper json, ManagedTaskExecutor tasks, List<Path> runnerClasspath,
                            PluginHostServiceRegistry registry, Consumer<Session> exited, Consumer<Session> backgroundReleased) {
        this.json = json; this.tasks = tasks; this.runnerClasspath = List.copyOf(runnerClasspath);
        this.registry = registry; this.exited = exited; this.backgroundReleased = backgroundReleased;
        try {
            hostServer = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                    .executor(callbacks).maxInboundMessageSize(MAX_MESSAGE)
                    .intercept(new CallbackAuthentication()).addService(new HostCallbacks()).build().start();
        } catch (Exception failure) { callbacks.close(); throw new PluginException("CALLBACK_SERVER_FAILED", "无法启动插件宿主回调服务", failure); }
    }

    Session start(PluginManifest manifest, String hash, Path artifactDirectory,
                  String signedArtifactFileHash, String workspace, PluginStateStore.StoredConfiguration config, String configurationJson, Path data) {
        if (runnerClasspath.isEmpty() || runnerClasspath.stream().anyMatch(path -> !Files.isRegularFile(path))) {
            throw new PluginException("RUNNER_NOT_BUILT", "独立插件 Runner 尚未构建，请先打包 plugin-runner-java");
        }
        PluginIdentity identity = PluginIdentity.newBuilder().setPluginId(manifest.id()).setWorkspaceId(workspace)
                .setArtifactHash(hash).setConfigurationRevision(config.revision()).setInstanceId(UUID.randomUUID().toString()).build();
        Session session = new Session(identity, manifest, config.permissions(), secretValues(json, manifest, configurationJson));
        sessions.put(identity.getInstanceId(), session);
        try {
            Path artifact = PluginPaths.managed(artifactDirectory, artifactDirectory.resolve(manifest.artifact()));
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString());
            command.add("-Xmx" + manifest.heapMiB() + "m"); command.add("-Dfile.encoding=UTF-8");
            if (!manifest.javaModules().isEmpty()) {
                for (String module : manifest.javaModules()) if (java.lang.module.ModuleFinder.ofSystem().find(module).isEmpty()) throw new PluginException("RUNTIME_MODULE_UNAVAILABLE", "当前 Java 运行环境缺少插件要求的模块: " + module);
                command.add("--add-modules"); command.add(String.join(",", manifest.javaModules().stream().sorted().toList()));
            }
            if (manifest.nativeAccess()) command.add("--enable-native-access=ALL-UNNAMED");
            command.add("-cp"); command.add(String.join(java.io.File.pathSeparator, runnerClasspath.stream().map(Path::toString).toList()));
            command.add("com.javaclaw.plugin.runner.PluginRunnerMain");
            ProcessBuilder builder = new ProcessBuilder(command).directory(data.toFile());
            Map<String, String> inherited = Map.copyOf(builder.environment());
            builder.environment().clear();
            // 仅保留启动 JVM 所需的系统变量，不把宿主 API 密钥传播给插件。
            for (String key : List.of("PATH", "SystemRoot", "WINDIR", "TEMP", "TMP", "LANG", "LC_ALL")) {
                if (inherited.containsKey(key)) builder.environment().put(key, inherited.get(key));
            }
            Map<String, String> env = builder.environment();
            env.put("JAVACLAW_PLUGIN_HOST_PORT", Integer.toString(hostServer.getPort()));
            env.put("JAVACLAW_PLUGIN_TOKEN", session.controlToken); env.put("JAVACLAW_HOST_TOKEN", session.hostToken);
            env.put("JAVACLAW_PLUGIN_ID", manifest.id()); env.put("JAVACLAW_PLUGIN_HASH", hash);
            env.put("JAVACLAW_PLUGIN_REVISION", Long.toString(config.revision())); env.put("JAVACLAW_WORKSPACE_ID", workspace);
            env.put("JAVACLAW_PLUGIN_INSTANCE_ID", identity.getInstanceId()); env.put("JAVACLAW_PLUGIN_ARTIFACT", artifact.toString());
            env.put("JAVACLAW_PLUGIN_ARTIFACT_FILE_HASH", signedArtifactFileHash); env.put("JAVACLAW_PLUGIN_MAIN", manifest.entrypoint());
            env.put("JAVACLAW_PLUGIN_DATA_DIRECTORY", data.toString()); env.put("JAVACLAW_PLUGIN_PARENT_PID", Long.toString(ProcessHandle.current().pid()));
            session.process = builder.start();
            CompletableFuture<Integer> bootstrap = new CompletableFuture<>();
            readOutput(session, session.process.inputReader(StandardCharsets.UTF_8), bootstrap);
            readOutput(session, session.process.errorReader(StandardCharsets.UTF_8), null);
            session.process.onExit().thenRun(() -> {
                bootstrap.completeExceptionally(new PluginException("RUNNER_EXITED", "插件进程在握手前退出"));
                if (session.live.getAndSet(false)) {
                    if (session.retiring.get()) { session.callbacksAllowed.set(false); exited.accept(session); return; }
                    session.state = State.FAILED; session.lastError = session.redact("插件进程退出，状态码 " + session.process.exitValue());
                    session.calls.values().forEach(call -> call.fail(new PluginException("RUNNER_EXITED", session.lastError)));
                    session.callbacksAllowed.set(false); exited.accept(session);
                }
            });
            int port = bootstrap.get(45, TimeUnit.SECONDS);
            session.channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext()
                    .disableRetry().maxInboundMessageSize(MAX_MESSAGE).intercept(new BearerCredentials(session.controlToken)).build();
            session.control = PluginControlGrpc.newBlockingStub(session.channel);
            PluginDescriptor descriptor = session.control.withDeadlineAfter(30, TimeUnit.SECONDS).initialize(InitializeRequest.newBuilder()
                    .setIdentity(identity).setProtocolMajor(2).setProtocolMinor(0).setManifestJson(manifest.json()).setConfigurationJson(configurationJson).build());
            if (!descriptor.getIdentity().equals(identity) || descriptor.getProtocolMajor() != 2) throw new PluginException("PROTOCOL_MISMATCH", "Runner 返回了不匹配的身份或协议");
            Set<String> actual = new java.util.HashSet<>();
            for (Contribution contribution : descriptor.getContributionsList()) {
                String key = contribution.getKind().name() + "/" + contribution.getId();
                if (!actual.add(key) || manifest.contributions().stream().noneMatch(value -> value.id().equals(contribution.getId()) && value.capability().equals(contribution.getKind().name()))) {
                    throw new PluginException("CONTRIBUTION_MISMATCH", "运行时贡献与签名清单不匹配");
                }
            }
            if (actual.size() != manifest.contributions().size()) throw new PluginException("CONTRIBUTION_MISMATCH", "Runner 缺少清单贡献");
            // 展示、路由与权限始终来自签名清单，运行时不能扩大声明。
            session.contributions = manifest.contributions();
            ValidationResult validation = session.control.withDeadlineAfter(10, TimeUnit.SECONDS).validateConfiguration(
                    ConfigurationRequest.newBuilder().setIdentity(identity).setConfigurationJson(configurationJson).build());
            if (!validation.getValid()) throw new PluginException("INVALID_CONFIGURATION", String.join("; ", validation.getErrorsList()));
            session.state = State.READY;
            return session;
        } catch (Exception failure) {
            session.lastError = session.redact("插件启动失败: " + safeMessage(failure));
            shutdown(session, true);
            if (failure instanceof PluginException problem) throw new PluginException(problem.code(), session.redact(problem.getMessage()));
            if (failure instanceof StatusRuntimeException status && status.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) throw new PluginException("INVALID_CONFIGURATION", "插件拒绝了候选配置");
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new PluginException("RUNNER_START_FAILED", session.lastError);
        }
    }

    private void readOutput(Session session, BufferedReader reader, CompletableFuture<Integer> bootstrap) {
        tasks.submit(TaskSpec.io("plugin-output-" + session.identity.getInstanceId()), context -> {
            try (reader) {
                String line;
                while ((line = boundedLine(reader)) != null) {
                    if (bootstrap != null && line.startsWith("JAVACLAW_PLUGIN_V2_READY ")) {
                        JsonNode ready = json.readTree(line.substring("JAVACLAW_PLUGIN_V2_READY ".length()));
                        int port = ready.path("port").asInt();
                        if (ready.path("protocolMajor").asInt() != 2 || port < 1 || port > 65535) bootstrap.completeExceptionally(new PluginException("PROTOCOL_MISMATCH", "Runner 启动握手无效"));
                        else bootstrap.complete(port);
                    } else session.log(line);
                }
            } catch (Exception failure) { if (bootstrap != null) bootstrap.completeExceptionally(failure); }
            return null;
        });
    }

    private static String boundedLine(BufferedReader reader) throws java.io.IOException {
        StringBuilder value = new StringBuilder(); int next;
        while ((next = reader.read()) >= 0) {
            if (next == '\n') break;
            if (value.length() < 8192 && next != '\r') value.append((char) next);
        }
        return next < 0 && value.isEmpty() ? null : value.toString();
    }

    PluginInvocation invoke(Session session, String runId, String capability, String contributionId,
                            String operation, String payload, Duration timeout, Consumer<PluginEvent> events) {
        if (!session.live.get() || !session.callbacksAllowed.get() || !session.runs.contains(runId)) throw new PluginException("LEASE_REVOKED", "插件租约已失效");
        if (session.contributions.stream().noneMatch(value -> value.id().equals(contributionId) && value.capability().equals(capability))) throw new PluginException("CONTRIBUTION_NOT_FOUND", "租约中没有该能力贡献");
        Duration deadline = timeout == null || timeout.isZero() ? Duration.ofMinutes(2) : timeout;
        if (deadline.isNegative() || deadline.compareTo(Duration.ofMinutes(30)) > 0) throw new PluginException("INVALID_DEADLINE", "插件调用超时必须在 0 到 30 分钟之间");
        CallContext context = CallContext.newBuilder().setIdentity(session.identity).setRunId(runId)
                .setRequestId(UUID.randomUUID().toString()).setDeadlineEpochMillis(Instant.now().plus(deadline).toEpochMilli()).build();
        CallRequest request = CallRequest.newBuilder().setContext(context).setContributionId(contributionId)
                .setOperation(operation == null ? "" : operation).setPayloadJson(payload == null ? "{}" : payload).build();
        Invocation call = new Invocation(session, context, events);
        session.calls.put(call.requestId(), call);
        call.completion.whenComplete((value, failure) -> session.calls.remove(call.requestId(), call));
        io.grpc.Context.CancellableContext cancellation = io.grpc.Context.current().withCancellation();
        call.cancellation = cancellation;
        cancellation.run(() -> {
            long millis = deadline.toMillis();
            switch (capability) {
                case "TOOL" -> { call.dispatched = true; ToolServiceGrpc.newStub(session.channel).withDeadlineAfter(millis, TimeUnit.MILLISECONDS).execute(request, call); }
                case "MODEL" -> { call.dispatched = true; InferenceServiceGrpc.newStub(session.channel).withDeadlineAfter(millis, TimeUnit.MILLISECONDS).invoke(request, call); }
                case "WORKFLOW" -> { call.dispatched = true; WorkflowServiceGrpc.newStub(session.channel).withDeadlineAfter(millis, TimeUnit.MILLISECONDS).execute(request, call); }
                case "CONTEXT", "SKILL" -> {
                    call.dispatched = true;
                    ContextServiceGrpc.newStub(session.channel).withDeadlineAfter(millis, TimeUnit.MILLISECONDS).retrieve(request, new StreamObserver<>() {
                        public void onNext(CallResult value) { call.result(value.getPayloadJson()); }
                        public void onError(Throwable failure) { call.onError(failure); }
                        public void onCompleted() { call.finish(); }
                    });
                }
                case "HOOK" -> {
                    try {
                        JsonNode envelope = json.readTree(payload == null ? "{}" : payload);
                        HookPoint point = switch (operation == null ? "" : operation.toLowerCase(java.util.Locale.ROOT)) {
                            case "before_turn" -> HookPoint.BEFORE_TURN;
                            case "after_model" -> HookPoint.AFTER_MODEL;
                            case "tool_result" -> HookPoint.TOOL_RESULT;
                            case "constraint", "evaluate_policy" -> HookPoint.EVALUATE_POLICY;
                            default -> throw new PluginException("INVALID_HOOK_POINT", "未知的数据钩子阶段");
                        };
                        List<String> allowed = new ArrayList<>();
                        envelope.path("allowedPermissions").forEach(item -> { if (item.isTextual()) allowed.add(item.asText()); });
                        long budget = Math.max(0, envelope.path("remainderBudget").asLong(0));
                        HookRequest hook = HookRequest.newBuilder().setContext(context).setContributionId(contributionId).setPoint(point)
                                .setInputJson(envelope.has("input") ? envelope.get("input").toString() : payload).addAllAllowedPermissions(allowed).setRemainderBudget(budget).build();
                        call.dispatched = true;
                        HookServiceGrpc.newStub(session.channel).withDeadlineAfter(millis, TimeUnit.MILLISECONDS).invoke(hook, new StreamObserver<>() {
                            public void onNext(HookResponse value) {
                                if ((!allowed.contains("*") && !allowed.containsAll(value.getAllowedPermissionsList())) || value.getRemainderBudget() < 0 || value.getRemainderBudget() > budget) { call.fail(new PluginException("HOOK_ESCALATION", "钩子不能扩大权限或预算")); return; }
                                try { call.result(json.writeValueAsString(Map.of("output", json.readTree(value.getOutputJson()), "allowedPermissions", value.getAllowedPermissionsList(), "remainderBudget", value.getRemainderBudget()))); }
                                catch (Exception failure) { call.fail(failure); }
                            }
                            public void onError(Throwable failure) { call.onError(failure); }
                            public void onCompleted() { call.finish(); }
                        });
                    } catch (Exception failure) { call.fail(failure); }
                }
                default -> call.fail(new PluginException("UNSUPPORTED_CAPABILITY", "不支持的插件能力"));
            }
        });
        return call;
    }

    void invalidate(Session session) {
        session.accepting.set(false); session.callbacksAllowed.set(false);
        session.calls.values().forEach(Invocation::cancel);
        session.hostCalls.forEach(call -> call.cancel(new CancellationException("插件身份已撤销")));
        session.backgrounds.clear();
    }

    void releaseRun(Session session, String runId) {
        session.calls.values().stream().filter(call -> call.context.getRunId().equals(runId)).forEach(Invocation::cancel);
        session.runs.remove(runId);
    }

    void expireBackgroundLeases(Session session) {
        long now = System.currentTimeMillis();
        session.backgrounds.forEach((runId, receipt) -> { if (receipt.expiresAt <= now) closeReceipt(session, runId, receipt); });
    }

    private boolean activeReceipt(Session session, String runId) {
        BackgroundReceipt receipt = session.backgrounds.get(runId);
        if (receipt == null) return false;
        if (receipt.expiresAt <= System.currentTimeMillis()) { closeReceipt(session, runId, receipt); return false; }
        return true;
    }

    private void closeReceipt(Session session, String runId, BackgroundReceipt receipt) {
        if (!session.backgrounds.remove(runId, receipt)) return;
        receipt.calls.forEach(call -> call.cancel(new CancellationException("插件后台租约已关闭或到期")));
        try { tasks.submit(TaskSpec.io("plugin-background-released-" + session.identity.getInstanceId()), context -> {
            backgroundReleased.accept(session); return null;
        }); } catch (RuntimeException ignored) { /* 执行器关闭时由统一回收兜底；正常运行的监督周期也会再次检查排空。 */ }
    }

    void terminateUnhealthy(Session session) {
        invalidate(session);
        session.lastError = session.redact("插件连续三次健康检查失败");
        if (session.process != null && session.process.isAlive()) ProcessTerminator.destroyTreeForcibly(session.process);
    }

    boolean drain(Session session) {
        if (!session.live.get() || session.process == null || !session.process.isAlive()) return true;
        try {
            return session.control.withDeadlineAfter(1, TimeUnit.SECONDS).drain(DrainRequest.newBuilder()
                    .setIdentity(session.identity).setTimeoutMillis(0).build()).getDrained();
        } catch (RuntimeException failure) {
            session.lastError = session.redact("插件排空检查暂不可达，将继续等待或由用户强制结束");
            return !session.live.get();
        }
    }

    boolean shutdown(Session session, boolean force) {
        synchronized (session) {
            session.accepting.set(false); session.callbacksAllowed.set(false);
            if (force) { session.live.set(false); session.calls.values().forEach(Invocation::cancel); }
            if (session.control != null && session.process != null && session.process.isAlive()) {
                try { session.control.withDeadlineAfter(force ? 3000 : 500, TimeUnit.MILLISECONDS).shutdown(session.identity); }
                catch (RuntimeException ignored) { /* 进程可能已退出；下方检查真实进程状态决定是否继续等待。 */ }
            }
            if (session.process != null) {
                try {
                    if (!session.process.waitFor(force ? 3000 : 100, TimeUnit.MILLISECONDS)) {
                        if (!force) return false;
                        ProcessTerminator.destroyTreeForcibly(session.process);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    if (!force) return false;
                    ProcessTerminator.destroyTreeForcibly(session.process);
                }
            }
            session.live.set(false);
            if (session.channel != null) session.channel.shutdownNow();
            session.state = State.STOPPED; sessions.remove(session.identity.getInstanceId(), session);
            return true;
        }
    }

    Diagnostics diagnostics(Session session) {
        String health = "";
        if (session.live.get() && session.control != null) {
            try {
                health = session.control.withDeadlineAfter(2, TimeUnit.SECONDS).health(session.identity).getState();
                session.healthFailures.set(0);
                if (session.state == State.DEGRADED) session.state = State.READY;
            } catch (RuntimeException failure) { if (session.state != State.DRAINING) session.state = State.DEGRADED; session.healthFailures.incrementAndGet(); health = "不可达"; }
        }
        return new Diagnostics(session.state, session.identity.getInstanceId(), session.process == null ? 0 : session.process.pid(), session.lastError,
                session.logs(), Map.of("runner", health, "activeRuns", Integer.toString(session.runs.size()), "activeRequests", Integer.toString(session.calls.size())));
    }

    @Override public void close() {
        List.copyOf(sessions.values()).forEach(session -> shutdown(session, true)); hostServer.shutdownNow(); callbacks.shutdownNow();
    }

    static final class Session {
        final PluginIdentity identity;
        final Instant startedAt = Instant.now();
        final PluginManifest manifest;
        final Set<String> permissions;
        final String controlToken = token();
        final String hostToken = token();
        final AtomicBoolean live = new AtomicBoolean(true);
        final AtomicBoolean accepting = new AtomicBoolean(true);
        final AtomicBoolean callbacksAllowed = new AtomicBoolean(true);
        final AtomicBoolean drainChecking = new AtomicBoolean();
        final AtomicBoolean retiring = new AtomicBoolean();
        final Map<String, BackgroundReceipt> backgrounds = new ConcurrentHashMap<>();
        final Set<String> backgroundRequests = ConcurrentHashMap.newKeySet();
        final Set<Context.CancellableContext> hostCalls = ConcurrentHashMap.newKeySet();
        final List<String> secretValues;
        final AtomicInteger healthFailures = new AtomicInteger();
        final Set<String> runs = ConcurrentHashMap.newKeySet();
        final Map<String, Invocation> calls = new ConcurrentHashMap<>();
        final ArrayDeque<String> log = new ArrayDeque<>();
        volatile State state = State.STARTING;
        volatile String lastError = "";
        volatile Process process;
        volatile ManagedChannel channel;
        volatile PluginControlGrpc.PluginControlBlockingStub control;
        volatile List<ContributionInfo> contributions;
        Session(PluginIdentity identity, PluginManifest manifest, Set<String> permissions, List<String> secretValues) {
            this.identity = identity; this.manifest = manifest; this.permissions = permissions; this.contributions = manifest.contributions(); this.secretValues = secretValues;
        }
        String redact(String value) {
            String safe = value == null ? "" : value.replace(controlToken, "[密钥]").replace(hostToken, "[密钥]");
            for (String secret : secretValues) safe = safe.replace(secret, "[密钥]");
            return SensitiveDataRedactor.redactText(safe);
        }
        synchronized void log(String line) { log.addLast(redact(line)); while (log.size() > 200) log.removeFirst(); }
        synchronized List<String> logs() { return List.copyOf(log); }
    }

    private static final class BackgroundReceipt {
        final String leaseId;
        final long expiresAt;
        final Set<Context.CancellableContext> calls = ConcurrentHashMap.newKeySet();
        BackgroundReceipt(String leaseId, long expiresAt) { this.leaseId = leaseId; this.expiresAt = expiresAt; }
    }

    private static final class Invocation implements PluginInvocation, StreamObserver<RpcEvent> {
        final Session session;
        final CallContext context;
        final Consumer<PluginEvent> consumer;
        final CompletableFuture<InvocationResult> completion = new CompletableFuture<>();
        volatile io.grpc.Context.CancellableContext cancellation;
        long sequence;
        String result = "{}";
        boolean terminal;
        volatile boolean dispatched;
        Invocation(Session session, CallContext context, Consumer<PluginEvent> consumer) { this.session = session; this.context = context; this.consumer = consumer == null ? event -> { } : consumer; }
        public String requestId() { return context.getRequestId(); }
        public CompletableFuture<InvocationResult> completion() { return completion; }
        public boolean cancel() {
            if (!completion.cancel(true)) return false;
            if (cancellation != null) cancellation.cancel(new CancellationException("插件调用已取消"));
            if (session.control != null && session.live.get()) {
                try { session.control.withDeadlineAfter(1, TimeUnit.SECONDS).cancel(CancelRequest.newBuilder().setIdentity(session.identity).setRequestId(requestId()).build()); }
                catch (RuntimeException ignored) { /* 本地调用已取消，远端失联时由身份撤销或进程退出兜底。 */ }
            }
            return true;
        }
        public synchronized void onNext(RpcEvent event) {
            if (completion.isDone()) return;
            if (terminal || !event.getRequestId().equals(requestId()) || event.getSequence() <= sequence) { fail(new PluginException("INVALID_PLUGIN_EVENT", "插件流事件顺序或身份无效")); return; }
            sequence = event.getSequence();
            switch (event.getEventType()) {
                case ERROR -> { terminal = true; declaredFailure(new PluginException(event.getErrorCode().matches("[A-Z][A-Z0-9_]{0,63}") ? event.getErrorCode() : "PLUGIN_ERROR", session.redact(event.getMessage()))); }
                case COMPLETE -> {
                    terminal = true;
                    if (!event.getPayloadJson().isBlank() && !event.getPayloadJson().equals("{}")) result = event.getPayloadJson();
                    emit("COMPLETE", event.getPayloadJson());
                }
                case DATA -> { result = event.getPayloadJson(); emit("DATA", result); }
                default -> fail(new PluginException("INVALID_PLUGIN_EVENT", "插件事件类型无效"));
            }
        }
        public synchronized void onError(Throwable failure) { if (terminal) finish(); else fail(failure); }
        public synchronized void onCompleted() {
            if (!terminal) { fail(new PluginException("INCOMPLETE_PLUGIN_STREAM", "插件流缺少终态事件")); return; }
            finish();
        }
        synchronized void result(String value) { if (!completion.isDone()) { result = value; terminal = true; emit("COMPLETE", value); } }
        void emit(String type, String value) { try { consumer.accept(new PluginEvent(requestId(), sequence, type, value)); } catch (RuntimeException failure) { fail(failure); } }
        synchronized void finish() {
            if (!terminal) { fail(new PluginException("INCOMPLETE_PLUGIN_STREAM", "插件响应缺少明确终态")); return; }
            completion.complete(new InvocationResult(requestId(), result));
        }
        void fail(Throwable failure) {
            if (terminal && !completion.isDone()) { finish(); return; }
            String code;
            if (failure instanceof PluginException problem) {
                code = Set.of("RUNNER_EXITED", "INCOMPLETE_PLUGIN_STREAM", "INVALID_PLUGIN_EVENT").contains(problem.code()) ? "UNKNOWN_OUTCOME" : problem.code();
            } else {
                Status.Code status = Status.fromThrowable(failure).getCode();
                code = switch (status) {
                    case PERMISSION_DENIED, UNAUTHENTICATED -> "PERMISSION_DENIED";
                    case INVALID_ARGUMENT -> "INVALID_PLUGIN_REQUEST";
                    case NOT_FOUND -> "CONTRIBUTION_NOT_FOUND";
                    case FAILED_PRECONDITION -> "PLUGIN_NOT_READY";
                    case RESOURCE_EXHAUSTED -> "PLUGIN_RESOURCE_EXHAUSTED";
                    default -> dispatched ? "UNKNOWN_OUTCOME" : "PLUGIN_RPC_FAILED";
                };
            }
            completion.completeExceptionally(new PluginException(code, session.redact(safeMessage(failure))));
            if (cancellation != null) cancellation.cancel(failure);
        }
        private void declaredFailure(PluginException failure) {
            completion.completeExceptionally(failure);
            if (cancellation != null) cancellation.cancel(failure);
        }
    }

    private final class CallbackAuthentication implements ServerInterceptor {
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            String authorization = headers.get(AUTH);
            Session session = authorization == null ? null : sessions.values().stream().filter(value -> constantEquals("Bearer " + value.hostToken, authorization)).findFirst().orElse(null);
            if (session == null || !session.live.get() || !session.callbacksAllowed.get()) {
                call.close(Status.UNAUTHENTICATED.withDescription("插件回调凭据已失效"), new Metadata()); return new ServerCall.Listener<>() { };
            }
            return Contexts.interceptCall(Context.current().withValue(CALLER, session), call, headers, next);
        }
    }

    private final class HostCallbacks extends HostServicesGrpc.HostServicesImplBase {
        @Override public void openBackground(BackgroundLeaseRequest request, StreamObserver<BackgroundLease> observer) {
            Session session = CALLER.get(); CallContext context = request.getContext(); long now = System.currentTimeMillis();
            if (session == null || !context.getIdentity().equals(session.identity) || !validRequestId(context.getRequestId()) || context.getRunId().length() > 128
                    || context.getDeadlineEpochMillis() <= now || context.getDeadlineEpochMillis() > now + Duration.ofMinutes(30).toMillis() || request.getTtlMillis() <= 0) {
                observer.onError(Status.PERMISSION_DENIED.withDescription("后台租约身份或截止时间无效").asRuntimeException()); return;
            }
            expireBackgroundLeases(session);
            BackgroundLease lease;
            synchronized (session) {
                if (!session.live.get() || !session.callbacksAllowed.get() || !session.accepting.get()) {
                    observer.onError(Status.FAILED_PRECONDITION.withDescription("插件已停止接收新后台任务").asRuntimeException()); return;
                }
                if (session.backgrounds.size() >= 256 || session.backgroundRequests.size() >= 100_000) {
                    observer.onError(Status.RESOURCE_EXHAUSTED.withDescription("后台租约数量已达上限").asRuntimeException()); return;
                }
                if (!session.backgroundRequests.add(context.getRequestId())) {
                    observer.onError(Status.ALREADY_EXISTS.withDescription("后台租约申请不能重放").asRuntimeException()); return;
                }
                String id = UUID.randomUUID().toString(); String runId = "background:" + id;
                long expires = now + Math.min(Duration.ofMinutes(30).toMillis(), request.getTtlMillis());
                BackgroundReceipt receipt = new BackgroundReceipt(id, expires); session.backgrounds.put(runId, receipt);
                if (!session.accepting.get() || !session.callbacksAllowed.get()) {
                    closeReceipt(session, runId, receipt);
                    observer.onError(Status.FAILED_PRECONDITION.withDescription("插件正在排空").asRuntimeException()); return;
                }
                lease = BackgroundLease.newBuilder().setLeaseId(id).setRunId(runId).setExpiresAtEpochMillis(expires).build();
            }
            observer.onNext(lease); observer.onCompleted();
        }

        @Override public void closeBackground(BackgroundLeaseCloseRequest request, StreamObserver<com.google.protobuf.Empty> observer) {
            Session session = CALLER.get();
            if (session == null || !request.getIdentity().equals(session.identity) || !request.getLeaseId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                observer.onError(Status.PERMISSION_DENIED.withDescription("后台租约身份无效").asRuntimeException()); return;
            }
            String runId = "background:" + request.getLeaseId(); BackgroundReceipt receipt = session.backgrounds.get(runId);
            if (receipt != null && receipt.leaseId.equals(request.getLeaseId())) closeReceipt(session, runId, receipt);
            observer.onNext(com.google.protobuf.Empty.getDefaultInstance()); observer.onCompleted();
        }

        @Override public void invoke(CallRequest request, StreamObserver<RpcEvent> observer) {
            Session session = CALLER.get(); CallContext context = request.getContext();
            boolean background = session != null && !session.runs.contains(context.getRunId());
            boolean allowedBackground = background && activeReceipt(session, context.getRunId()) && registry.allowsBackground(request.getContributionId(), request.getOperation());
            if (session == null || !session.callbacksAllowed.get() || !context.getIdentity().equals(session.identity) || background && !allowedBackground
                    || !validRequestId(context.getRequestId()) || context.getRunId().length() > 128 || context.getDeadlineEpochMillis() <= System.currentTimeMillis()
                    || context.getDeadlineEpochMillis() > System.currentTimeMillis() + Duration.ofMinutes(30).toMillis()) {
                observer.onError(Status.PERMISSION_DENIED.withDescription("回调身份、运行租约或截止时间无效").asRuntimeException()); return;
            }
            if (!callbackSlots.tryAcquire()) { observer.onError(Status.RESOURCE_EXHAUSTED.withDescription("宿主回调并发额度耗尽").asRuntimeException()); return; }
            Context.CancellableContext grpc = Context.current().withCancellation(); AtomicLong sequence = new AtomicLong();
            BackgroundReceipt receipt = background ? session.backgrounds.get(context.getRunId()) : null;
            session.hostCalls.add(grpc); if (receipt != null) receipt.calls.add(grpc);
            try {
                if (grpc.isCancelled() || !validCallback(session, context, background)) throw new CancellationException();
                PluginHostServiceRegistry.HostRequest verified = new PluginHostServiceRegistry.HostRequest(session.identity.getWorkspaceId(), session.identity.getPluginId(),
                        session.identity.getInstanceId(), context.getRunId(), context.getRequestId(), request.getOperation(), request.getPayloadJson(), session.permissions,
                        Instant.ofEpochMilli(receipt == null ? context.getDeadlineEpochMillis() : Math.min(context.getDeadlineEpochMillis(), receipt.expiresAt)), background);
                String result = registry.invoke(request.getContributionId(), verified,
                        () -> grpc.isCancelled() || !validCallback(session, context, background),
                        value -> {
                            if (grpc.isCancelled() || !validCallback(session, context, background)) throw new CancellationException();
                            observer.onNext(RpcEvent.newBuilder().setRequestId(context.getRequestId()).setSequence(sequence.incrementAndGet()).setEventType(EventType.DATA).setPayloadJson(value).build());
                        });
                if (grpc.isCancelled() || !validCallback(session, context, background)) throw new CancellationException();
                observer.onNext(RpcEvent.newBuilder().setRequestId(context.getRequestId()).setSequence(sequence.incrementAndGet()).setEventType(EventType.COMPLETE).setPayloadJson(result == null ? "{}" : result).build());
                observer.onCompleted();
            } catch (Exception failure) { observer.onError(Status.PERMISSION_DENIED.withDescription(session.redact(safeMessage(failure))).asRuntimeException()); }
            finally { session.hostCalls.remove(grpc); if (receipt != null) receipt.calls.remove(grpc); grpc.cancel(null); callbackSlots.release(); }
        }
    }

    private boolean validCallback(Session session, CallContext context, boolean background) {
        return session.callbacksAllowed.get() && System.currentTimeMillis() < context.getDeadlineEpochMillis()
                && (background ? activeReceipt(session, context.getRunId()) : session.runs.contains(context.getRunId()));
    }

    private static final class BearerCredentials implements ClientInterceptor {
        private final String token;
        BearerCredentials(String token) { this.token = token; }
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
                @Override public void start(Listener<RespT> listener, Metadata headers) { headers.put(AUTH, "Bearer " + token); super.start(listener, headers); }
            };
        }
    }

    private static String token() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static boolean validRequestId(String value) { return !value.isBlank() && value.length() <= 128; }
    static List<String> secretValues(ObjectMapper json, PluginManifest manifest, String configuration) {
        Set<String> values = new java.util.HashSet<>();
        try { collectSecretValues(json.readTree(configuration), manifest.secretKeys(), values); }
        catch (Exception failure) { throw new PluginException("INVALID_CONFIGURATION", "插件敏感配置无法解析"); }
        return values.stream().filter(value -> !value.isEmpty()).sorted(java.util.Comparator.comparingInt(String::length).reversed()).toList();
    }
    private static void collectSecretValues(JsonNode node, Set<String> declared, Set<String> values) {
        if (node == null) return;
        if (node.isObject()) node.fields().forEachRemaining(field -> {
            String normalized = field.getKey().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (field.getValue().isTextual() && (declared.contains(field.getKey()) || normalized.matches(".*(?:password|passwd|passphrase|secret|token|apikey|authorization|privatekey|credential).*"))) values.add(field.getValue().asText());
            collectSecretValues(field.getValue(), Set.of(), values);
        });
        else if (node.isArray()) node.forEach(child -> collectSecretValues(child, Set.of(), values));
    }
    private static boolean constantEquals(String left, String right) { return java.security.MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8)); }
    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"); }
    private static String safeMessage(Throwable failure) { String value = failure.getMessage(); return value == null ? failure.getClass().getSimpleName() : value.substring(0, Math.min(512, value.length())); }
}
