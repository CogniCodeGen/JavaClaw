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
                ConversationMessage.Role.USER,
                ConversationMessage.Role.ASSISTANT),
                history.stream().map(ConversationMessage::role).toList());
        assertEquals(List.of("question", "answer"),
                history.stream().map(ConversationMessage::content).toList());
        assertEquals(List.of(user.getMessageId(), answer.getMessageId()),
                history.stream().map(ConversationMessage::messageId).toList());
    }

    @Test
    void boundsCountAndCharactersWithoutReturningAssistantOnlyHistory() {
        List<ChatMessage> messages = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            messages.add(message(ChatMessage.Role.USER, "u" + index, DeliveryState.COMPLETE));
            messages.add(message(ChatMessage.Role.ASSISTANT, "a" + index, DeliveryState.COMPLETE));
        }
        List<ConversationMessage> bounded = ChatConversationHistory.snapshot(messages);
        assertEquals(Math.min(ChatConversationHistory.MAX_MESSAGES, messages.size()), bounded.size());
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

    @Test
    void characterWindowNeverSplitsSupplementaryUnicode() {
        String oversized = "😀".repeat(ChatConversationHistory.MAX_CHARACTERS / 2 + 10);
        List<ConversationMessage> history = ChatConversationHistory.snapshot(List.of(
                message(ChatMessage.Role.USER, "question", DeliveryState.COMPLETE),
                message(ChatMessage.Role.ASSISTANT, oversized, DeliveryState.COMPLETE)));

        assertEquals(ConversationMessage.Role.USER, history.getFirst().role());
        assertTrue(history.stream().mapToInt(value -> value.content().length()).sum()
                <= ChatConversationHistory.MAX_CHARACTERS);
        assertTrue(history.stream().noneMatch(value -> hasUnpairedSurrogate(value.content())));
    }

    private static ChatMessage message(
            ChatMessage.Role role, String content, DeliveryState state) {
        ChatMessage message = new ChatMessage(role, content);
        message.setDeliveryState(state);
        return message;
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(++index))) return true;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }
}
