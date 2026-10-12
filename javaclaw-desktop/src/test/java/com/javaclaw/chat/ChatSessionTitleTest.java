package com.javaclaw.chat;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSessionTitleTest {

    @Test
    void firstUserMessageNamesAnUntitledSessionOnce() {
        ChatSession session = ChatSession.untitled();
        session.getMessages().add(new ChatMessage(ChatMessage.Role.SYSTEM, "提示"));
        session.getMessages().add(new ChatMessage(ChatMessage.Role.USER,
                "  解释虚拟线程的调度方式并给出示例  "));

        assertTrue(session.autoTitle());
        assertEquals("解释虚拟线程的调度方式并给出示例", session.getTitle());
        session.getMessages().add(new ChatMessage(ChatMessage.Role.USER, "后续问题"));
        assertFalse(session.autoTitle());
        assertEquals("解释虚拟线程的调度方式并给出示例", session.getTitle());
    }

    @Test
    void existingTitleSurvivesLaterMessagesAndEmptyUserMessage() {
        ChatSession named = new ChatSession("branch", "已有分支标题",
                LocalDateTime.now(), List.of(new ChatMessage(ChatMessage.Role.USER, "新内容")));
        assertFalse(named.autoTitle());
        assertEquals("已有分支标题", named.getTitle());

        ChatSession empty = ChatSession.untitled();
        empty.getMessages().add(new ChatMessage(ChatMessage.Role.USER, "  "));
        assertFalse(empty.autoTitle());
        assertEquals("新的对话", empty.getTitle());
    }

    @Test
    void longFirstMessageIsTruncated() {
        ChatSession session = ChatSession.untitled();
        session.getMessages().add(new ChatMessage(ChatMessage.Role.USER, "一".repeat(21)));
        assertTrue(session.autoTitle());
        assertEquals("一".repeat(20) + "...", session.getTitle());
    }

    @Test
    void aUserTitleEqualToTheDefaultDisplayTextIsStillExplicit() {
        ChatSession session = new ChatSession("新的对话");
        session.getMessages().add(new ChatMessage(ChatMessage.Role.USER, "真实首条消息"));
        assertFalse(session.autoTitle());
        assertEquals("新的对话", session.getTitle());
    }

    @Test
    void emptySessionRecoversItsPendingTitlePolicyFromSavedSnapshot() {
        LocalDateTime created = LocalDateTime.now();
        ChatSession pending = new ChatSession("pending", "新的对话", created, List.of(), true);
        ChatSession explicit = new ChatSession("explicit", "新的对话", created, List.of(), false);

        ChatSession restoredPending = ChatSessionCoordinator.recoverDurableSession(
                "pending", "新的对话", created, List.of(pending, explicit));
        ChatSession restoredExplicit = ChatSessionCoordinator.recoverDurableSession(
                "explicit", "新的对话", created, List.of(pending, explicit));
        restoredPending.getMessages().add(new ChatMessage(ChatMessage.Role.USER, "重启后的首条消息"));
        restoredExplicit.getMessages().add(new ChatMessage(ChatMessage.Role.USER, "不应覆盖标题"));

        assertTrue(restoredPending.autoTitle());
        assertEquals("重启后的首条消息", restoredPending.getTitle());
        assertFalse(restoredExplicit.autoTitle());
        assertEquals("新的对话", restoredExplicit.getTitle());
    }
}
