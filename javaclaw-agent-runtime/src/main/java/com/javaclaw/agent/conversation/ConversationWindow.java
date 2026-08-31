package com.javaclaw.agent.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.javaclaw.core.api.ItemId;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.ProviderConversationState;
import com.javaclaw.core.api.ThreadId;

/**
 * Thread 当前模型窗口的持久快照；原始 transcript 始终保留，只有成功安装的窗口影响下一次采样。
 *
 * @param threadId 所属 Thread
 * @param number 单调递增窗口号；零表示尚未发生压缩的 Provider canonical 状态
 * @param strategy 原生 opaque 或自由文本摘要策略
 * @param provider 生成窗口的 Provider
 * @param model 生成窗口的模型
 * @param coveredSequence 该窗口已覆盖的 Thread 事件序号
 * @param schemaVersion Provider opaque 封装或摘要窗口 Schema 版本
 * @param payload 原生 canonical JSON 或摘要正文
 * @param retainedUserMessages 摘要策略保留的最近真实用户消息；原生策略为空
 * @param usage 生成本窗口所消耗的用量
 * @param compactionItemId 对应的 contextCompaction Item；零号窗口为空
 * @param createdAt 安装时间
 */
public record ConversationWindow(
        ThreadId threadId,
        long number,
        Strategy strategy,
        String provider,
        String model,
        long coveredSequence,
        int schemaVersion,
        String payload,
        List<String> retainedUserMessages,
        ModelUsage usage,
        ItemId compactionItemId,
        Instant createdAt) {
    /** 窗口压缩策略；NATIVE 载荷只能交还原 Provider，SUMMARY 可跨 Provider 作为资料使用。 */
    public enum Strategy {
        NATIVE,
        SUMMARY
    }

    /** 固定窗口内容与元数据，禁止空摘要或越界 opaque 载荷成为活动窗口。 */
    public ConversationWindow {
        threadId = Objects.requireNonNull(threadId, "threadId");
        strategy = Objects.requireNonNull(strategy, "strategy");
        provider = ThreadId.required(provider, "provider");
        model = ThreadId.required(model, "model");
        payload = Objects.requireNonNull(payload, "payload");
        retainedUserMessages = List.copyOf(Objects.requireNonNull(retainedUserMessages, "retainedUserMessages"));
        usage = usage == null ? ModelUsage.ZERO : usage;
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (number < 0 || coveredSequence < 0 || schemaVersion < 1 || payload.isBlank()) {
            throw new IllegalArgumentException("invalid conversation window");
        }
        if (strategy == Strategy.NATIVE && !retainedUserMessages.isEmpty()) {
            throw new IllegalArgumentException("native conversation windows cannot contain summary user messages");
        }
    }

    /** 将原生窗口恢复为 Provider 请求状态；模型或 Provider 不匹配时调用方必须忽略。 */
    public ProviderConversationState providerState() {
        if (strategy != Strategy.NATIVE) {
            throw new IllegalStateException("summary window has no provider state");
        }
        return new ProviderConversationState(provider, schemaVersion, payload, 0, false);
    }

    /**
     * 尚未安装的替换窗口；Item 完成与窗口安装必须由持久层在同一事务提交。
     *
     * @param strategy 压缩策略
     * @param provider 生成 Provider
     * @param model 生成模型
     * @param coveredSequence 覆盖到的事件序号
     * @param schemaVersion 载荷 Schema 版本
     * @param payload opaque JSON 或非空摘要
     * @param retainedUserMessages 最近真实用户消息
     * @param usage 压缩调用用量
     */
    public record Replacement(
            Strategy strategy,
            String provider,
            String model,
            long coveredSequence,
            int schemaVersion,
            String payload,
            List<String> retainedUserMessages,
            ModelUsage usage) {
        /** 复用活动窗口的约束，但不提前分配窗口号或 Item 标识。 */
        public Replacement {
            strategy = Objects.requireNonNull(strategy, "strategy");
            provider = ThreadId.required(provider, "provider");
            model = ThreadId.required(model, "model");
            payload = Objects.requireNonNull(payload, "payload");
            retainedUserMessages = List.copyOf(Objects.requireNonNull(retainedUserMessages, "retainedUserMessages"));
            usage = usage == null ? ModelUsage.ZERO : usage;
            if (coveredSequence < 0 || schemaVersion < 1 || payload.isBlank()) {
                throw new IllegalArgumentException("invalid conversation window replacement");
            }
            if (strategy == Strategy.NATIVE && !retainedUserMessages.isEmpty()) {
                throw new IllegalArgumentException("native replacement cannot retain summary messages");
            }
        }

        /** 从 SDK 无关的 Provider 状态创建原生替换窗口。 */
        public static Replacement nativeState(
                String model, long coveredSequence, ProviderConversationState state, ModelUsage usage) {
            Objects.requireNonNull(state, "state");
            return new Replacement(
                    Strategy.NATIVE,
                    state.provider(),
                    model,
                    coveredSequence,
                    state.schemaVersion(),
                    state.payloadJson(),
                    List.of(),
                    usage);
        }
    }
}
