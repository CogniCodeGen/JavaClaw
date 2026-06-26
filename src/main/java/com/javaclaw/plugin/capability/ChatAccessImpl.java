package com.javaclaw.plugin.capability;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ScheduledTaskAgent;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
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
 * <p>每个插件独占一个本实例与一个 {@link ScheduledTaskAgent}（懒建：仅当插件确实调用 CHAT 时才创建，
 * 避免未用 CHAT 的插件白白构建昂贵编排器）。{@code ScheduledTaskAgent.run} 复用单一 toolkit/子智能体、
 * 阻塞且需串行，故 {@link #ask}/{@link #stream} 经实例锁串行化——插件从多个后台虚拟线程并发发起对话时
 * 自动排队，互不踩踏。</p>
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
            agent().run(prompt, new ConversationCallbacks() {
                @Override
                public void onEvent(ConversationEvent event) {
                    if (event instanceof ConversationEvent.Reply r) {
                        reply.append(r.chunk());
                    }
                }

                @Override
                public void onComplete() {
                    // run() 内部已阻塞至此，无需额外同步
                }

                @Override
                public void onError(Throwable t) {
                    error.set(t);
                }
            });
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
            agent().run(prompt, new ConversationCallbacks() {
                @Override
                public void onEvent(ConversationEvent event) {
                    if (event instanceof ConversationEvent.Reply r) {
                        safe(() -> listener.onChunk(r.chunk()));
                    }
                }

                @Override
                public void onComplete() {
                    safe(listener::onComplete);
                }

                @Override
                public void onError(Throwable t) {
                    safe(() -> listener.onError(t.getMessage()));
                }
            });
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
