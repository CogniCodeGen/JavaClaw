package com.javaclaw.desktop.state;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.TurnId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptStateTest {
    private final TurnId turnId = TurnId.random();

    @Test
    void appendRequiresStrictNonOverlappingSequence() {
        TranscriptState first = TranscriptState.empty().append(List.of(item(1)), 1);
        TranscriptState second = first.append(List.of(item(2), item(3)), 3);

        assertEquals(
                List.of(1L, 2L, 3L),
                second.items().stream().map(ItemEnvelope::sequence).toList());
        assertEquals(3, second.nextSequence());
        assertThrows(IllegalArgumentException.class, () -> second.append(List.of(item(3)), 3));
        assertThrows(IllegalArgumentException.class, () -> second.append(List.of(), 2));
    }

    @Test
    void 历史超过五百条时向前浏览保留锚点而新事实仍推进最大水位() {
        var initial = new TranscriptState(List.of(), 1000, true, Optional.empty(), true, history(501, 1001));
        var earlier = initial.earlier(new com.javaclaw.api.ItemHistoryResult(history(401, 501), 1000, true));
        assertEquals(500, earlier.history().size());
        assertEquals(401, earlier.history().getFirst().sequence());
        assertEquals(900, earlier.history().getLast().sequence());
        var updated = earlier.committed(new com.javaclaw.api.ItemHistoryResult(history(1001, 1101), 1100, true));
        assertEquals(401, updated.history().getFirst().sequence());
        assertEquals(900, updated.history().getLast().sequence());
        assertEquals(1100, updated.nextSequence());
    }

    @Test
    void 新事实跨过一页时保持连续尾窗口并可向前补齐所有遗漏消息() {
        var initial = new TranscriptState(List.of(), 100, true, Optional.empty(), false, history(1, 101));
        var latest = initial.committed(new com.javaclaw.api.ItemHistoryResult(history(151, 251), 250, true));
        assertEquals(151, latest.history().getFirst().sequence());
        assertTrue(latest.hasEarlier());
        // 分页入口始终是连续窗口的首条；不能把旧缓存 1..100 留在前面遮住中间缺口。
        var previous = latest.earlier(new com.javaclaw.api.ItemHistoryResult(history(51, 151), 250, true));
        var complete = previous.earlier(new com.javaclaw.api.ItemHistoryResult(history(1, 51), 250, false));
        assertEquals(
                java.util.stream.LongStream.rangeClosed(1, 250).boxed().toList(),
                complete.history().stream().map(entry -> entry.sequence()).toList());
        assertFalse(complete.hasEarlier());
    }

    @Test
    void 暂停跟随时不把不连续尾页拼入当前阅读窗口() {
        var reading = new TranscriptState(List.of(), 100, false, Optional.empty(), false, history(1, 101));
        var updated = reading.committed(new com.javaclaw.api.ItemHistoryResult(history(151, 251), 250, true));
        assertEquals(reading.history(), updated.history());
        assertEquals(250, updated.nextSequence());
        assertFalse(updated.hasEarlier());
        assertFalse(updated.following());
    }

    @Test
    void 重叠或紧邻的尾页正常合并而无需重新加载已有历史() {
        var initial = new TranscriptState(List.of(), 100, true, Optional.empty(), false, history(1, 101));
        var overlap = initial.committed(new com.javaclaw.api.ItemHistoryResult(history(51, 151), 150, true));
        var adjacent = overlap.committed(new com.javaclaw.api.ItemHistoryResult(history(151, 201), 200, true));
        assertEquals(
                java.util.stream.LongStream.rangeClosed(1, 200).boxed().toList(),
                adjacent.history().stream().map(entry -> entry.sequence()).toList());
    }

    @Test
    void 旧完整Item窗口同时受字节预算限制且保留最新合法消息() {
        CanonicalPayload payload = new CanonicalPayload("{\"text\":\"" + "x".repeat(600_000) + "\"}");
        var items = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(index -> {
                    ItemEnvelope source = item(index);
                    return new ItemEnvelope(
                            source.id(),
                            source.turnId(),
                            source.sequence(),
                            source.kind(),
                            source.schemaId(),
                            source.producerId(),
                            source.status(),
                            payload,
                            source.createdAt(),
                            source.completedAt());
                })
                .toList();
        TranscriptState value = new TranscriptState(items, 10, true);
        long bytes = value.items().stream()
                .mapToLong(entry -> entry.payload().json().length() * 2L + 1024)
                .sum();
        org.junit.jupiter.api.Assertions.assertTrue(bytes <= 8L * 1024 * 1024);
        assertEquals(10, value.items().getLast().sequence());
    }

    private List<com.javaclaw.api.ItemHistoryEntry> history(int first, int end) {
        return java.util.stream.IntStream.range(first, end)
                .mapToObj(sequence -> new com.javaclaw.api.ItemHistoryEntry(
                        ItemId.random(),
                        turnId,
                        sequence,
                        "message",
                        Optional.of(com.javaclaw.api.MessageRole.USER),
                        "摘要",
                        Optional.empty(),
                        false,
                        Instant.EPOCH,
                        List.of(),
                        List.of()))
                .toList();
    }

    private ItemEnvelope item(long sequence) {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        return new ItemEnvelope(
                ItemId.random(),
                turnId,
                sequence,
                "test",
                "test/item@1",
                "test",
                ItemStatus.COMPLETED,
                new CanonicalPayload("{}"),
                now,
                Optional.of(now));
    }
}
