package com.javaclaw.client.facade;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Optional;

import com.javaclaw.api.ItemId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.TurnStreamEvent;
import com.javaclaw.api.TurnStreamKind;
import com.javaclaw.protocol.TurnStreamRpcContracts;

/** 单订阅顺序 reducer；持久事件不能合并丢弃，绘制层可以合并完整快照。 */
final class TurnStreamState {
    private static final int MAXIMUM_TEXT_UNITS = 1024 * 1024;
    private final TurnId turnId;
    private final LinkedHashMap<ItemId, MessageState> messages = new LinkedHashMap<>();
    private final LinkedHashSet<String> recent = new LinkedHashSet<>();
    private String cursor;
    private Optional<TurnStatus> terminal;
    private Optional<Long> finalItemSequence;

    TurnStreamState(TurnStreamSnapshot snapshot) {
        turnId = snapshot.turnId();
        cursor = snapshot.cursor();
        terminal = snapshot.terminal();
        finalItemSequence = snapshot.finalItemSequence();
        snapshot.messages().forEach(message -> messages.put(message.call().messageItemId(), new MessageState(message)));
        recent.add(cursor);
    }

    synchronized TurnStreamSnapshot snapshot() {
        return new TurnStreamSnapshot(
                turnId,
                cursor,
                messages.values().stream().map(MessageState::snapshot).toList(),
                terminal,
                finalItemSequence);
    }

    synchronized void apply(TurnStreamRpcContracts.Notification notification) {
        for (TurnStreamEvent event : notification.events()) {
            apply(event);
        }
        notification.watermark().ifPresent(watermark -> {
            if (!watermark.lastCursor().equals(cursor)
                    || watermark.terminal() != terminal.isPresent()
                    || !watermark.finalItemSequence().equals(finalItemSequence)) {
                throw new IllegalStateException("聊天流水位缺少前序事件");
            }
        });
    }

    synchronized void prune(java.util.Set<ItemId> persistedIds) {
        messages.entrySet()
                .removeIf(entry ->
                        persistedIds.contains(entry.getKey()) && entry.getValue().kind == TurnStreamKind.COMMITTED);
    }

    private void apply(TurnStreamEvent event) {
        if (!event.turnId().equals(turnId)) {
            throw new IllegalStateException("聊天流跨 Turn");
        }
        if (recent.contains(event.cursor())) {
            return;
        }
        if (!event.previousCursor().equals(cursor) || terminal.isPresent()) {
            throw new IllegalStateException("聊天流前驱不匹配");
        }
        var data = event.data();
        if (data.kind() == TurnStreamKind.TURN_FINISHED) {
            terminal = data.status();
            finalItemSequence = data.itemSequence();
        } else {
            applyMessage(data);
        }
        trimCompleted();
        cursor = event.cursor();
        recent.add(cursor);
        if (recent.size() > 1024) {
            recent.removeFirst();
        }
    }

    private void trimCompleted() {
        long total = messages.values().stream()
                .mapToLong(value -> value.text.length())
                .sum();
        for (MessageState message : messages.values()) {
            if (total <= 2L * MAXIMUM_TEXT_UNITS) {
                break;
            }
            if (message.kind == TurnStreamKind.COMMITTED || message.kind == TurnStreamKind.CLOSED) {
                int length = message.text.length();
                message.textOffset += length;
                message.text.setLength(0);
                message.text.trimToSize();
                total -= length;
            }
        }
    }

    private void applyMessage(TurnStreamEvent.Data data) {
        var call = data.call().orElseThrow();
        var prior = messages.get(call.messageItemId());
        if (data.kind() == TurnStreamKind.STARTED) {
            if (prior != null) {
                throw new IllegalStateException("模型调用身份重复建立");
            }
            messages.put(
                    call.messageItemId(),
                    new MessageState(new TurnStreamSnapshot.Message(call, "", data.kind(), Optional.empty())));
            return;
        }
        if (prior == null
                || !prior.call.equals(call)
                || prior.kind == TurnStreamKind.CLOSED
                || prior.kind == TurnStreamKind.COMMITTED) {
            throw new IllegalStateException("模型调用身份缺失或已经关闭");
        }
        StringBuilder text = prior.text;
        if (data.kind() == TurnStreamKind.TEXT_DELTA) {
            if (data.offsetUtf16() != prior.textOffset + text.length()) {
                throw new IllegalStateException("模型正文 offset 不匹配");
            }
            text.append(data.text());
            prior.trim();
        }
        prior.kind = data.kind();
        prior.itemSequence = data.itemSequence();
    }

    private static final class MessageState {
        private final com.javaclaw.api.TurnStreamCall call;
        private final StringBuilder text;
        private long textOffset;
        private TurnStreamKind kind;
        private Optional<Long> itemSequence;

        private MessageState(TurnStreamSnapshot.Message message) {
            call = message.call();
            text = new StringBuilder(message.text());
            textOffset = message.textOffsetUtf16();
            kind = message.state();
            itemSequence = message.itemSequence();
        }

        private void trim() {
            if (text.length() <= MAXIMUM_TEXT_UNITS) {
                return;
            }
            int remove = text.length() - MAXIMUM_TEXT_UNITS / 2;
            if (Character.isLowSurrogate(text.charAt(remove))) {
                remove++;
            }
            text.delete(0, remove);
            text.trimToSize();
            textOffset += remove;
        }

        private TurnStreamSnapshot.Message snapshot() {
            return new TurnStreamSnapshot.Message(call, text.toString(), kind, itemSequence, textOffset);
        }
    }
}
