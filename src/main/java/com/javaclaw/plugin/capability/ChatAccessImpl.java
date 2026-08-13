package com.javaclaw.plugin.capability;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationHandle;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.application.agent.AgentConversationRunner;
import com.javaclaw.application.agent.RunRequestFactory;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.plugin.CapabilityGuard;
import com.javaclaw.plugin.api.Capability;
import com.javaclaw.plugin.api.PluginException;
import com.javaclaw.plugin.api.capability.ChatAccess;
import com.javaclaw.plugin.api.capability.ChatChunkListener;
import com.javaclaw.runtime.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Plugin CHAT capability backed directly by the single AgentClient. */
public final class ChatAccessImpl implements ChatAccess {
    private static final Logger log = LoggerFactory.getLogger(ChatAccessImpl.class);
    private static final long TIMEOUT_MINUTES = 30;

    private final String pluginId;
    private final AgentConversationRunner runs;
    private final RunRequestFactory requests;
    private final Object lock = new Object();

    public ChatAccessImpl(
            String pluginId,
            AgentClient agents,
            WorkspaceContext workspace,
            Executor executor) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.runs = new AgentConversationRunner(agents, executor);
        this.requests = new RunRequestFactory(workspace);
    }

    @Override
    public String ask(String prompt) {
        CapabilityGuard.require(Capability.CHAT);
        StringBuilder reply = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();
        execute(prompt, chunk -> reply.append(chunk), error);
        Throwable failure = error.get();
        if (failure != null) {
            throw new PluginException("插件[" + pluginId + "]CHAT 调用失败："
                    + failure.getMessage(), failure);
        }
        return reply.toString();
    }

    @Override
    public void stream(String prompt, ChatChunkListener listener) {
        CapabilityGuard.require(Capability.CHAT);
        AtomicReference<Throwable> error = new AtomicReference<>();
        execute(prompt, chunk -> safe(() -> listener.onChunk(chunk)), error);
        Throwable failure = error.get();
        if (failure == null) safe(listener::onComplete);
        else safe(() -> listener.onError(failure.getMessage()));
    }

    private void execute(
            String prompt,
            java.util.function.Consumer<String> chunks,
            AtomicReference<Throwable> error) {
        synchronized (lock) {
            CountDownLatch done = new CountDownLatch(1);
            ConversationHandle handle = runs.start(requests.text(prompt, "plugin:" + pluginId, "plugin",
                            InvocationSource.plugin(pluginId),
                            // CHAT capability does not implicitly authorize host tools.
                            PermissionSet.NONE, null),
                    ToolCallOrigin.UNKNOWN, new ConversationCallbacks() {
                @Override
                public void onEvent(ConversationEvent event) {
                    if (event instanceof ConversationEvent.Reply reply) chunks.accept(reply.chunk());
                }

                @Override
                public void onTerminal(ConversationOutcome outcome) {
                    if (outcome instanceof ConversationOutcome.Failed failed) {
                        error.set(failed.error());
                    } else if (outcome instanceof ConversationOutcome.Cancelled cancelled) {
                        error.set(new java.util.concurrent.CancellationException(
                                "插件对话已取消: " + cancelled.reason()));
                    }
                    done.countDown();
                }
            });
            try {
                if (!done.await(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                    handle.cancel(com.javaclaw.api.conversation.CancellationReason.RUNTIME_REBUILD);
                    error.compareAndSet(null, new java.util.concurrent.TimeoutException(
                            "插件 CHAT 超过 " + TIMEOUT_MINUTES + " 分钟"));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                handle.cancel(com.javaclaw.api.conversation.CancellationReason.RUNTIME_REBUILD);
                error.compareAndSet(null, interrupted);
            }
        }
    }

    public void shutdown() {
        runs.close();
    }

    private void safe(Runnable callback) {
        try {
            callback.run();
        } catch (Exception failure) {
            log.warn("插件[{}]CHAT 回调异常（已隔离）：{}", pluginId, failure.toString());
        }
    }
}
