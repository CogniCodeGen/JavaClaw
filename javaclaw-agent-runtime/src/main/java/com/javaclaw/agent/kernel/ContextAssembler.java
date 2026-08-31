package com.javaclaw.agent.kernel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.javaclaw.agent.context.ContextContributor;
import com.javaclaw.agent.conversation.CompactionPrompts;
import com.javaclaw.agent.conversation.ConversationWindow;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.StoredItem;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.TurnInput;

/** Deterministic transcript-to-model projection; it never reads a second history store. */
public final class ContextAssembler {
    private final List<ContextContributor> contributors;
    private final com.javaclaw.agent.context.AttachmentInputResolver attachments;

    /** 创建只读取统一 transcript 的上下文组装器，不访问额外知识来源。 */
    public ContextAssembler() {
        this(List.of());
    }

    /** 复制上下文贡献者列表；每次组装按该顺序读取并记录实际使用版本。 */
    public ContextAssembler(List<ContextContributor> contributors) {
        this(contributors, com.javaclaw.agent.context.AttachmentInputResolver.UNAVAILABLE);
    }

    /** 装配真实附件解析边界；复杂文档 Worker 不可用时明确失败，不用名称或哈希冒充内容。 */
    public ContextAssembler(
            List<ContextContributor> contributors, com.javaclaw.agent.context.AttachmentInputResolver attachments) {
        this.contributors = List.copyOf(Objects.requireNonNull(contributors, "contributors"));
        this.attachments = Objects.requireNonNull(attachments, "attachments");
    }

    /**
     * 从 transcript、Profile 提示词及贡献者组装上下文，并返回使用清单供审计。
     *
     * @throws Exception 任一上下文贡献者读取失败
     */
    public Assembly assemble(TurnExecutionContext context) throws Exception {
        return assemble(context, true);
    }

    /**
     * 按当前 Provider 能力组装上下文；环境切换后不再支持原生载荷时，忽略旧 opaque 窗口并重放 transcript。
     *
     * @param context 当前 Turn 的不可变执行快照
     * @param useNativeWindow 当前 Provider 环境是否明确支持已保存的原生窗口
     * @return 模型消息及实际采用的上下文来源
     * @throws Exception 任一上下文贡献者读取失败
     */
    public Assembly assemble(TurnExecutionContext context, boolean useNativeWindow) throws Exception {
        ConversationWindow window = context.conversationWindow();
        if (!useNativeWindow && window != null && window.strategy() == ConversationWindow.Strategy.NATIVE) {
            window = null;
        }
        List<ModelMessage> result = new ArrayList<>(assemble(
                context.priorItems(),
                context.turn().input(),
                window,
                context.turn().config().provider(),
                context.turn().config().model()));
        List<ContextContributor.ContextContribution> used = new ArrayList<>();
        ContextContributor.ContextRequest request =
                new ContextContributor.ContextRequest(context.thread(), context.turn(), context.priorItems());
        for (ContextContributor contributor : contributors) {
            List<ContextContributor.ContextContribution> values = contributor.contribute(request);
            if (values == null) {
                continue;
            }
            for (ContextContributor.ContextContribution value : values) {
                used.add(Objects.requireNonNull(value, "context contribution"));
            }
        }
        return new Assembly(result, used);
    }

    /** 从完整 transcript 投影用户/助手消息，再追加本轮输入；不会删除原始历史。 */
    public List<ModelMessage> assemble(List<StoredItem> transcript, List<TurnInput> currentInput) {
        return assemble(transcript, currentInput, null, "", "");
    }

    /** 按活动 ConversationWindow 投影模型可见历史；摘要窗口保留最近用户消息，原生窗口由 Provider payload 承载。 */
    public List<ModelMessage> assemble(
            List<StoredItem> transcript,
            List<TurnInput> currentInput,
            ConversationWindow window,
            String provider,
            String model) {
        List<ModelMessage> result = new ArrayList<>();
        int start = 0;
        if (window != null && window.strategy() == ConversationWindow.Strategy.NATIVE) {
            if (window.provider().equalsIgnoreCase(provider) && window.model().equals(model)) {
                start = transcript.size();
            }
        } else if (window != null) {
            for (String retained : window.retainedUserMessages()) {
                result.add(new ModelMessage(ModelMessage.Role.USER, retained, null));
            }
            result.add(new ModelMessage(
                    ModelMessage.Role.USER, CompactionPrompts.summaryPrefix() + "\n" + window.payload(), null));
            start = afterCompactionItem(transcript, window);
        }
        for (int index = start; index < transcript.size(); index++) {
            project(transcript.get(index).item(), result);
            checkImageLimits(result);
        }
        for (TurnInput input : currentInput) {
            if (input instanceof TurnInput.Text text) {
                result.add(new ModelMessage(ModelMessage.Role.USER, text.text(), null));
            } else if (input instanceof TurnInput.AttachmentRef attachment) {
                result.add(attachments.resolve(attachment));
                checkImageLimits(result);
            }
        }
        checkImageLimits(result);
        return List.copyOf(result);
    }

    private static void checkImageLimits(List<ModelMessage> result) {
        long imageCount =
                result.stream().mapToLong(message -> message.images().size()).sum();
        long bytes = result.stream()
                .flatMap(message -> message.images().stream())
                .mapToLong(com.javaclaw.core.api.ModelImage::sizeBytes)
                .sum();
        if (imageCount > 20 || bytes > 32L * 1024 * 1024) {
            throw new IllegalArgumentException("Turn image inputs exceed page or memory limits");
        }
    }

    private static int afterCompactionItem(List<StoredItem> items, ConversationWindow window) {
        if (window.compactionItemId() == null) {
            return items.size();
        }
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).id().equals(window.compactionItemId())) {
                return index + 1;
            }
        }
        throw new IllegalStateException("active conversation window does not match the transcript");
    }

    private void project(ThreadItem item, List<ModelMessage> target) {
        if (item instanceof ThreadItem.UserMessage message) {
            target.add(new ModelMessage(ModelMessage.Role.USER, message.text(), null));
            for (var reference : message.attachments()) {
                target.add(attachments.resolve(reference));
                checkImageLimits(target);
            }
        } else if (item instanceof ThreadItem.AgentMessage message) {
            target.add(new ModelMessage(ModelMessage.Role.ASSISTANT, message.text(), null));
        }
    }

    /**
     * 模型消息与来源使用清单的同批快照，供模型调用和 ContextUsage 审计共用。
     *
     * @param messages 有序的非空模型消息列表，构造时复制
     * @param contributions 本次实际采用的非空来源清单，构造时复制
     */
    public record Assembly(List<ModelMessage> messages, List<ContextContributor.ContextContribution> contributions) {
        /** 同时复制消息与使用清单，避免上下文与审计在后续处理时发生漂移。 */
        public Assembly {
            messages = List.copyOf(messages);
            contributions = List.copyOf(contributions);
        }
    }
}
