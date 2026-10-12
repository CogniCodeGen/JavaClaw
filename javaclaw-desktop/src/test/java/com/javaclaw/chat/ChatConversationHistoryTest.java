package com.javaclaw.chat;

import com.javaclaw.api.conversation.ConversationMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatConversationHistoryTest {

    @Test
    void keepsOnlyUserAndSuccessfulAssistantMessagesAndStartsWithUser() {
        ChatMessage orphan = message(ChatMessage.Role.ASSISTANT, "orphan", DeliveryState.COMPLETE);
        ChatMessage user = message(ChatMessage.Role.USER, "question", DeliveryState.COMPLETE);
        ChatMessage failed = message(ChatMessage.Role.ASSISTANT, "failed", DeliveryState.FAILED);
        ChatMessage cancelled = message(
                ChatMessage.Role.ASSISTANT, "cancelled", DeliveryState.CANCELLED);
        ChatMessage answer = message(ChatMessage.Role.ASSISTANT, "answer", DeliveryState.COMPLETE);
        ChatMessage system = message(ChatMessage.Role.SYSTEM, "error", DeliveryState.COMPLETE);

        List<ConversationMessage> history = ChatConversationHistory.snapshot(
                List.of(orphan, user, failed, cancelled, answer, system));

        assertEquals(List.of(
                new ConversationMessage(ConversationMessage.Role.USER, "question"),
                new ConversationMessage(ConversationMessage.Role.ASSISTANT, "answer")), history);
    }

    @Test
    void boundsCountAndCharactersWithoutReturningAssistantOnlyHistory() {
        List<ChatMessage> messages = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            messages.add(message(ChatMessage.Role.USER, "u" + index, DeliveryState.COMPLETE));
            messages.add(message(ChatMessage.Role.ASSISTANT, "a" + index, DeliveryState.COMPLETE));
        }
        List<ConversationMessage> bounded = ChatConversationHistory.snapshot(messages);
        assertEquals(ChatConversationHistory.MAX_MESSAGES, bounded.size());
        assertEquals(ConversationMessage.Role.USER, bounded.getFirst().role());

        List<ConversationMessage> longHistory = ChatConversationHistory.snapshot(List.of(
                message(ChatMessage.Role.USER, "u", DeliveryState.COMPLETE),
                message(ChatMessage.Role.ASSISTANT,
                        "a".repeat(ChatConversationHistory.MAX_CHARACTERS),
                        DeliveryState.COMPLETE)));
        assertEquals(ConversationMessage.Role.USER, longHistory.getFirst().role());
        assertTrue(longHistory.stream().mapToInt(value -> value.content().length()).sum()
                <= ChatConversationHistory.MAX_CHARACTERS);
    }

    private static ChatMessage message(
            ChatMessage.Role role, String content, DeliveryState state) {
        ChatMessage message = new ChatMessage(role, content);
        message.setDeliveryState(state);
        return message;
    }
}
