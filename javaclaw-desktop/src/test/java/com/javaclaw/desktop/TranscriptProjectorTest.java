package com.javaclaw.desktop;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.javaclaw.sdk.model.CommandItemContent;
import com.javaclaw.sdk.model.ErrorItemContent;
import com.javaclaw.sdk.model.ItemContent;
import com.javaclaw.sdk.model.ItemInfo;
import com.javaclaw.sdk.model.JsonDocument;
import com.javaclaw.sdk.model.StructuredItemContent;
import com.javaclaw.sdk.model.TextItemContent;
import com.javaclaw.sdk.model.UserMessageItemContent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptProjectorTest {
    @Test
    void lifecycleAndUsageItemsCollapseIntoOneExecutionBlockPerTurn() {
        var blocks = TranscriptProjector.project(List.of(
                item(
                        "user",
                        "turn",
                        1,
                        "COMPLETED",
                        new UserMessageItemContent("请检查", List.of(), JsonDocument.EMPTY_OBJECT)),
                item("started", "turn", 2, "STARTED", null),
                item(
                        "command",
                        "turn",
                        3,
                        "COMPLETED",
                        new CommandItemContent(
                                List.of("mvn", "test"), 0, "通过", "", false, false, JsonDocument.EMPTY_OBJECT)),
                item(
                        "usage-1",
                        "turn",
                        4,
                        "COMPLETED",
                        new StructuredItemContent("contextUsage", JsonDocument.EMPTY_OBJECT)),
                item(
                        "usage-2",
                        "turn",
                        5,
                        "COMPLETED",
                        new StructuredItemContent("contextUsage", JsonDocument.EMPTY_OBJECT)),
                item(
                        "answer",
                        "turn",
                        6,
                        "COMPLETED",
                        new TextItemContent("agentMessage", "已完成", JsonDocument.EMPTY_OBJECT)),
                item(
                        "failure",
                        "turn-2",
                        1,
                        "FAILED",
                        new ErrorItemContent("error", "DENIED", "权限被拒绝", false, JsonDocument.EMPTY_OBJECT))));

        assertEquals(4, blocks.size());
        assertEquals(TranscriptBlock.Category.USER_MESSAGE, blocks.get(0).category());
        TranscriptBlock execution = blocks.get(1);
        assertEquals(TranscriptBlock.Category.EXECUTION, execution.category());
        assertEquals(4, execution.itemIds().size());
        assertEquals(1, occurrences(execution.text(), "上下文与用量已更新"));
        assertTrue(execution.text().contains("退出码：0"));
        assertFalse(execution.text().contains("STARTED"));
        assertEquals(TranscriptBlock.Category.AGENT_MESSAGE, blocks.get(2).category());
        assertEquals(TranscriptBlock.Category.ERROR, blocks.get(3).category());
    }

    private static ItemInfo item(String id, String turnId, long ordinal, String state, ItemContent content) {
        return new ItemInfo(id, "thread", turnId, ordinal, state, content, Instant.EPOCH, Instant.EPOCH);
    }

    private static int occurrences(String text, String part) {
        return (text.length() - text.replace(part, "").length()) / part.length();
    }
}
