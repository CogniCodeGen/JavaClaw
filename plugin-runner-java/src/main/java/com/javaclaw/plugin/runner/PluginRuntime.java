package com.javaclaw.plugin.runner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.javaclaw.plugin.protocol.v2.*;
import com.javaclaw.plugin.sdk.Cancellation;
import com.javaclaw.plugin.sdk.Plugin;
import com.javaclaw.plugin.sdk.PluginCall;
import com.javaclaw.plugin.sdk.PluginContext;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 单个不可变插件实例的生命周期与调用边界，不持有任何 Desktop 类型。 */
final class PluginRuntime implements AutoCloseable {
    private final RunnerBootstrap bootstrap;
    private final ThreadLocal<Boolean> inRpc = ThreadLocal.withInitial(() -> false);
    private final TrackedPluginExecutor tasks = new TrackedPluginExecutor(inRpc::get, () -> this.loader,
            this::signalDrained, () -> this.host.captureTask());
    private final Map<String, ActiveCall> active = new ConcurrentHashMap<>();
    private final Set<String> acceptedRequests = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean preparingDrain = new AtomicBoolean();
    private final Object lifecycle = new Object();
    private final Object drained = new Object();
    private volatile String state = "STARTING";
    private volatile Plugin plugin;
    private volatile PluginDescriptor descriptor;
    private volatile Thread drainPreparation;
    private PluginArtifactLoader loader;
    private GrpcHostClient host;

    PluginRuntime(RunnerBootstrap bootstrap) { this.bootstrap = bootstrap; }

    PluginDescriptor initialize(InitializeRequest request) throws Exception {
        identity(request.getIdentity());
        if (request.getProtocolMajor() != 2 || request.getProtocolMinor() < 0) {
            throw Status.FAILED_PRECONDITION.withDescription("插件协议版本不兼容").asRuntimeException();
        }
        JsonObject manifest = object(request.getManifestJson());
        JsonObject configuration = object(request.getConfigurationJson());
        validateManifest(manifest);
        synchronized (lifecycle) {
            if (descriptor != null) throw Status.ALREADY_EXISTS
                    .withDescription("插件实例已经初始化").asRuntimeException();
            if (stopped.get() || !state.equals("STARTING")) throw Status.FAILED_PRECONDITION.asRuntimeException();
            try {
                loader = PluginArtifactLoader.open(bootstrap);
                try (LoaderScope ignored = pluginScope()) {
                    Plugin candidate = loader.instantiate(bootstrap.mainClass());
                    plugin = candidate;
                    List<String> errors = List.copyOf(candidate.validateConfiguration(configuration.toString()));
                    if (!errors.isEmpty()) throw Status.INVALID_ARGUMENT
                            .withDescription("插件配置校验失败").asRuntimeException();
                    List<Contribution> contributions = List.copyOf(candidate.contributions());
                    validateContributions(manifest, contributions);
                    Files.createDirectories(bootstrap.privateDataDirectory());
                    host = new GrpcHostClient(bootstrap);
                    candidate.start(new PluginContext(bootstrap.identity(), configuration.toString(),
                            bootstrap.privateDataDirectory(), tasks, host, () -> stopped.get() || host.taskCancelled()));
                    if (stopped.get()) throw Status.CANCELLED.asRuntimeException();
                    descriptor = PluginDescriptor.newBuilder().setIdentity(bootstrap.identity())
                            .setProtocolMajor(2).setProtocolMinor(0).addAllContributions(contributions)
                            .setConfigurationSchemaJson(jsonMember(manifest, "configurationSchema"))
                            .setUiSchemaJson(jsonMember(manifest, "uiSchema")).build();
                    state = "READY";
                    return descriptor;
                }
            } catch (Exception | Error failure) {
                state = "FAILED";
                close();
                throw failure;
            }
        }
    }

    ValidationResult validate(ConfigurationRequest request) {
        identity(request.getIdentity());
        object(request.getConfigurationJson());
        Plugin current = requireReady();
        List<String> errors;
        try (LoaderScope ignored = pluginScope()) {
            errors = current.validateConfiguration(request.getConfigurationJson());
        }
        return ValidationResult.newBuilder().setValid(errors.isEmpty()).addAllErrors(errors).build();
    }

    HealthResponse health(PluginIdentity identity) {
        identity(identity);
        return HealthResponse.newBuilder().setState(state).setActiveRequests(activeCount()).build();
    }

