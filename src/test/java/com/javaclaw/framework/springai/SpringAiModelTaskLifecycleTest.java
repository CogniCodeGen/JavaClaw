package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.core.RunUsageLedger;
import com.javaclaw.framework.spi.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static org.junit.jupiter.api.Assertions.*;

/** 只读辅助模型可逻辑收尾，但物理调用与预算租约必须继续受到保护。 */
class SpringAiModelTaskLifecycleTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 晚到失败响应的持久化异常或任务状态竞态不能跳过真实收费(boolean rejectUsage) throws Exception {
        var owner = RunId.random();
        var state = new java.util.concurrent.atomic.AtomicReference<>(RunState.RUNNING);
        var ownerRequest = RunRequest.builder().agent(AgentDefinitionRef.latest("fixture"))
                .profile(RunProfileRef.latest("fixture")).source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "session")).input(InputBlock.text("观察窗口")).build();
        var journal = new java.util.concurrent.CopyOnWriteArrayList<RunEventEnvelope>();
        var writes = new AtomicInteger();
        var store = (RunStore) java.lang.reflect.Proxy.newProxyInstance(RunStore.class.getClassLoader(),
                new Class<?>[] {RunStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("find")) {
                        var now = java.time.Instant.now();
                        return java.util.Optional.of(new StoredRun(new RunSnapshot(owner, state.get(), "fixture",
                                journal.size(), now, now, null, null, 0), ownerRequest));
                    }
                    if (method.getName().equals("withRunAcceptanceLock"))
                        return ((java.util.function.Supplier<?>) args[1]).get();
                    if (method.getName().equals("append")) {
                        var draft = (RunEventDraft) args[3];
                        if (draft.type().equals("core.step.usage")) {
                            int count = writes.incrementAndGet();
                            if (rejectUsage) throw new IllegalStateException("费用审计存储故障");
                            if (count == 1) {
                                state.set(RunState.RUNNING); // 模拟暂停任务被其他线程恢复的 CAS 竞态。
                                return java.util.Optional.empty();
                            }
                            assertEquals(state.get(), args[2], "费用补记不得改变所属任务状态");
                        }
                        var event = new RunEventEnvelope(owner.value(), journal.size() + 1, java.time.Instant.now(),
                                draft.type(), draft.schemaVersion(), draft.producer(), null, null, draft.payload());
                        journal.add(event);
                        return java.util.Optional.of(event);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        var ledger = new RunUsageLedger();
        ledger.open(owner, RunBudget.UNBOUNDED, ownerRequest.scope());
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var partialFailure = new ManagedInferenceChatModel.ManagedInferenceModelException(
                "inference_error", "提供方部分返回后失败", false,
                new com.javaclaw.inference.api.InferenceUsage(7, 3), "fixture", null);
        var models = new SpringAiModelRegistry();
        models.register("fixture:model", prompt -> {
            started.countDown();
            while (release.getCount() != 0) {
                try { release.await(); }
                catch (InterruptedException ignored) {}
            }
            throw partialFailure;
        });
        models.route("workspace", ModelTier.LIGHT, "fixture:model");
        var executor = new HoldingExecutor();
        var gateway = new SpringAiModelTaskGateway(models, ledger, new RecordingAudit(),
                new ObjectMapper(), executor, store);
        try {
            var result = gateway.execute(request(owner, Duration.ofSeconds(5), 2)).toCompletableFuture();
            assertTrue(started.await(1, TimeUnit.SECONDS));
            executor.fail(new TimeoutException("视觉任务已超时"));
            assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            state.set(RunState.PAUSED);
            release.countDown();
            executor.terminated.get(2, TimeUnit.SECONDS);
            assertEquals(7, ledger.snapshot(owner).inputTokens());
            assertEquals(3, ledger.snapshot(owner).outputTokens());
            assertFalse(journal.stream().anyMatch(event -> event.type().equals("core.step.completed")));
            if (rejectUsage) {
                assertTrue(java.util.Arrays.stream(partialFailure.getSuppressed())
                        .anyMatch(failure -> failure.getMessage().equals("费用审计存储故障")));
            } else {
                assertEquals(2, writes.get(), "CAS 未写入必须重读当前状态后再补记");
                assertEquals(1, journal.stream().filter(event -> event.type().equals("core.step.usage")).count());
            }
        } finally { release.countDown(); }
    }

    @Test
    void 内部超时不等待忽略中断的提供方且晚到回包不能重试或宣告成功() throws Exception {
        var executor = new HoldingExecutor();
        var audit = new RecordingAudit();
        var provider = new HoldingProvider("not-json");
        var fixture = fixture(executor, audit, provider);
        try {
            var result = fixture.gateway.execute(request(fixture.owner, Duration.ofSeconds(5), 2))
                    .toCompletableFuture();
            assertTrue(provider.started.await(1, TimeUnit.SECONDS));
            executor.fail(new TimeoutException("内部视觉任务超时：90 秒"));

            var failure = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertEquals(1, audit.failed.get());
            assertFalse(executor.terminated.isDone());

            // 保留物理锁，同时使下一轮主模型有界暂停，不再等到整个 run 耗尽预算。
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(
                    TurnPausedException.class, () -> fixture.ledger.beginModelCall(fixture.owner)));
            provider.release.countDown();
            executor.terminated.get(1, TimeUnit.SECONDS);
            try (var ignored = fixture.ledger.beginModelCall(fixture.owner)) {}
            assertEquals(1, provider.calls.get(), "逻辑失败后的旧任务不得重试");
            assertEquals(7, fixture.ledger.snapshot(fixture.owner).inputTokens());
            assertEquals(0, audit.completed.get());
            assertEquals(1, audit.failed.get());
        } finally { provider.release.countDown(); }
    }

    @Test
    void 失败审计抛异常仍保留原超时并使公共结果及时结束() throws Exception {
        var executor = new HoldingExecutor();
        var audit = new RecordingAudit();
        audit.failAudit.set(true);
        var provider = new HoldingProvider("{\"ok\":true}");
        var fixture = fixture(executor, audit, provider);
        try {
            var result = fixture.gateway.execute(request(fixture.owner, Duration.ofSeconds(5), 0))
                    .toCompletableFuture();
            assertTrue(provider.started.await(1, TimeUnit.SECONDS));
            executor.fail(new TimeoutException("内部任务超时"));
            var failure = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
            var timeout = assertInstanceOf(TimeoutException.class, failure.getCause());
            assertTrue(java.util.Arrays.stream(timeout.getSuppressed())
                    .anyMatch(cause -> cause.getMessage().equals("审计故障")));
            assertEquals(1, audit.failed.get());
        } finally { provider.release.countDown(); }
    }

    @Test
    void 失败审计忽略中断也不能把已超时的公共结果重新挂住() throws Exception {
        var executor = new HoldingExecutor();
        var audit = new RecordingAudit();
        audit.blockAudit.set(true);
        var provider = new HoldingProvider("{\"ok\":true}");
        var fixture = fixture(executor, audit, provider);
        try {
            var result = fixture.gateway.execute(request(fixture.owner, Duration.ofSeconds(5), 0))
                    .toCompletableFuture();
            assertTrue(provider.started.await(1, TimeUnit.SECONDS));
            executor.fail(new TimeoutException("内部任务超时"));
            var failure = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertEquals("内部任务超时", failure.getCause().getMessage());
            assertEquals(1, audit.failed.get());
            assertFalse(executor.terminated.isDone());
        } finally { audit.auditRelease.countDown(); provider.release.countDown(); }
    }

    @Test
    void 公共结果取消立即收尾并转发物理取消而不接纳晚到成功() throws Exception {
        var executor = new HoldingExecutor();
        var audit = new RecordingAudit();
        var provider = new HoldingProvider("{\"ok\":true}");
        var fixture = fixture(executor, audit, provider);
        try {
            var result = fixture.gateway.execute(request(fixture.owner, Duration.ofSeconds(5), 0))
                    .toCompletableFuture();
            assertTrue(provider.started.await(1, TimeUnit.SECONDS));
            assertTrue(result.cancel(true));
            assertTrue(result.isCancelled());
            assertTrue(executor.cancelled.await(1, TimeUnit.SECONDS));
            provider.release.countDown();
            executor.terminated.get(1, TimeUnit.SECONDS);
            assertTrue(audit.recorded.await(1, TimeUnit.SECONDS));
            assertEquals(0, audit.completed.get());
            assertEquals(1, audit.failed.get());
            assertEquals(7, fixture.ledger.snapshot(fixture.owner).inputTokens());
        } finally { provider.release.countDown(); }
    }

    @Test
    void 执行器没有发出超时信号时网关仍有独立有界收尾() throws Exception {
        var executor = new HoldingExecutor();
        var audit = new RecordingAudit();
        var provider = new HoldingProvider("{\"ok\":true}");
        var fixture = fixture(executor, audit, provider);
        try {
            var result = fixture.gateway.execute(request(fixture.owner, Duration.ofMillis(150), 0))
                    .toCompletableFuture();
            assertTrue(provider.started.await(1, TimeUnit.SECONDS));
            var failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertTrue(executor.cancelled.await(1, TimeUnit.SECONDS));
            assertFalse(executor.terminated.isDone());
            assertEquals(1, audit.failed.get());
        } finally { provider.release.countDown(); }
    }

    private static Fixture fixture(HoldingExecutor executor, RecordingAudit audit, ChatModel model) {
        var owner = RunId.random();
        var ledger = new RunUsageLedger();
        ledger.open(owner, RunBudget.UNBOUNDED, new RunScope("workspace", "user", "session"));
        var models = new SpringAiModelRegistry();
        models.register("test:model", model);
        models.route(ModelTier.LIGHT, "test:model");
        return new Fixture(new SpringAiModelTaskGateway(models, ledger, audit, new ObjectMapper(), executor),
                ledger, owner);
    }

    private static ModelTaskRequest request(RunId owner, Duration timeout, int retries) {
        var schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        schema.putObject("properties").putObject("ok").put("type", "boolean");
        schema.putArray("required").add("ok");
        schema.put("additionalProperties", false);
        return new ModelTaskRequest("vision", ModelTier.LIGHT,
                JsonNodeFactory.instance.objectNode(), List.of(), schema, owner, "vision", timeout,
                retries, () -> false, false);
    }

    private record Fixture(SpringAiModelTaskGateway gateway, RunUsageLedger ledger, RunId owner) {}

    private static final class HoldingProvider implements ChatModel {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        private final String output;
        HoldingProvider(String output) { this.output = output; }
        @Override public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
            calls.incrementAndGet();
            started.countDown();
            while (release.getCount() != 0) {
                try { release.await(); }
                catch (InterruptedException ignored) { /* 模拟忽略中断的远端提供方。 */ }
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(output))),
                    ChatResponseMetadata.builder().model("fixture")
                            .usage(new DefaultUsage(7, 3)).build());
        }
    }

    private static final class RecordingAudit implements ModelTaskAuditSink {
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        final AtomicBoolean failAudit = new AtomicBoolean();
        final AtomicBoolean blockAudit = new AtomicBoolean();
        final CountDownLatch auditRelease = new CountDownLatch(1);
        final CountDownLatch recorded = new CountDownLatch(1);
        @Override public void started(ModelTaskRequest request) {}
        @Override public void completed(ModelTaskRequest request, ModelTaskResult result) { completed.incrementAndGet(); }
        @Override public void failed(ModelTaskRequest request, Throwable failure) {
            failed.incrementAndGet();
            recorded.countDown();
            while (blockAudit.get() && auditRelease.getCount() != 0) {
                try { auditRelease.await(); }
                catch (InterruptedException ignored) { /* 模拟忽略中断的审计。 */ }
            }
            if (failAudit.get()) throw new IllegalStateException("审计故障");
        }
    }

    private static final class HoldingExecutor implements CancellableTaskExecutor {
        final CompletableFuture<Void> terminated = new CompletableFuture<>();
        final CountDownLatch cancelled = new CountDownLatch(1);
        private CompletableFuture<?> logical;
        private Thread carrier;
        @Override public void execute(Runnable command) { Thread.startVirtualThread(command); }
        @Override public <T> CancellableTask<T> submit(String name, Duration timeout,
                CancellationToken cancellation, Callable<T> action) {
            var completion = new CompletableFuture<T>();
            logical = completion;
            carrier = Thread.startVirtualThread(() -> {
                try { completion.complete(action.call()); }
                catch (Throwable failure) { completion.completeExceptionally(failure); }
                finally { terminated.complete(null); }
            });
            return new CancellableTask<>() {
                @Override public CompletionStage<T> completion() { return completion; }
                @Override public CompletionStage<Void> termination() { return terminated; }
                @Override public boolean cancel() {
                    cancelled.countDown();
                    carrier.interrupt();
                    return true;
                }
            };
        }
        void fail(Throwable failure) { logical.completeExceptionally(failure); }
    }
}
