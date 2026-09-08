package com.javaclaw.client.facade;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ItemId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.TurnStreamCall;
import com.javaclaw.api.TurnStreamEvent;
import com.javaclaw.api.TurnStreamKind;
import com.javaclaw.protocol.TurnStreamRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnStreamStateTest {
    private final TurnId turnId = TurnId.random();
    private final TurnStreamCall call = new TurnStreamCall(1, "attempt", ItemId.random());

    @Test
    void 重复文本保留而重复事件去重并关联最终消息() {
        TurnStreamState state = empty();
        TurnStreamEvent first = event("1", "START", TurnStreamKind.STARTED, "", 0);
        TurnStreamEvent second = event("3", "1", TurnStreamKind.TEXT_DELTA, "回声", 0);
        state.apply(batch(first, second));
        state.apply(batch(first, second, event("7", "3", TurnStreamKind.TEXT_DELTA, "回声", 2)));
        state.apply(batch(event("9", "7", TurnStreamKind.COMMITTED, "", 0), finished("12", "9")));
        state.apply(new TurnStreamRpcContracts.Notification(
                "sub", List.of(), Optional.of(new TurnStreamRpcContracts.Watermark("12", true, Optional.of(5L)))));

        TurnStreamSnapshot snapshot = state.snapshot();
        assertEquals("回声回声", snapshot.messages().getFirst().text());
        assertEquals(call.messageItemId(), snapshot.messages().getFirst().call().messageItemId());
        assertEquals(Optional.of(5L), snapshot.messages().getFirst().itemSequence());
        assertEquals("12", snapshot.cursor());
        assertEquals(Optional.of(TurnStatus.COMPLETED), snapshot.terminal());
    }

    @Test
    void 中途断线从完整Java快照恢复且使用UTF16偏移() {
        TurnStreamState first = empty();
        first.apply(batch(
                event("1", "START", TurnStreamKind.STARTED, "", 0),
                event("4", "1", TurnStreamKind.TEXT_DELTA, "😀", 0)));
        TurnStreamState restored = new TurnStreamState(first.snapshot());
        restored.apply(batch(event("6", "4", TurnStreamKind.TEXT_DELTA, "好", 2)));
        assertEquals("😀好", restored.snapshot().messages().getFirst().text());
        assertEquals("6", restored.snapshot().cursor());
    }

    @Test
    void 末尾水位不推进游标且前驱断裂不会应用文字() {
        TurnStreamState state = empty();
        state.apply(batch(event("1", "START", TurnStreamKind.STARTED, "", 0)));
        assertThrows(
                IllegalStateException.class,
                () -> state.apply(batch(event("4", "missing", TurnStreamKind.TEXT_DELTA, "丢包", 0))));
        assertThrows(
                IllegalStateException.class,
                () -> state.apply(new TurnStreamRpcContracts.Notification(
                        "sub",
                        List.of(),
                        Optional.of(new TurnStreamRpcContracts.Watermark("4", false, Optional.empty())))));
        assertEquals("1", state.snapshot().cursor());
        assertTrue(state.snapshot().messages().getFirst().text().isEmpty());
    }

    @Test
    void 调用未开始偏移错误和终态之后的事件均被拒绝() {
        TurnStreamState state = empty();
        assertThrows(
                IllegalStateException.class,
                () -> state.apply(batch(event("1", "START", TurnStreamKind.TEXT_DELTA, "x", 0))));
        state.apply(batch(event("1", "START", TurnStreamKind.STARTED, "", 0)));
        assertThrows(
                IllegalStateException.class,
                () -> state.apply(batch(event("2", "1", TurnStreamKind.TEXT_DELTA, "x", 1))));
        state.apply(batch(event("2", "1", TurnStreamKind.CLOSED, "", 0), finished("3", "2")));
        assertThrows(
                IllegalStateException.class,
                () -> state.apply(batch(event("4", "3", TurnStreamKind.TEXT_DELTA, "迟到", 0))));
    }

    @Test
    void 大正文保留有界尾部且裁剪和重连不改变完整UTF16位置() {
        TurnStreamState state = empty();
        state.apply(batch(event("start", "START", TurnStreamKind.STARTED, "", 0)));
        String fragment = "😀".repeat(1024);
        long total = 0;
        String previous = "start";
        for (int index = 0; index < 1024; index++) {
            String cursor = "text-" + index;
            state.apply(batch(event(cursor, previous, TurnStreamKind.TEXT_DELTA, fragment, total)));
            total += fragment.length();
            previous = cursor;
        }
        var retained = state.snapshot().messages().getFirst();
        assertTrue(retained.text().length() <= 1024 * 1024);
        assertTrue(retained.textOffsetUtf16() > 0);
        assertEquals(total, retained.textOffsetUtf16() + retained.text().length());
        assertTrue(Character.isHighSurrogate(retained.text().charAt(0)));
        TurnStreamState restored = new TurnStreamState(state.snapshot());
        restored.apply(batch(event("last", previous, TurnStreamKind.TEXT_DELTA, "尾", total)));
        restored.apply(batch(event("commit", "last", TurnStreamKind.COMMITTED, "", 0)));
        restored.prune(Set.of(call.messageItemId()));
        assertTrue(restored.snapshot().messages().isEmpty());
        restored.apply(batch(finished("terminal", "commit")));
        assertEquals("terminal", restored.snapshot().cursor());
    }

    @Test
    void 终态水位序号不同不能冒充已完成对账() {
        TurnStreamState state = empty();
        state.apply(batch(finished("terminal", "START")));
        assertThrows(
                IllegalStateException.class,
                () -> state.apply(new TurnStreamRpcContracts.Notification(
                        "sub",
                        List.of(),
                        Optional.of(new TurnStreamRpcContracts.Watermark("terminal", true, Optional.of(6L))))));
    }

    private TurnStreamState empty() {
        return new TurnStreamState(
                new TurnStreamSnapshot(turnId, "START", List.of(), Optional.empty(), Optional.empty()));
    }

    private TurnStreamEvent event(String cursor, String previous, TurnStreamKind kind, String text, long offset) {
        return new TurnStreamEvent(
                turnId,
                cursor,
                previous,
                new TurnStreamEvent.Data(
                        kind,
                        Optional.of(call),
                        text,
                        offset,
                        kind == TurnStreamKind.COMMITTED ? Optional.of(5L) : Optional.empty(),
                        Optional.empty()));
    }

    private TurnStreamEvent finished(String cursor, String previous) {
        return new TurnStreamEvent(
                turnId,
                cursor,
                previous,
                new TurnStreamEvent.Data(
                        TurnStreamKind.TURN_FINISHED,
                        Optional.empty(),
                        "",
                        0,
                        Optional.of(5L),
                        Optional.of(TurnStatus.COMPLETED)));
    }

    private static TurnStreamRpcContracts.Notification batch(TurnStreamEvent... events) {
        return new TurnStreamRpcContracts.Notification("sub", List.of(events), Optional.empty());
    }
}
