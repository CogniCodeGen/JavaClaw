package com.javaclaw.desktop.state;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.ItemHistoryResult;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.TurnStreamCall;
import com.javaclaw.api.TurnStreamKind;
import com.javaclaw.client.facade.TurnStreamSnapshot;
import com.javaclaw.protocol.CanonicalJson;

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

    @Test
    void 本地回显不伪造持久序号且确认失败均保留正文和阅读策略() {
        TranscriptState sending = TranscriptState.empty().following(false).outgoing(outgoing());
        assertTrue(sending.following());
        assertEquals(0, sending.nextSequence());
        assertTrue(sending.items().isEmpty());
        TranscriptState accepted = sending.following(false).acknowledged("outgoing:one", turnId);
        assertEquals(
                OutgoingMessage.Status.ACCEPTED,
                accepted.outgoing().orElseThrow().status());
        assertFalse(accepted.following());
        TranscriptState failed = accepted.unconfirmed("outgoing:one");
        assertEquals(
                OutgoingMessage.Status.UNCONFIRMED,
                failed.outgoing().orElseThrow().status());
        assertEquals("本地完整正文", failed.outgoing().orElseThrow().text());
        assertEquals(7, accepted.outgoing().orElseThrow().attempt());
        assertEquals(7, failed.outgoing().orElseThrow().attempt());
        assertEquals(failed, failed.acknowledged("outgoing:stale", TurnId.random()));
        assertEquals(failed, failed.unconfirmed("outgoing:stale"));
    }

    @Test
    void 摘要按Turn和用户角色替换回显而非根据文本或摘要长度() {
        TranscriptState accepted = TranscriptState.empty().outgoing(outgoing()).acknowledged("outgoing:one", turnId);
        var unrelated = summary(TurnId.random(), 1, MessageRole.USER, "本地完整正文");
        var assistant = summary(turnId, 2, MessageRole.ASSISTANT, "本地完整正文");
        TranscriptState waiting = accepted.committed(new ItemHistoryResult(List.of(unrelated, assistant), 2, false));
        assertTrue(waiting.outgoing().isPresent());
        var truncated = summary(turnId, 3, MessageRole.USER, "截断摘要");
        TranscriptState confirmed = waiting.committed(new ItemHistoryResult(List.of(truncated), 3, false));
        assertTrue(confirmed.outgoing().isEmpty());
        assertEquals(3, confirmed.nextSequence());
        assertEquals(3, confirmed.history().size());
    }

    @Test
    void 权威用户消息先到时回执绑定立即去重且旧协议也按角色确认() {
        ItemEnvelope source = item(1);
        ItemEnvelope user = new ItemEnvelope(
                source.id(),
                turnId,
                1,
                "message",
                CoreSchemas.MESSAGE,
                "core",
                ItemStatus.COMPLETED,
                new CanonicalJson()
                        .encode(new CorePayloads.Message(MessageRole.USER, "实际权威正文", List.of(), Optional.empty())),
                source.createdAt(),
                source.completedAt());
        TranscriptState waiting = TranscriptState.empty().outgoing(outgoing()).append(List.of(user), 1);
        assertTrue(waiting.outgoing().isPresent());
        assertTrue(waiting.acknowledged("outgoing:one", turnId).outgoing().isEmpty());
    }

    @Test
    void 分页和暂停跟随仍保留待发送身份且尾页确认不会留下重复回显() {
        TranscriptState accepted = new TranscriptState(List.of(), 100, true, Optional.empty(), false, history(1, 101))
                .outgoing(outgoing())
                .acknowledged("outgoing:one", TurnId.random());
        TranscriptState earlier = accepted.earlier(new ItemHistoryResult(List.of(), 100, false));
        assertEquals(accepted.outgoing(), earlier.outgoing());
        TurnId acceptedTurn = accepted.outgoing().orElseThrow().turnId().orElseThrow();
        var confirmed = summary(acceptedTurn, 151, MessageRole.USER, "权威消息");
        TranscriptState updated = earlier.committed(new ItemHistoryResult(List.of(confirmed), 151, true));
        assertFalse(updated.following());
        assertEquals(earlier.history(), updated.history());
        assertTrue(updated.outgoing().isEmpty());
    }

    @Test
    void 主动提交尝试号必须非负且兼容构造不制造新重试() {
        OutgoingMessage compatible =
                new OutgoingMessage("outgoing:old", "正文", Optional.empty(), OutgoingMessage.Status.SENDING);
        assertEquals(0, compatible.attempt());
        assertThrows(
                IllegalArgumentException.class,
                () -> new OutgoingMessage(
                        "outgoing:invalid", "正文", Optional.empty(), OutgoingMessage.Status.SENDING, -1));
    }

    @Test
    void 新Turn回执才结束旧轮尾文展示且保留已到达的同轮正文() {
        TurnStreamSnapshot previous = closedStream(TurnId.random());
        TranscriptState sending = TranscriptState.empty().stream(previous).outgoing(outgoing());
        assertEquals(Optional.of(previous), sending.stream());
        assertEquals(Optional.of(previous), sending.unconfirmed("outgoing:one").stream());
        assertEquals(sending, sending.acknowledged("outgoing:stale", turnId));
        assertTrue(sending.acknowledged("outgoing:one", turnId).stream().isEmpty());
        TurnStreamSnapshot current = closedStream(turnId);
        assertEquals(Optional.of(current), sending.stream(current).acknowledged("outgoing:one", turnId).stream());
    }

    private TurnStreamSnapshot closedStream(TurnId turn) {
        var call = new TurnStreamCall(1, "closed-call", ItemId.random());
        var message = new TurnStreamSnapshot.Message(call, "取消前保留的尾文", TurnStreamKind.CLOSED, Optional.empty());
        return new TurnStreamSnapshot(
                turn, "cursor", List.of(message), Optional.of(TurnStatus.CANCELLED), Optional.of(0L));
    }

    private OutgoingMessage outgoing() {
        return new OutgoingMessage("outgoing:one", "本地完整正文", Optional.empty(), OutgoingMessage.Status.SENDING, 7);
    }

    private ItemHistoryEntry summary(TurnId turn, long sequence, MessageRole role, String text) {
        return new ItemHistoryEntry(
                ItemId.random(),
                turn,
                sequence,
                "message",
                Optional.of(role),
                text,
                Optional.empty(),
                true,
                Instant.EPOCH,
                List.of(),
                List.of());
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
