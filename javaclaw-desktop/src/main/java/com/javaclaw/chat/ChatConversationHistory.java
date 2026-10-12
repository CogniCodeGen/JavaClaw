package com.javaclaw.chat;

import com.javaclaw.api.conversation.ConversationMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Produces the bounded, successful user/assistant history supplied to a new conversation Run. */
final class ChatConversationHistory {
    static final int MAX_MESSAGES = 40;
    static final int MAX_CHARACTERS = 48_000;

    private ChatConversationHistory() { }

    static List<ConversationMessage> snapshot(ChatSession session) {
        if (session == null) return List.of();
        return snapshot(session.getMessages());
    }

    static List<ConversationMessage> snapshot(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        List<ConversationMessage> selected = new ArrayList<>();
        int characters = 0;
        for (int index = messages.size() - 1;
             index >= 0 && selected.size() < MAX_MESSAGES;
             index--) {
            ConversationMessage message = eligible(messages.get(index));
            if (message == null) continue;
            int remaining = MAX_CHARACTERS - characters;
            if (remaining <= 0) {
                if (message.role() != ConversationMessage.Role.USER) break;
                if (selected.isEmpty()) return List.of();
                ConversationMessage newest = selected.getFirst();
                if (newest.content().isEmpty()) return List.of();
                selected.set(0, new ConversationMessage(
                        newest.role(), newest.content().substring(1)));
                characters--;
                remaining = 1;
            }
            String content = message.content();
            if (content.length() > remaining) {
                if (!selected.isEmpty()
                        && message.role() != ConversationMessage.Role.USER) break;
                int reservedForUser = selected.isEmpty()
                        && message.role() == ConversationMessage.Role.ASSISTANT ? 1 : 0;
                int retained = remaining - reservedForUser;
                if (retained <= 0) continue;
                content = content.substring(content.length() - retained);
            }
            selected.add(new ConversationMessage(message.role(), content));
            characters += content.length();
        }
        Collections.reverse(selected);
        while (!selected.isEmpty()
                && selected.getFirst().role() != ConversationMessage.Role.USER) {
            selected.removeFirst();
        }
        return List.copyOf(selected);
    }

    private static ConversationMessage eligible(ChatMessage message) {
        if (message == null) return null;
        ConversationMessage.Role role;
        if (message.getRole() == ChatMessage.Role.USER) {
            role = ConversationMessage.Role.USER;
        } else if (message.getRole() == ChatMessage.Role.ASSISTANT
                && (message.getDeliveryState() == null
                || message.getDeliveryState() == DeliveryState.COMPLETE)) {
            role = ConversationMessage.Role.ASSISTANT;
        } else {
            return null;
        }
        String content = message.getContent() == null ? "" : message.getContent();
        return content.isBlank() ? null : new ConversationMessage(role, content);
    }
}