    DrainResponse drain(DrainRequest request) throws InterruptedException {
        identity(request.getIdentity());
        synchronized (lifecycle) {
            if (state.equals("READY") || state.equals("STARTING")) {
                state = "DRAINING";
                tasks.beginDrain();
                Plugin current = plugin;
                if (current != null) {
                    preparingDrain.set(true);
                    // 控制 RPC 不等待插件入口停止；准备工作本身也计入排空状态。
                    drainPreparation = Thread.ofVirtual().start(() -> {
                        try (LoaderScope ignored = pluginScope()) { current.beginDrain(); }
                        catch (Throwable failure) { state = "FAILED"; }
                        finally { preparingDrain.set(false); signalDrained(); }
                    });
                }
            }
        }
        long nanos = TimeUnit.MILLISECONDS.toNanos(Math.min(30_000, Math.max(0, request.getTimeoutMillis())));
        long end = System.nanoTime() + nanos;
        synchronized (drained) {
            while (activeCount() != 0 && nanos > 0) {
                TimeUnit.NANOSECONDS.timedWait(drained, nanos);
                nanos = end - System.nanoTime();
            }
        }
        int remaining = activeCount();
        return DrainResponse.newBuilder().setDrained(remaining == 0).setActiveRequests(remaining).build();
    }

    private int activeCount() { return active.size() + tasks.activeCount() + (preparingDrain.get() ? 1 : 0); }
    private void signalDrained() { synchronized (drained) { drained.notifyAll(); } }

    void cancel(CancelRequest request) {
        identity(request.getIdentity());
        ActiveCall call = active.get(request.getRequestId());
        if (call != null) call.cancel();
    }

