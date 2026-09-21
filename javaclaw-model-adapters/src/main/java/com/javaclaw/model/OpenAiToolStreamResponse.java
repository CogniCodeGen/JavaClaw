package com.javaclaw.model;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import com.openai.core.http.AsyncStreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;

/**
 * 单个 SDK 流的工具合并边界；回调继续使用 SDK 的执行器，不创建线程或额外请求。
 *
 * <p>自有完成 future 确保 EOF 校验失败传回 Spring AI，不能被 SDK 正常结束的 future 覆盖。 显式关闭只关闭本次响应并取消结果，不把尚未完成的工具变成可执行调用。
 */
final class OpenAiToolStreamResponse implements AsyncStreamResponse<ChatCompletionChunk> {
    private final AsyncStreamResponse<?> source;
    private final OpenAiToolChunkNormalizer chunks = new OpenAiToolChunkNormalizer();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final AtomicBoolean subscribed = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    OpenAiToolStreamResponse(AsyncStreamResponse<?> source) {
        this.source = Objects.requireNonNull(source, "source");
        source.onCompleteFuture().whenComplete((ignored, failure) -> {
            if (failure != null) {
                completion.completeExceptionally(failure);
            }
        });
    }

    @Override
    public AsyncStreamResponse<ChatCompletionChunk> subscribe(Handler<? super ChatCompletionChunk> handler) {
        source.subscribe(observe(handler));
        return this;
    }

    @Override
    public AsyncStreamResponse<ChatCompletionChunk> subscribe(
            Handler<? super ChatCompletionChunk> handler, Executor executor) {
        Objects.requireNonNull(executor, "executor");
        source.subscribe(observe(handler), executor);
        return this;
    }

    private Handler<Object> observe(Handler<? super ChatCompletionChunk> handler) {
        Objects.requireNonNull(handler, "handler");
        if (finished.get() || !subscribed.compareAndSet(false, true)) {
            throw new IllegalStateException("模型响应只能订阅一次，且不能在关闭后订阅");
        }
        return new Handler<>() {
            @Override
            public void onNext(Object value) {
                if (!finished.get()) {
                    if (!(value instanceof ChatCompletionChunk chunk)) {
                        throw new IllegalStateException("模型返回了不支持的流数据类型");
                    }
                    handler.onNext(chunks.accept(chunk));
                }
            }

            @Override
            public void onComplete(Optional<Throwable> error) {
                finish(handler, error.orElse(null));
            }
        };
    }

    private void finish(Handler<? super ChatCompletionChunk> handler, Throwable failure) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        Throwable result = failure;
        try {
            if (result == null) {
                chunks.finish();
            }
        } catch (RuntimeException invalid) {
            result = invalid;
        }
        try {
            handler.onComplete(Optional.ofNullable(result));
        } catch (RuntimeException rejected) {
            result = rejected;
        } finally {
            if (result == null) {
                completion.complete(null);
            } else {
                completion.completeExceptionally(result);
            }
        }
    }

    @Override
    public CompletableFuture<Void> onCompleteFuture() {
        return completion;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            finished.set(true);
            try {
                source.close();
            } finally {
                completion.cancel(false);
            }
        }
    }
}
