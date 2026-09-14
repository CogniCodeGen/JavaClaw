package com.javaclaw.desktop;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.state.OutgoingMessage;
import com.javaclaw.desktop.state.TranscriptState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopSendLedgerTest {
    @Test
    void 多条失败按原顺序保留且重试冻结原正文和幂等身份() {
        DesktopSendLedger ledger = new DesktopSendLedger();
        var first = command("原问题");
        var firstMessage = ledger.begin(first, 1, 5);
        ledger.unconfirmed(first);
        var second = command("后来输入的问题");
        ledger.begin(second, 2, 8);
        ledger.unconfirmed(second);
        var retry = ledger.retry(first.thread(), firstMessage.id());
        assertSame(first, retry);
        ledger.begin(retry, 3, 10);
        assertEquals(
                List.of("原问题", "后来输入的问题"),
                ledger.messages(first.thread()).stream()
                        .map(OutgoingMessage::text)
                        .toList());
        assertEquals(3, ledger.messages(first.thread()).getFirst().attempt());
        assertEquals(5, ledger.messages(first.thread()).getFirst().afterSequence());
        assertEquals(8, ledger.messages(first.thread()).getLast().afterSequence());
        assertEquals(
                OutgoingMessage.Status.UNCONFIRMED,
                ledger.messages(first.thread()).getLast().status());
        assertTrue(ledger.sending(first.thread()));
    }

    @Test
    void 离开会话后迟到回执保存但不污染另一会话() {
        DesktopSendLedger ledger = new DesktopSendLedger();
        var command = command("切换前发送");
        ledger.begin(command, 1);
        ConversationThread other = anotherThread(command.thread());
        assertTrue(ledger.project(other, TranscriptState.empty()).outgoings().isEmpty());
        ledger.accepted(command, DesktopTestFixtures.turn());
        assertTrue(ledger.project(other, TranscriptState.empty()).outgoings().isEmpty());
        var returned = ledger.project(command.thread(), TranscriptState.empty());
        assertEquals(
                OutgoingMessage.Status.ACCEPTED, returned.outgoings().getFirst().status());
        assertThrows(
                IllegalStateException.class,
                () -> ledger.retry(other, returned.outgoings().getFirst().id()));
    }

    @Test
    void 权威Turn确认只移除匹配发送且切回不会重新出现() {
        DesktopSendLedger ledger = new DesktopSendLedger();
        var accepted = command("相同正文");
        ledger.begin(accepted, 1);
        ledger.accepted(accepted, DesktopTestFixtures.turn());
        var unknown = command("相同正文");
        ledger.begin(unknown, 2);
        ledger.unconfirmed(unknown);
        var entry = new ItemHistoryEntry(
                ItemId.random(),
                DesktopTestFixtures.turn().id(),
                1,
                "message",
                Optional.of(MessageRole.USER),
                "被截断的权威摘要",
                Optional.empty(),
                true,
                DesktopTestFixtures.NOW,
                List.of(),
                List.of());
        TranscriptState history = new TranscriptState(List.of(), 1, true, Optional.empty(), false, List.of(entry));
        var projected = ledger.project(accepted.thread(), history);
        assertEquals(1, projected.outgoings().size());
        assertEquals(
                DesktopSendLedger.id(unknown.options()),
                projected.outgoings().getFirst().id());
        assertEquals(
                projected.outgoings(),
                ledger.project(accepted.thread(), TranscriptState.empty()).outgoings());
        assertFalse(ledger.sending(accepted.thread()));
    }

    @Test
    void 更改原幂等身份的载荷被拒绝且记录仍可重试() {
        DesktopSendLedger ledger = new DesktopSendLedger();
        var original = command("原正文");
        var message = ledger.begin(original, 1);
        ledger.unconfirmed(original);
        var changed = new DesktopSendLedger.Command(original.thread(), "新正文", original.execution(), original.options());
        assertThrows(IllegalArgumentException.class, () -> ledger.begin(changed, 2));
        assertSame(original, ledger.retry(original.thread(), message.id()));
    }

    private static DesktopSendLedger.Command command(String text) {
        return new DesktopSendLedger.Command(
                DesktopTestFixtures.thread(), text, ExecutionOverrides.empty(), CommandOptions.create(0));
    }

    private static ConversationThread anotherThread(ConversationThread source) {
        return new ConversationThread(
                ThreadId.random(),
                source.workspaceId(),
                source.parentThreadId(),
                source.executionIntent(),
                "另一会话",
                source.status(),
                source.revision(),
                source.createdAt(),
                source.updatedAt());
    }
}
