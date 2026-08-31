package com.javaclaw.agent.conversation;

import java.util.ArrayList;
import java.util.List;

import com.javaclaw.agent.model.CompactionStrategy;
import com.javaclaw.agent.model.ContextWindowExceededException;
import com.javaclaw.agent.model.ModelInvocationService;
import com.javaclaw.agent.prompt.AgentsInstructionResolution;
import com.javaclaw.agent.prompt.PromptPurpose;
import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ModelResponse;
import com.javaclaw.core.api.ThreadItem;

/** 使用同一 TurnScope 执行原生或 Codex 风格摘要压缩；只有完整成功后才原子替换活动窗口。 */
public final class CompactionExecution {
    private CompactionExecution() {}

    /** 禁止工具、结构化修复和递归压缩；取消、空摘要及 Provider 失败均保留旧窗口。 */
    public static void execute(TurnExecutionContext context, ItemSink sink, ModelInvocationService models)
            throws Exception {
        var attributes = context.turn().config().attributes();
        if (!CompactionTranscript.fingerprint(context.priorItems()).equals(attributes.get("compactionSourceHash"))) {
            throw new IllegalStateException("history changed before compaction; the previous window was preserved");
        }
        long coveredSequence = Long.parseLong(attributes.get("compactionBaseSequence"));
        ItemSink.ItemEmitter emitter = sink.start("contextCompaction");
        try {
            ConversationWindow.Replacement replacement =
                    models.compactionStrategy(context.turn().config()) == CompactionStrategy.NATIVE
                            ? nativeReplacement(context, sink, models, coveredSequence)
                            : summaryReplacement(context, sink, models, coveredSequence);
            context.throwIfInterrupted();
            emitter.completeCompaction(new ThreadItem.ContextCompaction(), replacement);
        } catch (Exception failure) {
            emitter.fail("compaction_failed", "上下文压缩未完成；原始 transcript 与先前活动窗口保持有效。", false);
            throw failure;
        }
    }

    private static ConversationWindow.Replacement nativeReplacement(
            TurnExecutionContext context, ItemSink sink, ModelInvocationService models, long coveredSequence)
            throws Exception {
        List<ModelMessage> dialogue = CompactionTranscript.modelMessages(
                context.conversationWindow(),
                context.priorItems(),
                context.turn().config().provider(),
                context.turn().config().model());
        var prepared = models.prepare(
                PromptPurpose.COMPACTION,
                context.turn().config(),
                List.of(),
                List.of(),
                AgentsInstructionResolution.empty(context.thread().workingDirectory()),
                dialogue);
        var result = models.compact(context, prepared, prepared.messages(), context.conversationState(), sink);
        return ConversationWindow.Replacement.nativeState(
                context.turn().config().model(), coveredSequence, result.state(), result.usage());
    }

    private static ConversationWindow.Replacement summaryReplacement(
            TurnExecutionContext context, ItemSink sink, ModelInvocationService models, long coveredSequence)
            throws Exception {
        List<com.javaclaw.core.api.StoredItem> candidate = context.priorItems();
        while (true) {
            context.throwIfInterrupted();
            ArrayList<ModelMessage> dialogue = new ArrayList<>(CompactionTranscript.modelMessages(
                    context.conversationWindow(),
                    candidate,
                    context.turn().config().provider(),
                    context.turn().config().model()));
            dialogue.add(new ModelMessage(ModelMessage.Role.USER, CompactionPrompts.checkpoint(), null));
            var prepared = models.prepare(
                    PromptPurpose.COMPACTION,
                    context.turn().config(),
                    List.of(),
                    List.of(),
                    AgentsInstructionResolution.empty(context.thread().workingDirectory()),
                    dialogue);
            try {
                ModelResponse response = models.stream(
                        context,
                        prepared,
                        prepared.messages(),
                        List.of(),
                        new com.javaclaw.core.api.ModelStreamSink() {},
                        sink,
                        null);
                if (!response.toolCalls().isEmpty()) {
                    throw new IllegalArgumentException("compaction summary cannot contain tool calls");
                }
                String summary = response.text().strip();
                if (summary.isEmpty()) {
                    throw new IllegalStateException("compaction returned an empty summary");
                }
                return new ConversationWindow.Replacement(
                        ConversationWindow.Strategy.SUMMARY,
                        context.turn().config().provider(),
                        context.turn().config().model(),
                        coveredSequence,
                        1,
                        summary,
                        CompactionTranscript.recentUserMessages(context.conversationWindow(), context.priorItems()),
                        response.usage());
            } catch (ContextWindowExceededException exceeded) {
                if (candidate.size() <= 1) {
                    throw exceeded;
                }
                candidate = CompactionTranscript.withoutOldest(candidate);
            }
        }
    }
}
