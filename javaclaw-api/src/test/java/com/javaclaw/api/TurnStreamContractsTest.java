package com.javaclaw.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnStreamContractsTest {
    private final TurnId turn = TurnId.random();
    private final TurnStreamCall call = new TurnStreamCall(1, "attempt", ItemId.random());

    @Test
    void 每类事件只有其允许的公开字段且调用身份固定() {
        for (TurnStreamKind kind : TurnStreamKind.values()) {
            var data = valid(kind);
            var event = new TurnStreamEvent(turn, "cursor", "previous", data);
            assertEquals(data, event.data());
            assertEquals(kind == TurnStreamKind.TURN_FINISHED, data.call().isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () -> new TurnStreamCall(0, "attempt", ItemId.random()));
        assertThrows(IllegalArgumentException.class, () -> new TurnStreamCall(1, " ", ItemId.random()));
        assertThrows(IllegalArgumentException.class, () -> new TurnStreamCall(1, "a".repeat(101), ItemId.random()));
        assertEquals(
                100,
                new TurnStreamCall(1, "a".repeat(100), ItemId.random())
                        .attemptId()
                        .length());
    }

    @Test
    void 事件拒绝跨类别字段() {
        for (TurnStreamKind kind : TurnStreamKind.values()) {
            var data = valid(kind);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TurnStreamEvent.Data(
                            kind,
                            data.call().isEmpty() ? Optional.of(call) : Optional.empty(),
                            data.text(),
                            data.offsetUtf16(),
                            data.itemSequence(),
                            data.status()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TurnStreamEvent.Data(
                            kind, data.call(), data.text(), -1, data.itemSequence(), data.status()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TurnStreamEvent.Data(
                            kind,
                            data.call(),
                            data.text(),
                            data.offsetUtf16(),
                            data.itemSequence(),
                            data.status().isEmpty() ? Optional.of(TurnStatus.FAILED) : Optional.empty()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TurnStreamEvent.Data(
                            kind,
                            data.call(),
                            data.text(),
                            data.offsetUtf16(),
                            data.itemSequence().isEmpty() ? Optional.of(1L) : Optional.empty(),
                            data.status()));
        }
        assertThrows(IllegalArgumentException.class, () -> data(TurnStreamKind.STARTED, "text", 0, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> data(TurnStreamKind.STARTED, "", 1, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> data(TurnStreamKind.COMMITTED, "", 0, Optional.of(0L)));
        assertThrows(IllegalArgumentException.class, () -> data(TurnStreamKind.COMMITTED, "", 0, Optional.of(-1L)));
    }

    @Test
    void 终态标识只允许真实Turn终态() {
        for (TurnStatus status : List.of(TurnStatus.QUEUED, TurnStatus.RUNNING, TurnStatus.WAITING)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TurnStreamEvent.Data(
                            TurnStreamKind.TURN_FINISHED,
                            Optional.empty(),
                            "",
                            0,
                            Optional.of(0L),
                            Optional.of(status)));
        }
        for (TurnStatus status : List.of(TurnStatus.COMPLETED, TurnStatus.CANCELLED, TurnStatus.FAILED)) {
            assertEquals(
                    status,
                    new TurnStreamEvent.Data(
                                    TurnStreamKind.TURN_FINISHED,
                                    Optional.empty(),
                                    "",
                                    0,
                                    Optional.of(0L),
                                    Optional.of(status))
                            .status()
                            .orElseThrow());
        }
    }

    @Test
    void 公开文字禁止不完整UTF16而保留合法emoji() {
        assertEquals(
                "😀", data(TurnStreamKind.TEXT_DELTA, "😀", 0, Optional.empty()).text());
        for (String malformed : List.of("\uD800", "\uD800a", "\uDC00")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> data(TurnStreamKind.TEXT_DELTA, malformed, 0, Optional.empty()));
        }
    }

    @Test
    void 历史摘要不可修改也不会将任意大正文当完整Item() {
        var attachments = new ArrayList<AttachmentRef>();
        attachments.add(new AttachmentRef("a".repeat(64), "text/plain", "file", 1));
        var entry = history("摘要", attachments, List.of());
        attachments.clear();
        assertEquals(1, entry.attachments().size());
        assertEquals(
                4096, history("a".repeat(4096), List.of(), List.of()).summary().length());
        assertThrows(IllegalArgumentException.class, () -> history("a".repeat(4097), List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> history(
                        "x",
                        java.util.Collections.nCopies(33, entry.attachments().getFirst()),
                        List.of()));
        var reference = DocumentReference.message(WorkspaceId.random(), entry.id(), "body");
        assertThrows(
                IllegalArgumentException.class,
                () -> history("x", List.of(), java.util.Collections.nCopies(33, reference)));
        assertThrows(IllegalArgumentException.class, () -> new ItemHistoryResult(List.of(), -1, false));
        assertTrue(new ItemHistoryResult(List.of(entry), 1, false).items().contains(entry));
    }

    private TurnStreamEvent.Data valid(TurnStreamKind kind) {
        if (kind == TurnStreamKind.TURN_FINISHED) {
            return new TurnStreamEvent.Data(
                    kind, Optional.empty(), "", 0, Optional.of(1L), Optional.of(TurnStatus.COMPLETED));
        }
        return data(
                kind,
                kind == TurnStreamKind.TEXT_DELTA ? "文字" : "",
                0,
                kind == TurnStreamKind.COMMITTED ? Optional.of(1L) : Optional.empty());
    }

    private TurnStreamEvent.Data data(TurnStreamKind kind, String text, long offset, Optional<Long> sequence) {
        return new TurnStreamEvent.Data(kind, Optional.of(call), text, offset, sequence, Optional.empty());
    }

    private ItemHistoryEntry history(String text, List<AttachmentRef> attachments, List<DocumentReference> references) {
        return new ItemHistoryEntry(
                ItemId.random(),
                turn,
                1,
                "message",
                Optional.of(MessageRole.ASSISTANT),
                text,
                Optional.empty(),
                true,
                Instant.EPOCH,
                attachments,
                references);
    }
}
