package com.javaclaw.agent.conversation;

import java.util.ArrayList;
import java.util.List;

import com.javaclaw.agent.model.CompactionStrategy;
import com.javaclaw.agent.model.CompactionThresholds;
import com.javaclaw.agent.model.ContextWindowExceededException;
import com.javaclaw.agent.model.ModelInvocationService;
import com.javaclaw.agent.prompt.AgentsInstructionResolution;
import com.javaclaw.agent.prompt.PromptCompiler;
import com.javaclaw.agent.prompt.PromptPurpose;
import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ModelResponse;
import com.javaclaw.core.api.ModelStreamSink;
import com.javaclaw.core.api.ProviderConversationState;
import com.javaclaw.core.api.ThreadItem;

/** 在每次采样边界选择原生或摘要压缩；压缩失败不会替换当前窗口。 */
public final class CompactionCoordinator {
    private static final int APPROXIMATE_CHARACTERS_PER_TOKEN = 4;

    private CompactionCoordinator() {}

    /** 达到 Profile 阈值时执行一次自动压缩；force 用于唯一一次 context-window 恢复，不递归调用自身。 */
    public static Result compactIfNeeded(
            TurnExecutionContext context,
            ItemSink sink,
            ModelInvocationService models,
            PromptCompiler.CompiledPrompt prepared,
            List<ModelMessage> messages,
            ProviderConversationState state,
            boolean force)
            throws Exception {
        boolean due = force
                || CompactionThresholds.autoThreshold(context.turn().config()).stream()
                        .anyMatch(threshold -> approximateTokens(messages) >= threshold);
        if (!due) {
            return new Result(messages, state, false);
        }
        if (models.compactionStrategy(context.turn().config()) == CompactionStrategy.NATIVE) {
            return force
                    ? nativeCompaction(context, sink, models, prepared, messages, state)
                    : new Result(messages, state, false);
        }
        return summaryCompaction(context, sink, models, prepared, messages);
    }

    private static Result nativeCompaction(
            TurnExecutionContext context,
            ItemSink sink,
            ModelInvocationService models,
            PromptCompiler.CompiledPrompt prepared,
            List<ModelMessage> messages,
            ProviderConversationState state)
            throws Exception {
        // 原生自动阈值由 Responses context_management 处理；这里只处理 Provider 的窗口超限恢复。
        ItemSink.ItemEmitter emitter = sink.start("contextCompaction");
        try {
            var compacted = models.compact(context, prepared, messages, state, sink);
            var replacement = ConversationWindow.Replacement.nativeState(
                    context.turn().config().model(),
                    context.thread().lastSequence(),
                    compacted.state(),
                    compacted.usage());
            emitter.completeCompaction(new ThreadItem.ContextCompaction(), replacement);
            return new Result(messages, compacted.state(), true);
        } catch (Exception failure) {
            emitter.fail("compaction_failed", "上下文压缩未完成；先前活动窗口保持有效。", false);
            throw failure;
        }
    }

    private static Result summaryCompaction(
            TurnExecutionContext context,
            ItemSink sink,
            ModelInvocationService models,
            PromptCompiler.CompiledPrompt prepared,
            List<ModelMessage> messages)
            throws Exception {
        int boundary = prepared.conversationStartIndex();
        List<ModelMessage> candidate = List.copyOf(messages.subList(boundary, messages.size()));
        ItemSink.ItemEmitter emitter = sink.start("contextCompaction");
        try {
            while (true) {
                context.throwIfInterrupted();
                ArrayList<ModelMessage> compactDialogue = new ArrayList<>(candidate);
                compactDialogue.add(new ModelMessage(ModelMessage.Role.USER, CompactionPrompts.checkpoint(), null));
                var compactPrompt = models.prepare(
                        PromptPurpose.COMPACTION,
                        context.turn().config(),
                        List.of(),
                        List.of(),
                        AgentsInstructionResolution.empty(context.thread().workingDirectory()),
                        compactDialogue);
                try {
                    ModelResponse response = models.stream(
                            context,
                            compactPrompt,
                            compactPrompt.messages(),
                            List.of(),
                            new ModelStreamSink() {},
                            sink,
                            null);
                    if (!response.toolCalls().isEmpty()) {
                        throw new IllegalArgumentException("compaction summary cannot contain tool calls");
                    }
                    String summary = response.text().strip();
                    if (summary.isEmpty()) {
                        throw new IllegalStateException("compaction returned an empty summary");
                    }
                    List<String> retained =
                            CompactionTranscript.recentUserMessages(messages.subList(boundary, messages.size()));
                    var replacement = new ConversationWindow.Replacement(
                            ConversationWindow.Strategy.SUMMARY,
                            context.turn().config().provider(),
                            context.turn().config().model(),
                            context.thread().lastSequence(),
                            1,
                            summary,
                            retained,
                            response.usage());
                    emitter.completeCompaction(new ThreadItem.ContextCompaction(), replacement);
                    ArrayList<ModelMessage> replacementMessages = new ArrayList<>(messages.subList(0, boundary));
                    retained.forEach(
                            value -> replacementMessages.add(new ModelMessage(ModelMessage.Role.USER, value, null)));
                    replacementMessages.add(new ModelMessage(
                            ModelMessage.Role.USER, CompactionPrompts.summaryPrefix() + "\n" + summary, null));
                    return new Result(replacementMessages, null, true);
                } catch (ContextWindowExceededException exceeded) {
                    if (candidate.size() <= 1) {
                        throw exceeded;
                    }
                    candidate = List.copyOf(candidate.subList(1, candidate.size()));
                }
            }
        } catch (Exception failure) {
            emitter.fail("compaction_failed", "上下文压缩未完成；先前活动窗口保持有效。", false);
            throw failure;
        }
    }

    private static long approximateTokens(List<ModelMessage> messages) {
        long characters =
                messages.stream().mapToLong(value -> value.content().length()).sum();
        return Math.max(1, Math.ceilDiv(characters, APPROXIMATE_CHARACTERS_PER_TOKEN));
    }

    /**
     * 采样边界的不可变结果。
     *
     * @param messages 压缩后待采样的完整消息；静态策略片段保持原样
     * @param state 原生窗口状态；摘要策略清空
     * @param compacted 本次是否安装了新窗口
     */
    public record Result(List<ModelMessage> messages, ProviderConversationState state, boolean compacted) {
        /** 复制消息，防止 tool loop 后续追加时反向改写已安装窗口。 */
        public Result {
            messages = List.copyOf(messages);
        }
    }
}
