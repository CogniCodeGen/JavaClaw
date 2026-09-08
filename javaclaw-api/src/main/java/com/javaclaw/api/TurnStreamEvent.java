package com.javaclaw.api;

import java.util.Objects;
import java.util.Optional;

/**
 * 只允许正文的持久聊天事件。客户端仅比较 cursor 是否相等，不解析其内容。
 *
 * @param turnId 来源 Turn，不可空
 * @param cursor 当前不透明位置，不可空
 * @param previousCursor 同 Turn 上一公开事件位置，首条为 START
 * @param data 固定公开投影，不可空
 */
public record TurnStreamEvent(TurnId turnId, String cursor, String previousCursor, Data data) {
    /** 校验完整事件信封。 */
    public TurnStreamEvent {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(previousCursor, "previousCursor");
        Objects.requireNonNull(data, "data");
    }

    /**
     * 公开事件正文，非文字事件 text 为空，offsetUtf16 为 0。
     *
     * @param kind 事件类别，不可空
     * @param call 调用身份，仅 TURN_FINISHED 为空
     * @param text 助手正文增量，其余事件为空字符串
     * @param offsetUtf16 正文起点，单位 UTF-16 code unit，非负
     * @param itemSequence COMMITTED 的最终 Item sequence 或终态最终 Item 水位，其余为空
     * @param status 仅 TURN_FINISHED 携带终态
     */
    public record Data(
            TurnStreamKind kind,
            Optional<TurnStreamCall> call,
            String text,
            long offsetUtf16,
            Optional<Long> itemSequence,
            Optional<TurnStatus> status) {
        /** 拒绝不完整的身份和跨类别字段。 */
        public Data {
            Objects.requireNonNull(kind, "kind");
            call = Objects.requireNonNull(call, "call");
            text = Objects.requireNonNull(text, "text");
            requireValidText(text);
            itemSequence = Objects.requireNonNull(itemSequence, "itemSequence");
            status = Objects.requireNonNull(status, "status");
            if ((kind == TurnStreamKind.TURN_FINISHED) != call.isEmpty() || offsetUtf16 < 0) {
                throw new IllegalArgumentException("invalid stream call identity or offset");
            }
            if (kind != TurnStreamKind.TEXT_DELTA && (!text.isEmpty() || offsetUtf16 != 0)) {
                throw new IllegalArgumentException("only text delta contains text and offset");
            }
            if ((kind == TurnStreamKind.TURN_FINISHED) != status.isPresent()) {
                throw new IllegalArgumentException("only terminal event contains status");
            }
            if ((kind == TurnStreamKind.COMMITTED || kind == TurnStreamKind.TURN_FINISHED)
                    != itemSequence.isPresent()) {
                throw new IllegalArgumentException("invalid final Item sequence");
            }
            requireTerminalValues(kind, itemSequence, status);
        }

        private static void requireTerminalValues(
                TurnStreamKind kind, Optional<Long> itemSequence, Optional<TurnStatus> status) {
            if (itemSequence.filter(value -> value < 0).isPresent()
                    || (kind == TurnStreamKind.COMMITTED && itemSequence.orElseThrow() == 0)) {
                throw new IllegalArgumentException("invalid final Item sequence value");
            }
            if (status.filter(value -> value != TurnStatus.COMPLETED
                            && value != TurnStatus.CANCELLED
                            && value != TurnStatus.FAILED)
                    .isPresent()) {
                throw new IllegalArgumentException("stream terminal status must be terminal");
            }
        }

        private static void requireValidText(String text) {
            for (int index = 0; index < text.length(); index++) {
                char value = text.charAt(index);
                if (Character.isHighSurrogate(value)) {
                    if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) {
                        throw new IllegalArgumentException("stream text contains an incomplete surrogate pair");
                    }
                } else if (Character.isLowSurrogate(value)) {
                    throw new IllegalArgumentException("stream text contains an isolated low surrogate");
                }
            }
        }
    }
}