    void stream(CapabilityKind kind, CallRequest request, StreamObserver<RpcEvent> observer) {
        try (ActiveCall call = enter(kind, request); LoaderScope ignored = pluginScope()) {
            AtomicLong sequence = new AtomicLong();
            AtomicBoolean terminal = new AtomicBoolean();
            if (observer instanceof ServerCallStreamObserver<RpcEvent> server) {
                server.setOnCancelHandler(() -> {
                    call.cancel();
                    synchronized (observer) { observer.notifyAll(); }
                });
                server.setOnReadyHandler(() -> {
                    synchronized (observer) { observer.notifyAll(); }
                });
            }
            Plugin current = plugin;
            synchronized (observer) {
                call.throwIfCancelled();
            }
            current.execute(new PluginCall(kind, request, call), payload -> {
                call.throwIfCancelled();
                if (payload.length() > 8 * 1024 * 1024
                        || payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8 * 1024 * 1024) {
                    throw Status.RESOURCE_EXHAUSTED.withDescription("插件事件超过大小上限").asRuntimeException();
                }
                objectOrValue(payload);
                synchronized (observer) {
                    if (terminal.get()) throw new IllegalStateException("调用已结束");
                    // 等待传输窗口，避免慢客户端让插件事件无限积压在 gRPC 内存队列。
                    if (observer instanceof ServerCallStreamObserver<RpcEvent> server) {
                        while (!server.isReady()) {
                            call.throwIfCancelled();
                            try { observer.wait(50); }
                            catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new java.util.concurrent.CancellationException("传输等待已取消");
                            }
                        }
                    }
                    observer.onNext(RpcEvent.newBuilder().setRequestId(request.getContext().getRequestId())
                            .setEventType(EventType.DATA).setSequence(sequence.incrementAndGet())
                            .setPayloadJson(payload).build());
                }
            });
            call.throwIfCancelled();
            synchronized (observer) {
                terminal.set(true);
                observer.onNext(RpcEvent.newBuilder().setRequestId(request.getContext().getRequestId())
                        .setEventType(EventType.COMPLETE).setSequence(sequence.incrementAndGet())
                        .setPayloadJson("{}").build());
                observer.onCompleted();
            }
        } catch (Throwable failure) { fail(observer, failure); }
    }

    CallResult retrieve(CallRequest request) throws Exception {
        CapabilityKind kind = descriptor == null ? CapabilityKind.CONTEXT : descriptor.getContributionsList().stream()
                .filter(value -> value.getId().equals(request.getContributionId()) && value.getKind() == CapabilityKind.SKILL)
                .findFirst().map(Contribution::getKind).orElse(CapabilityKind.CONTEXT);
        try (ActiveCall call = enter(kind, request); LoaderScope ignored = pluginScope()) {
            String result = plugin.retrieve(new PluginCall(kind, request, call));
            call.throwIfCancelled();
            objectOrValue(result);
            return CallResult.newBuilder().setPayloadJson(result).build();
        }
    }

    HookResponse hook(HookRequest request) throws Exception {
        if (request.getPoint() == HookPoint.HOOK_UNSPECIFIED || request.getRemainderBudget() < 0) {
            throw Status.INVALID_ARGUMENT.withDescription("数据钩子参数无效").asRuntimeException();
        }
        CallRequest callRequest = CallRequest.newBuilder().setContext(request.getContext())
                .setContributionId(request.getContributionId()).setOperation(request.getPoint().name())
                .setPayloadJson(request.getInputJson()).build();
        try (ActiveCall call = enter(CapabilityKind.HOOK, callRequest); LoaderScope ignored = pluginScope()) {
            HookResponse result = plugin.hook(request, call);
            call.throwIfCancelled();
            objectOrValue(result.getOutputJson());
            Set<String> allowed = new HashSet<>(request.getAllowedPermissionsList());
            if (result.getRemainderBudget() < 0 || result.getRemainderBudget() > request.getRemainderBudget()
                    || !allowed.contains("*") && !allowed.containsAll(result.getAllowedPermissionsList())) {
                throw Status.PERMISSION_DENIED.withDescription("数据钩子不得扩大权限或预算").asRuntimeException();
            }
            return result;
        }
    }

    void identity(PluginIdentity identity) {
        if (!bootstrap.identity().equals(identity)) throw Status.PERMISSION_DENIED
                .withDescription("插件实例、工件、工作区或配置版本不匹配").asRuntimeException();
    }

    private ActiveCall enter(CapabilityKind kind, CallRequest request) {
        identity(request.getContext().getIdentity());
        String id = request.getContext().getRequestId();
        if (id.isBlank()) throw Status.INVALID_ARGUMENT.withDescription("缺少请求标识").asRuntimeException();
        if (request.getContext().getDeadlineEpochMillis() <= System.currentTimeMillis()) {
            throw Status.DEADLINE_EXCEEDED.asRuntimeException();
        }
        objectOrValue(request.getPayloadJson());
        synchronized (lifecycle) {
            requireReady();
            if (descriptor.getContributionsList().stream().noneMatch(contribution ->
                    contribution.getId().equals(request.getContributionId()) && contribution.getKind() == kind)) {
                throw Status.NOT_FOUND.withDescription("插件没有声明该贡献").asRuntimeException();
            }
            if (active.size() >= 32 || acceptedRequests.size() >= 100_000) {
                throw Status.RESOURCE_EXHAUSTED.withDescription("插件实例请求配额已满").asRuntimeException();
            }
            if (!acceptedRequests.add(id)) throw Status.ALREADY_EXISTS
                    .withDescription("同一实例内禁止重放请求").asRuntimeException();
            ActiveCall call = new ActiveCall(id, request.getContext());
            active.put(id, call);
            return call;
        }
    }

    private Plugin requireReady() {
        Plugin current = plugin;
        if ((!state.equals("READY") && !state.equals("DRAINING")) || current == null || stopped.get()) throw Status.UNAVAILABLE
                .withDescription("插件实例尚未就绪或已停止").asRuntimeException();
        return current;
    }

    private void validateManifest(JsonObject manifest) {
        if (!manifest.has("id") || !manifest.get("id").getAsString().equals(bootstrap.identity().getPluginId())
                || !manifest.has("entrypoint") || !manifest.get("entrypoint").getAsString().equals(bootstrap.mainClass())
                || !manifest.has("protocol") || manifest.getAsJsonObject("protocol").get("major").getAsInt() != 2) {
            throw Status.FAILED_PRECONDITION.withDescription("插件声明与启动契约不匹配").asRuntimeException();
        }
    }

    private static void validateContributions(JsonObject manifest, List<Contribution> contributions) {
        Map<String, String> declared = new HashMap<>();
        if (manifest.has("contributions")) {
            for (var element : manifest.getAsJsonArray("contributions")) {
                JsonObject item = element.getAsJsonObject();
                String id = item.get("id").getAsString();
                if (declared.putIfAbsent(id, item.get("capability").getAsString().toUpperCase(java.util.Locale.ROOT)) != null) {
                    throw new IllegalArgumentException("插件贡献标识重复");
                }
            }
        }
        Set<String> actual = new HashSet<>();
        for (Contribution contribution : contributions) {
            if (contribution.getId().isBlank() || !actual.add(contribution.getId())
                    || !contribution.getKind().name().equals(declared.get(contribution.getId()))) {
                throw Status.FAILED_PRECONDITION.withDescription("插件动态贡献与已审核声明不匹配").asRuntimeException();
            }
            object(contribution.getInputSchemaJson().isBlank() ? "{}" : contribution.getInputSchemaJson());
            object(contribution.getOutputSchemaJson().isBlank() ? "{}" : contribution.getOutputSchemaJson());
        }
        if (!actual.equals(declared.keySet())) throw Status.FAILED_PRECONDITION
                .withDescription("插件未实现全部声明贡献").asRuntimeException();
    }

    private static String jsonMember(JsonObject manifest, String name) {
        return manifest.has(name) ? manifest.get(name).toString() : "{}";
    }
    private static JsonObject object(String json) {
        try { return JsonParser.parseString(json).getAsJsonObject(); }
        catch (RuntimeException invalid) { throw Status.INVALID_ARGUMENT.withDescription("必须提供 JSON 对象").asRuntimeException(); }
    }
    private static void objectOrValue(String json) {
        try {
            if (json == null || json.isBlank()) throw new IllegalArgumentException();
            JsonParser.parseString(json);
        } catch (RuntimeException invalid) { throw Status.INVALID_ARGUMENT.withDescription("JSON 数据无效").asRuntimeException(); }
    }
    static void fail(StreamObserver<?> observer, Throwable failure) {
        if (failure instanceof InterruptedException || failure instanceof java.util.concurrent.CancellationException) {
            observer.onError(Status.CANCELLED.asRuntimeException());
        } else if (failure instanceof io.grpc.StatusRuntimeException status) {
            observer.onError(status);
        } else {
            // 插件异常信息可能包含秘密，跨边界只返回稳定错误类别。
            observer.onError(Status.INTERNAL.withDescription("插件能力执行失败").asRuntimeException());
        }
    }
    @Override public void close() {
        if (!stopped.compareAndSet(false, true)) return;
        if (!state.equals("FAILED")) state = "STOPPED";
        active.values().forEach(ActiveCall::cancel);
        Thread preparation = drainPreparation;
        if (preparation != null) preparation.interrupt();
        Plugin current = plugin;
        plugin = null;
        if (current != null) {
            var closer = Executors.newVirtualThreadPerTaskExecutor();
            try {
                var future = closer.submit(() -> {
                    try (LoaderScope ignored = pluginScope()) { current.close(); }
                    return null;
                });
                try { future.get(5, TimeUnit.SECONDS); }
                catch (Exception failure) { future.cancel(true); closer.shutdownNow(); }
            } finally { closer.shutdownNow(); }
        }
        tasks.shutdownNow();
        if (host != null) host.close();
        if (loader != null) {
            try { loader.close(); }
            catch (Exception ignored) { }
        }
    }

    private LoaderScope pluginScope() { return new LoaderScope(loader); }

    /** 第三方 ServiceLoader 与资源发现也必须使用当前插件的私有类加载器。 */
    private static final class LoaderScope implements AutoCloseable {
        private final Thread thread = Thread.currentThread();
        private final ClassLoader previous = thread.getContextClassLoader();
        private LoaderScope(ClassLoader loader) { thread.setContextClassLoader(loader); }
        @Override public void close() { thread.setContextClassLoader(previous); }
    }

    private final class ActiveCall implements Cancellation, AutoCloseable {
        private final String id;
        private final long deadline;
        private final Thread thread = Thread.currentThread();
        private final Context context = Context.current();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Context.CancellationListener listener = ignored -> cancel();
        private final GrpcHostClient.TaskScope scope;
        private ActiveCall(String id, CallContext request) {
            this.id = id;
            this.deadline = request.getDeadlineEpochMillis();
            scope = host.rpcScope(request);
            inRpc.set(true);
            context.addListener(listener, Runnable::run);
        }
        void cancel() { cancelled.set(true); scope.cancel(); thread.interrupt(); }
        @Override public boolean isCancelled() {
            return cancelled.get() || stopped.get() || context.isCancelled()
                    || System.currentTimeMillis() >= deadline || thread.isInterrupted();
        }
        @Override public void close() {
            context.removeListener(listener);
            active.remove(id, this);
            inRpc.remove();
            scope.close();
            signalDrained();
        }
    }
}
