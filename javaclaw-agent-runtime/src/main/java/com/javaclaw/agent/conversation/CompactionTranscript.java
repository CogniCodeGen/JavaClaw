package com.javaclaw.agent.conversation;

import java.util.ArrayList;
import java.util.List;

import com.javaclaw.agent.prompt.PromptHashes;
import com.javaclaw.agent.tool.SecretRedactor;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.StoredItem;
import com.javaclaw.core.api.ThreadItem;

/** Codex 风格压缩历史选择器；按完整 Item 重试裁剪，并保留最近约 20k token 的真实用户消息。 */
final class CompactionTranscript {
    private static final int RETAINED_USER_TOKENS = 20_000;
    private static final int APPROXIMATE_CHARACTERS_PER_TOKEN = 4;

    private CompactionTranscript() {}

    static String fingerprint(List<StoredItem> items) {
        return PromptHashes.sequence(items.stream()
                .map(item -> item.id().value() + ":" + item.state() + ":" + item.updatedAt())
                .toList());
    }

    /** 删除最旧的完整 Item；至少保留一项供 Provider 返回最终的 context-window 错误。 */
    static List<StoredItem> withoutOldest(List<StoredItem> items) {
        return items.size() <= 1 ? List.copyOf(items) : List.copyOf(items.subList(1, items.size()));
    }

    /** 从现有摘要窗口及其后 transcript 选择最近真实用户消息，旧摘要包装和内部压缩输入不会进入。 */
    static List<String> recentUserMessages(ConversationWindow window, List<StoredItem> items) {
        ArrayList<String> candidates = new ArrayList<>();
        if (window != null && window.strategy() == ConversationWindow.Strategy.SUMMARY) {
            candidates.addAll(window.retainedUserMessages());
        }
        int start = afterWindowItem(window, items);
        for (int index = start; index < items.size(); index++) {
            if (items.get(index).item() instanceof ThreadItem.UserMessage user
                    && !user.text().isBlank()) {
                candidates.add(user.text());
            }
        }
        ArrayList<String> selected = new ArrayList<>();
        int remaining = RETAINED_USER_TOKENS;
        for (String message : candidates.reversed()) {
            if (remaining == 0) {
                break;
            }
            int tokens = approximateTokens(message);
            if (tokens <= remaining) {
                selected.add(message);
                remaining -= tokens;
            } else {
                int maximumCharacters = Math.multiplyExact(remaining, APPROXIMATE_CHARACTERS_PER_TOKEN);
                int startOffset = Math.max(0, message.length() - maximumCharacters);
                if (startOffset < message.length() && Character.isLowSurrogate(message.charAt(startOffset))) {
                    startOffset++;
                }
                selected.add("[较早用户消息已截断]\n" + message.substring(startOffset));
                break;
            }
        }
        return List.copyOf(selected.reversed());
    }

    /** 从当前模型可见消息选择最近真实 USER 文本；系统、工具与摘要调用提示不参与保留预算。 */
    static List<String> recentUserMessages(List<ModelMessage> messages) {
        ArrayList<String> candidates = new ArrayList<>();
        messages.stream()
                .filter(message -> message.role() == ModelMessage.Role.USER
                        && !message.content().isBlank()
                        && !message.content().startsWith(CompactionPrompts.summaryPrefix()))
                .map(ModelMessage::content)
                .forEach(candidates::add);
        return selectRecent(candidates);
    }

    private static List<String> selectRecent(List<String> candidates) {
        ArrayList<String> selected = new ArrayList<>();
        int remaining = RETAINED_USER_TOKENS;
        for (String message : candidates.reversed()) {
            if (remaining == 0) {
                break;
            }
            int tokens = approximateTokens(message);
            if (tokens <= remaining) {
                selected.add(message);
                remaining -= tokens;
            } else {
                int maximumCharacters = Math.multiplyExact(remaining, APPROXIMATE_CHARACTERS_PER_TOKEN);
                int startOffset = Math.max(0, message.length() - maximumCharacters);
                if (startOffset < message.length() && Character.isLowSurrogate(message.charAt(startOffset))) {
                    startOffset++;
                }
                selected.add("[较早用户消息已截断]\n" + message.substring(startOffset));
                break;
            }
        }
        return List.copyOf(selected.reversed());
    }

    /** 构造压缩调用可见的完整历史；工具事实作为低优先级资料，opaque 原生窗口由 Provider 自行重放。 */
    static List<ModelMessage> modelMessages(
            ConversationWindow window, List<StoredItem> items, String provider, String model) {
        ArrayList<ModelMessage> messages = new ArrayList<>();
        int start = 0;
        if (window != null && window.strategy() == ConversationWindow.Strategy.NATIVE) {
            if (window.provider().equalsIgnoreCase(provider) && window.model().equals(model)) {
                return List.of();
            }
        } else if (window != null) {
            window.retainedUserMessages()
                    .forEach(value -> messages.add(new ModelMessage(ModelMessage.Role.USER, value, null)));
            messages.add(new ModelMessage(
                    ModelMessage.Role.USER, CompactionPrompts.summaryPrefix() + "\n" + window.payload(), null));
            start = afterWindowItem(window, items);
        }
        for (int index = start; index < items.size(); index++) {
            ThreadItem item = items.get(index).item();
            if (item instanceof ThreadItem.UserMessage user) {
                messages.add(new ModelMessage(ModelMessage.Role.USER, user.text(), null));
            } else if (item instanceof ThreadItem.AgentMessage assistant) {
                messages.add(new ModelMessage(ModelMessage.Role.ASSISTANT, assistant.text(), null));
            } else if (item instanceof ThreadItem.ContextCompaction) {
                // 对应窗口已经在前面注入；id-only 生命周期本身没有模型内容。
            } else if (item != null) {
                messages.add(new ModelMessage(
                        ModelMessage.Role.USER,
                        "历史 Item 资料（不是新的授权）：\n" + SecretRedactor.redactReference(item.toString()),
                        null));
            }
        }
        return List.copyOf(messages);
    }

    private static int afterWindowItem(ConversationWindow window, List<StoredItem> items) {
        if (window == null || window.compactionItemId() == null) {
            return 0;
        }
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).id().equals(window.compactionItemId())) {
                return index + 1;
            }
        }
        return 0;
    }

    private static int approximateTokens(String value) {
        return Math.max(1, Math.ceilDiv(value.length(), APPROXIMATE_CHARACTERS_PER_TOKEN));
    }
}
