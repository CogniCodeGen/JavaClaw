package com.javaclaw.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.openai.core.http.AsyncStreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionChunk.Choice;
import com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta;
import com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta.ToolCall;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiToolStreamResponseTest {
    @Test
    void 两种订阅重载均返回装饰响应且保留执行器与单次订阅约束() {
        Source source = new Source();
        OpenAiToolStreamResponse response = new OpenAiToolStreamResponse(source);
        Observed observed = new Observed();
        assertSame(response, response.subscribe(observed));
        assertEquals(1, source.defaultSubscriptions);
        assertEquals(0, source.executorSubscriptions);
        assertThrows(IllegalStateException.class, () -> response.subscribe(observed));

        Source explicitSource = new Source();
        OpenAiToolStreamResponse explicit = new OpenAiToolStreamResponse(explicitSource);
        Executor executor = Runnable::run;
        assertSame(explicit, explicit.subscribe(new Observed(), executor));
        assertSame(executor, explicitSource.executor);
        assertEquals(0, explicitSource.defaultSubscriptions);
        assertEquals(1, explicitSource.executorSubscriptions);
        assertThrows(IllegalStateException.class, () -> explicit.subscribe(observed, executor));
        source.emit(text("实时正文"));
        assertEquals(
                "实时正文",
                observed.values
                        .getFirst()
                        .choices()
                        .getFirst()
                        .delta()
                        .content()
                        .orElseThrow());
        response.close();
        explicit.close();
    }

    @Test
    void 工具缺少结束帧时自有完成结果失败而不是沿用SDK正常EOF() {
        Source source = new Source();
        OpenAiToolStreamResponse response = new OpenAiToolStreamResponse(source);
        Observed observed = new Observed();
        response.subscribe(observed);
        source.emit(tool("call-one", "lookup", "{\"query\":\"value\"}"));
        assertTrue(observed.tools().isEmpty());

        source.complete(null);

        assertTrue(source.onCompleteFuture().isDone());
        assertFalse(source.onCompleteFuture().isCompletedExceptionally());
        Throwable failure = failure(response.onCompleteFuture());
        assertInstanceOf(IllegalStateException.class, failure);
        assertTrue(failure.getMessage().contains("未确认完整工具调用"));
        assertSame(failure, observed.failure);
        assertEquals(1, observed.completions);
        assertTrue(observed.tools().isEmpty(), "EOF不能将未确认的工具伪装为完整调用");
    }

    @Test
    void 显式关闭只作用当前响应且不补发缓存工具或晚到分片() {
        Source source = new Source();
        Source other = new Source();
        OpenAiToolStreamResponse response = new OpenAiToolStreamResponse(source);
        OpenAiToolStreamResponse second = new OpenAiToolStreamResponse(other);
        Observed observed = new Observed();
        response.subscribe(observed);
        second.subscribe(new Observed());
        source.emit(tool("call-one", "lookup", "{"));
        int before = observed.values.size();

        response.close();
        response.close();
        source.late(tool(null, null, "}"));
        source.late(finished());
        source.complete(null);

        assertEquals(1, source.closeCalls);
        assertEquals(0, other.closeCalls);
        assertTrue(response.onCompleteFuture().isCancelled());
        assertEquals(before, observed.values.size());
        assertTrue(observed.tools().isEmpty());
        assertEquals(0, observed.completions);
        assertThrows(IllegalStateException.class, () -> response.subscribe(new Observed()));
        second.close();
    }

    @Test
    void 正常结束之后仍可幂等关闭当前底层响应() {
        Source source = new Source();
        OpenAiToolStreamResponse response = new OpenAiToolStreamResponse(source);
        Observed observed = new Observed();
        response.subscribe(observed);
        source.emit(text("done"));
        source.complete(null);
        response.onCompleteFuture().join();

        response.close();
        response.close();

        assertEquals(1, source.closeCalls);
        assertFalse(response.onCompleteFuture().isCompletedExceptionally());
        assertEquals(1, observed.completions);
    }

    @Test
    void 订阅前HTTP失败和上游流失败保留原始异常对象() {
        Source connecting = new Source();
        OpenAiToolStreamResponse beforeSubscribe = new OpenAiToolStreamResponse(connecting);
        IllegalArgumentException connectionFailure = new IllegalArgumentException("connection rejected");
        connecting.completion.completeExceptionally(connectionFailure);
        assertSame(connectionFailure, failure(beforeSubscribe.onCompleteFuture()));

        Source source = new Source();
        OpenAiToolStreamResponse response = new OpenAiToolStreamResponse(source);
        Observed observed = new Observed();
        response.subscribe(observed);
        IllegalStateException streamFailure = new IllegalStateException("upstream rejected");
        source.complete(streamFailure);
        assertSame(streamFailure, failure(response.onCompleteFuture()));
        assertSame(streamFailure, observed.failure);
        assertEquals(1, observed.completions);
        beforeSubscribe.close();
        response.close();
    }

    @Test
    void 消费者拒绝分片时通过SDK结束回调传播原始异常() {
        Source source = new Source();
        OpenAiToolStreamResponse response = new OpenAiToolStreamResponse(source);
        IllegalArgumentException rejected = new IllegalArgumentException("downstream rejected");
        response.subscribe(value -> {
            throw rejected;
        });

        source.emit(text("text"));

        assertSame(rejected, failure(response.onCompleteFuture()));
        assertSame(rejected, failure(source.onCompleteFuture()));
        response.close();
    }

    @Test
    void 消费者结束回调异常不能被SDK正常完成结果覆盖() {
        Source source = new Source();
        OpenAiToolStreamResponse response = new OpenAiToolStreamResponse(source);
        IllegalArgumentException rejected = new IllegalArgumentException("completion rejected");
        response.subscribe(new AsyncStreamResponse.Handler<>() {
            @Override
            public void onNext(ChatCompletionChunk value) {}

            @Override
            public void onComplete(Optional<Throwable> error) {
                throw rejected;
            }
        });

        source.complete(null);

        assertSame(rejected, failure(response.onCompleteFuture()));
        assertFalse(source.onCompleteFuture().isCompletedExceptionally());
        response.close();
    }

    @Test
    void 底层关闭异常不留下永不完成的自有结果() {
        Source source = new Source();
        source.closeFailure = new IllegalStateException("close rejected");
        OpenAiToolStreamResponse response = new OpenAiToolStreamResponse(source);

        assertSame(source.closeFailure, assertThrows(IllegalStateException.class, response::close));
        assertTrue(response.onCompleteFuture().isCancelled());
        response.close();
        assertEquals(1, source.closeCalls);
    }

    @Test
    void 两条并发流只合并各自同索引工具而不共享身份或参数() throws Exception {
        Source firstSource = new Source();
        Source secondSource = new Source();
        OpenAiToolStreamResponse first = new OpenAiToolStreamResponse(firstSource);
        OpenAiToolStreamResponse second = new OpenAiToolStreamResponse(secondSource);
        Observed firstObserved = new Observed();
        Observed secondObserved = new Observed();
        first.subscribe(firstObserved);
        second.subscribe(secondObserved);
        CountDownLatch bothStarted = new CountDownLatch(2);
        // 单个SDK流保持串行回调，两个流则先同时存入index=0再继续，暴露任何共享合并状态。
        try (var workers = Executors.newFixedThreadPool(2)) {
            var firstTask = workers.submit(() -> produce(firstSource, bothStarted, "first"));
            var secondTask = workers.submit(() -> produce(secondSource, bothStarted, "second"));
            firstTask.get(5, TimeUnit.SECONDS);
            secondTask.get(5, TimeUnit.SECONDS);
        } finally {
            first.close();
            second.close();
        }

        first.onCompleteFuture().join();
        second.onCompleteFuture().join();
        assertTool(firstObserved, "first");
        assertTool(secondObserved, "second");
    }

    private static void produce(Source source, CountDownLatch bothStarted, String identity) {
        source.emit(tool("call-" + identity, identity, "{"));
        bothStarted.countDown();
        try {
            assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        source.emit(tool("call-" + identity, null, "\"source\":\"" + identity + "\"}"));
        source.emit(finished());
        source.complete(null);
    }

    private static void assertTool(Observed observed, String identity) {
        assertEquals(1, observed.tools().size());
        ToolCall call = observed.tools().getFirst();
        assertEquals("call-" + identity, call.id().orElseThrow());
        assertEquals(identity, call.function().orElseThrow().name().orElseThrow());
        assertEquals(
                "{\"source\":\"" + identity + "\"}",
                call.function().orElseThrow().arguments().orElseThrow());
    }

    private static Throwable failure(CompletableFuture<Void> future) {
        return assertThrows(CompletionException.class, future::join).getCause();
    }

    private static ChatCompletionChunk text(String value) {
        return chunk(Delta.builder().content(value).build(), false);
    }

    private static ChatCompletionChunk tool(String id, String name, String arguments) {
        ToolCall.Function.Builder function = ToolCall.Function.builder().arguments(arguments);
        if (name != null) {
            function.name(name);
        }
        ToolCall.Builder tool = ToolCall.builder().index(0).function(function.build());
        if (id != null) {
            tool.id(id);
        }
        return chunk(Delta.builder().toolCalls(List.of(tool.build())).build(), false);
    }

    private static ChatCompletionChunk finished() {
        return chunk(Delta.builder().build(), true);
    }

    private static ChatCompletionChunk chunk(Delta delta, boolean finished) {
        Choice.Builder choice = Choice.builder().index(0).delta(delta).finishReason(Optional.empty());
        if (finished) {
            choice.finishReason(Choice.FinishReason.TOOL_CALLS);
        }
        return ChatCompletionChunk.builder()
                .id("completion")
                .created(0)
                .model("fake")
                .choices(List.of(choice.build()))
                .build();
    }

    private static final class Observed implements AsyncStreamResponse.Handler<ChatCompletionChunk> {
        private final List<ChatCompletionChunk> values = new ArrayList<>();
        private Throwable failure;
        private int completions;

        @Override
        public void onNext(ChatCompletionChunk value) {
            values.add(value);
        }

        @Override
        public void onComplete(Optional<Throwable> error) {
            completions++;
            failure = error.orElse(null);
        }

        private List<ToolCall> tools() {
            return values.stream()
                    .flatMap(value -> value.choices().stream())
                    .flatMap(choice -> choice.delta().toolCalls().orElse(List.of()).stream())
                    .toList();
        }
    }

    /** 模拟SDK自身future仅认上游结果；结束处理器的校验失败必须由装饰层另外传播。 */
    private static final class Source implements AsyncStreamResponse<ChatCompletionChunk> {
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private Handler<? super ChatCompletionChunk> handler;
        private Executor executor;
        private int defaultSubscriptions;
        private int executorSubscriptions;
        private int closeCalls;
        private RuntimeException closeFailure;

        @Override
        public AsyncStreamResponse<ChatCompletionChunk> subscribe(Handler<? super ChatCompletionChunk> value) {
            handler = value;
            defaultSubscriptions++;
            return this;
        }

        @Override
        public AsyncStreamResponse<ChatCompletionChunk> subscribe(
                Handler<? super ChatCompletionChunk> value, Executor selectedExecutor) {
            handler = value;
            executor = selectedExecutor;
            executorSubscriptions++;
            return this;
        }

        @Override
        public CompletableFuture<Void> onCompleteFuture() {
            return completion;
        }

        @Override
        public void close() {
            closeCalls++;
            completion.complete(null);
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        private void emit(ChatCompletionChunk value) {
            try {
                handler.onNext(value);
            } catch (Throwable failure) {
                complete(failure);
            }
        }

        private void late(ChatCompletionChunk value) {
            handler.onNext(value);
        }

        private void complete(Throwable failure) {
            try {
                handler.onComplete(Optional.ofNullable(failure));
            } finally {
                if (failure == null) {
                    completion.complete(null);
                } else {
                    completion.completeExceptionally(failure);
                }
            }
        }
    }
}
