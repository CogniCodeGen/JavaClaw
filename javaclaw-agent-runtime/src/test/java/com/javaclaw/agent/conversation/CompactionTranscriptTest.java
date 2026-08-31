package com.javaclaw.agent.conversation;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.javaclaw.core.api.ItemId;
import com.javaclaw.core.api.ItemState;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.StoredItem;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.TurnId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionTranscriptTest {
    @Test
    void removesTheOldestWholeItemAndPreservesUnicode() {
        StoredItem first = item("first", 1, new ThreadItem.UserMessage("资料😀"));
        StoredItem second = item("second", 2, new ThreadItem.AgentMessage("回答"));
        assertEquals(List.of(second), CompactionTranscript.withoutOldest(List.of(first, second)));
        assertEquals(List.of(first), CompactionTranscript.withoutOldest(List.of(first)));
        assertTrue(CompactionTranscript.modelMessages(null, List.of(first), "fake", "model")
                .getFirst()
                .content()
                .contains("😀"));
    }

    @Test
    void summaryWindowIsReinjectedAndOnlyItemsAfterItsIdRemainRaw() {
        StoredItem old = item("old", 1, new ThreadItem.UserMessage("旧原文"));
        StoredItem checkpoint = item("checkpoint", 2, new ThreadItem.ContextCompaction());
        StoredItem recent = item("recent", 3, new ThreadItem.UserMessage("最近问题"));
        ConversationWindow window = new ConversationWindow(
                new ThreadId("thread"),
                1,
                ConversationWindow.Strategy.SUMMARY,
                "fake",
                "model",
                3,
                1,
                "已完成旧工作",
                List.of("保留的用户要求"),
                ModelUsage.ZERO,
                checkpoint.id(),
                Instant.EPOCH);

        List<ModelMessage> messages =
                CompactionTranscript.modelMessages(window, List.of(old, checkpoint, recent), "fake", "model");
        assertEquals(
                List.of("保留的用户要求", CompactionPrompts.summaryPrefix() + "\n已完成旧工作", "最近问题"),
                messages.stream().map(ModelMessage::content).toList());
        assertFalse(messages.toString().contains("旧原文"));
    }

    @Test
    void recentUserRetentionIsBoundedAndFingerprintTracksTheTranscript() {
        String large = "甲".repeat(100_000);
        List<String> retained = CompactionTranscript.recentUserMessages(List.of(
                new ModelMessage(ModelMessage.Role.USER, large, null),
                new ModelMessage(ModelMessage.Role.ASSISTANT, "忽略", null),
                new ModelMessage(ModelMessage.Role.USER, CompactionPrompts.summaryPrefix() + "\n旧摘要不得冒充真实用户消息", null),
                new ModelMessage(ModelMessage.Role.USER, "最后要求", null)));
        assertEquals("最后要求", retained.getLast());
        assertFalse(retained.stream().anyMatch(value -> value.contains("旧摘要不得冒充真实用户消息")));
        assertTrue(retained.stream().mapToInt(String::length).sum() <= 80_020);
        StoredItem original = item("original", 1, new ThreadItem.UserMessage("原文"));
        StoredItem changed = item("changed", 1, new ThreadItem.UserMessage("原文"));
        assertNotEquals(
                CompactionTranscript.fingerprint(List.of(original)),
                CompactionTranscript.fingerprint(List.of(changed)));
    }

    private static StoredItem item(String id, long ordinal, ThreadItem value) {
        return new StoredItem(
                new ItemId(id),
                new ThreadId("thread"),
                new TurnId("turn"),
                ordinal,
                ItemState.COMPLETED,
                value,
                Instant.EPOCH,
                Instant.EPOCH);
    }
}
