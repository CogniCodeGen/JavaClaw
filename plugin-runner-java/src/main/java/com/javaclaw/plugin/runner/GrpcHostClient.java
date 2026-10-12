package com.javaclaw.plugin.runner;

import com.javaclaw.plugin.protocol.v2.*;
import com.javaclaw.plugin.sdk.BearerTokens;
import com.javaclaw.plugin.sdk.HostClient;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** 反向服务只连接启动时固定的回环地址，不接受插件指定任意连接目标。 */
final class GrpcHostClient implements HostClient, AutoCloseable {
    private final ManagedChannel channel;
    private final HostServicesGrpc.HostServicesBlockingStub stub;
    private final PluginIdentity identity;
    private final ThreadLocal<Work> work = new ThreadLocal<>();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("plugin-background-deadlines").daemon(true).factory());
    GrpcHostClient(RunnerBootstrap bootstrap) {
        identity = bootstrap.identity();
        channel = NettyChannelBuilder.forAddress("127.0.0.1", bootstrap.hostPort()).usePlaintext()
                .disableRetry().intercept(BearerTokens.client(bootstrap.hostToken())).build();
        stub = HostServicesGrpc.newBlockingStub(channel);
    }
    @Override public String invoke(CallContext context, String serviceId, String operation,
                                   String payloadJson, Consumer<String> events) {
        if (!context.getIdentity().equals(identity)) throw Status.PERMISSION_DENIED
                .withDescription("反向调用身份不匹配").asRuntimeException();
        if (context.getRequestId().isBlank() || serviceId == null || serviceId.isBlank()) {
            throw Status.INVALID_ARGUMENT.withDescription("反向调用缺少请求或服务标识").asRuntimeException();
        }
        Work current = work.get();
        if (current != null) {
            current.check();
            String run = current.receipt != null || context.getRunId().isBlank() ? current.context.getRunId() : context.getRunId();
            context = context.toBuilder().setRunId(run).setDeadlineEpochMillis(
                    Math.min(context.getDeadlineEpochMillis(), current.context.getDeadlineEpochMillis())).build();
        }
        long remaining = context.getDeadlineEpochMillis() - System.currentTimeMillis();
        if (remaining <= 0) throw Status.DEADLINE_EXCEEDED.asRuntimeException();
        var responses = stub.withDeadlineAfter(remaining, TimeUnit.MILLISECONDS).invoke(CallRequest.newBuilder()
                .setContext(context).setContributionId(serviceId).setOperation(operation)
                .setPayloadJson(payloadJson).build());
        String result = "{}";
        boolean complete = false;
        while (responses.hasNext()) {
            var event = responses.next();
            if (!event.getRequestId().equals(context.getRequestId())) {
                throw Status.DATA_LOSS.withDescription("宿主响应请求标识不匹配").asRuntimeException();
            }
            if (event.getEventType() == EventType.ERROR) throw Status.UNKNOWN
                    .withDescription("宿主能力调用失败: " + event.getErrorCode()).asRuntimeException();
            if (event.getEventType() == EventType.DATA) { result = event.getPayloadJson(); events.accept(result); }
            if (event.getEventType() == EventType.COMPLETE) {
                complete = true;
                if (!event.getPayloadJson().isBlank() && !event.getPayloadJson().equals("{}")) {
                    result = event.getPayloadJson();
                }
            }
        }
        if (!complete) throw Status.UNAVAILABLE.withDescription("宿主流未正常结束").asRuntimeException();
        return result;
    }

    boolean taskCancelled() {
        Work current = work.get();
        return current != null && (current.expired.get() || System.currentTimeMillis() >= current.context.getDeadlineEpochMillis());
    }
    TaskScope rpcScope(CallContext context) {
        Work previous = work.get();
        Work current = new Work(context, null);
        work.set(current); current.threads.add(Thread.currentThread());
        return new TaskScope(previous, current);
    }
    TaskToken captureTask() {
        Work current = work.get();
        if (current != null) current.references.incrementAndGet();
        return new TaskToken(current);
    }

    /** 子任务持有同一租约引用，根任务结束后最后一个子任务负责归还。 */
    final class TaskToken implements AutoCloseable {
        private Work current;
        private Work previous;
        private boolean entered;
        private TaskToken(Work current) { this.current = current; }
        void enter() {
            if (current == null) {
                long now = System.currentTimeMillis();
                BackgroundLease receipt = stub.withDeadlineAfter(5, TimeUnit.SECONDS).openBackground(BackgroundLeaseRequest.newBuilder()
                        .setContext(CallContext.newBuilder().setIdentity(identity).setRequestId(UUID.randomUUID().toString())
                                .setDeadlineEpochMillis(now + 5000)).setTtlMillis(1_800_000).build());
                if (receipt.getLeaseId().isBlank() || receipt.getRunId().isBlank() || receipt.getExpiresAtEpochMillis() <= now
                        || receipt.getExpiresAtEpochMillis() > now + 1_805_000)
                    throw Status.DATA_LOSS.withDescription("宿主后台租约无效").asRuntimeException();
                current = new Work(CallContext.newBuilder().setIdentity(identity).setRunId(receipt.getRunId())
                        .setDeadlineEpochMillis(receipt.getExpiresAtEpochMillis()).build(), receipt);
            }
            previous = work.get(); work.set(current); entered = true;
            current.threads.add(Thread.currentThread());
            current.check();
        }
        @Override public void close() {
            if (current == null) return;
            if (entered) { current.threads.remove(Thread.currentThread()); restore(previous); }
            current.release(); current = null;
        }
    }
    final class TaskScope implements AutoCloseable {
        private final Work previous, current;
        private TaskScope(Work previous, Work current) { this.previous = previous; this.current = current; }
        void cancel() { current.expire(); }
        @Override public void close() {
            current.threads.remove(Thread.currentThread());
            // RPC 返回后宿主即可释放 Run，派生任务必须与本次调用一起结束。
            current.expire();
            restore(previous); current.release();
        }
    }
    private void restore(Work previous) { if (previous == null) work.remove(); else work.set(previous); }

    private final class Work {
        private final CallContext context;
        private final BackgroundLease receipt;
        private final AtomicInteger references = new AtomicInteger(1);
        private final AtomicBoolean expired = new AtomicBoolean();
        private final Set<Thread> threads = ConcurrentHashMap.newKeySet();
        private final ScheduledFuture<?> timeout;
        private Work(CallContext context, BackgroundLease receipt) {
            this.context = context; this.receipt = receipt;
            timeout = deadlines.schedule(() -> {
                expire();
            }, Math.max(0, context.getDeadlineEpochMillis() - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        }
        private void expire() { expired.set(true); threads.forEach(Thread::interrupt); }
        private void check() {
            if (expired.get() || System.currentTimeMillis() >= context.getDeadlineEpochMillis())
                throw new CancellationException("插件工作租约已到期");
        }
        private void release() {
            if (references.decrementAndGet() != 0) return;
            timeout.cancel(false);
            if (receipt != null) {
                try { stub.withDeadlineAfter(2, TimeUnit.SECONDS).closeBackground(BackgroundLeaseCloseRequest.newBuilder()
                        .setIdentity(identity).setLeaseId(receipt.getLeaseId()).build()); }
                catch (RuntimeException ignored) { /* 宿主 TTL 与进程撤权仍会回收失联租约。 */ }
            }
        }
    }
    @Override public void close() { deadlines.shutdownNow(); channel.shutdownNow(); }
}
