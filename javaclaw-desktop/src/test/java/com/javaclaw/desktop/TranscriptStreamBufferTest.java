package com.javaclaw.desktop;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.javaclaw.sdk.ItemDeltaNotification;
import com.javaclaw.sdk.model.JsonDocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptStreamBufferTest {
    @Test
    void duplicateLateAndPreviousThreadDeltasCannotCorruptCurrentTranscript() {
        var buffer = new TranscriptStreamBuffer();
        buffer.select("thread");
        assertTrue(buffer.append(delta("thread", "item", 1, "hello")));
        assertFalse(buffer.append(delta("thread", "item", 1, "duplicate")));
        assertEquals("hello", buffer.text());
        buffer.completed("item");
        assertFalse(buffer.append(delta("thread", "item", 2, "late")));
        assertEquals("", buffer.text());
        buffer.select("other");
        assertFalse(buffer.append(delta("thread", "new-item", 1, "wrong thread")));
    }

    @Test
    void hugeAndGappedStreamsStayBoundedAndClearlyMarkIncompletePreviews() {
        var buffer = new TranscriptStreamBuffer();
        buffer.select("thread");
        for (int index = 0; index < 100; index++) {
            buffer.append(delta("thread", "item-" + index, 4, "x".repeat(100_000)));
        }
        assertTrue(buffer.text().contains("预览不完整"));
        assertTrue(buffer.text().length()
                < TranscriptStreamBuffer.MAXIMUM_ITEMS * (TranscriptStreamBuffer.MAXIMUM_CHARACTERS + 100));
    }

    private static ItemDeltaNotification delta(String thread, String item, long sequence, String text) {
        return new ItemDeltaNotification(
                thread, "turn", item, "agentMessage", sequence, text, JsonDocument.EMPTY_OBJECT, Instant.EPOCH);
    }
}
