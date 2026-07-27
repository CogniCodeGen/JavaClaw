package com.javaclaw.workflow.service;

import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.workflow.model.StatePatch;
import com.javaclaw.workflow.runtime.NodeExecutionContext;
import com.javaclaw.workflow.runtime.NodeResult;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 把现有异步 ConversationCallbacks 管线适配为一个阻塞的系统图节点。 */
public final class SystemPipelineAwaiter {
    private SystemPipelineAwaiter() {}

    public static NodeResult await(NodeExecutionContext context,
                                   Consumer<ConversationCallbacks> starter,
                                   ConversationCallbacks outer,
                                   Runnable cancelAction) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StringBuilder output = new StringBuilder();
        ConversationCallbacks inner = new ConversationCallbacks() {
            @Override public void onEvent(ConversationEvent event) {
                if (event instanceof ConversationEvent.Reply r) output.append(r.chunk());
                else if (event instanceof ConversationEvent.AgentReply r) output.append(r.chunk());
                outer.onEvent(event);
            }
            @Override public void onTerminal(ConversationOutcome outcome) {
                if (outcome instanceof ConversationOutcome.Failed failed) {
                    failure.set(failed.error());
                } else if (outcome instanceof ConversationOutcome.Cancelled cancelled) {
                    failure.set(new java.util.concurrent.CancellationException(
                            "系统管线已取消: " + cancelled.reason()));
                }
                done.countDown();
            }
        };
        // 先拒绝已经取消的运行，避免无意义地启动底层管线；真正启动后再注册钩子。
        // CancellationToken.onCancel 对已经取消的令牌会立即执行 hook，因此即使取消恰好
        // 发生在 starter.accept 与注册之间，也会命中此时已经建立好的真实订阅。
        context.cancellation().throwIfCancelled();
        starter.accept(inner);
        try (AutoCloseable ignored = context.cancellation().onCancel(cancelAction)) {
            context.cancellation().throwIfCancelled();
            while (!done.await(200, TimeUnit.MILLISECONDS)) context.cancellation().throwIfCancelled();
        }
        context.cancellation().throwIfCancelled();
        if (failure.get() != null) {
            if (failure.get() instanceof Exception e) throw e;
            throw new IllegalStateException("系统管线失败", failure.get());
        }
        return NodeResult.output(StatePatch.builder().set("output", output.toString()).build(), output.toString());
    }
}
