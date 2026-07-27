package com.javaclaw.plugin.capability;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ScheduledTaskAgent;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.api.conversation.TerminalCallbackGuard;
import com.javaclaw.plugin.CapabilityGuard;
import com.javaclaw.plugin.api.Capability;
import com.javaclaw.plugin.api.PluginException;
import com.javaclaw.plugin.api.capability.ChatAccess;
import com.javaclaw.plugin.api.capability.ChatChunkListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * CHAT 能力实现 —— 背靠 {@link ScheduledTaskAgent}（与交互聊天<b>完全隔离</b>的非交互编排器，
 * 每轮独立上下文与记忆），为插件提供一轮 AI 对话。
 *
 * <p>每个插件独占一个本实例与一个 {@link ScheduledTaskAgent}（懒建：仅当插件确实调用 CHAT 时才创建）。
 * {@code ScheduledTaskAgent.run} 阻塞且需串行，故 {@link #ask}/{@link #stream} 经实例锁串行化——插件
 * 从多个后台虚拟线程并发发起对话时自动排队，互不踩踏。来源令牌按 {@code plugin:<id>} 逐 run 绑定：
 * 该 id 永不出现在定时任务授权窗里，插件对话的高风险工具确认永远走逐次人工（保守语义）。</p>
 *
 * @author JavaClaw
 */
public final class ChatAccessImpl implements ChatAccess {

    private static final Logger log = LoggerFactory.getLogger(ChatAccessImpl.class);

    private final String pluginId;
    private final AgentRuntime runtime;
    private final Object lock = new Object();

    /** 懒建的隔离编排器（首次调用 CHAT 时创建） */
    private volatile ScheduledTaskAgent agent;

    public ChatAccessImpl(String pluginId, AgentRuntime runtime) {
        this.pluginId = pluginId;
        this.runtime = runtime;
    }

    @Override
    public String ask(String prompt) {
        CapabilityGuard.require(Capability.CHAT);
        StringBuilder reply = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();

        synchronized (lock) {
            log.debug("插件[{}]发起同步对话，prompt 长度={}", pluginId, prompt == null ? 0 : prompt.length());
            // 插件路径不开授权窗：逐次构造的令牌只承载归属标识，永远走常规确认
            agent().run(com.javaclaw.agent.ToolCallOrigin.scheduled("plugin:" + pluginId),
                    prompt, new TerminalCallbackGuard(new ConversationCallbacks() {
                @Override
                public void onEvent(ConversationEvent event) {
                    if (event instanceof ConversationEvent.Reply r) {
                        reply.append(r.chunk());
                    }
                }

                @Override
                public void onTerminal(ConversationOutcome outcome) {
                    if (outcome instanceof ConversationOutcome.Failed failed) {
                        error.set(failed.error());
                    } else if (outcome instanceof ConversationOutcome.Cancelled cancelled) {
                        error.set(new java.util.concurrent.CancellationException(
                                "插件对话已取消: " + cancelled.reason()));
                    }
                }
            }));
        }

        Throwable t = error.get();
        if (t != null) {
            log.warn("插件[{}]同步对话失败：{}", pluginId, t.toString());
            throw new PluginException("插件[" + pluginId + "]CHAT 调用失败：" + t.getMessage(), t);
        }
        return reply.toString();
    }

    @Override
    public void stream(String prompt, ChatChunkListener listener) {
        CapabilityGuard.require(Capability.CHAT);
        synchronized (lock) {
            log.debug("插件[{}]发起流式对话", pluginId);
            agent().run(com.javaclaw.agent.ToolCallOrigin.scheduled("plugin:" + pluginId),
                    prompt, new TerminalCallbackGuard(new ConversationCallbacks() {
                @Override
                public void onEvent(ConversationEvent event) {
                    if (event instanceof ConversationEvent.Reply r) {
                        safe(() -> listener.onChunk(r.chunk()));
                    }
                }

                @Override
                public void onTerminal(ConversationOutcome outcome) {
                    if (outcome instanceof ConversationOutcome.Completed) {
                        safe(listener::onComplete);
                    } else if (outcome instanceof ConversationOutcome.Failed failed) {
                        safe(() -> listener.onError(failed.error().getMessage()));
                    } else if (outcome instanceof ConversationOutcome.Cancelled cancelled) {
                        safe(() -> listener.onError("已取消: " + cancelled.reason()));
                    }
                }
            }));
        }
    }

    /** 释放编排器资源（插件停用时调用）。 */
    public void shutdown() {
        ScheduledTaskAgent a = agent;
        if (a != null) {
            a.shutdown();
        }
    }

    /** 懒建隔离编排器（双重检查锁）。 */
    private ScheduledTaskAgent agent() {
        ScheduledTaskAgent a = agent;
        if (a == null) {
            synchronized (lock) {
                a = agent;
                if (a == null) {
                    a = new ScheduledTaskAgent(runtime);
                    agent = a;
                    log.info("插件[{}]CHAT 编排器已创建（隔离 ScheduledTaskAgent）", pluginId);
                }
            }
        }
        return a;
    }

    /** 包裹插件回调，回调内异常不得影响对话流程。 */
    private void safe(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.warn("插件[{}]CHAT 回调抛异常（已忽略）：{}", pluginId, e.toString());
        }
    }
}
