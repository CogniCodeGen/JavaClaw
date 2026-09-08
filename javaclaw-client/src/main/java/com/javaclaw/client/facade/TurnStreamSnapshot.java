package com.javaclaw.client.facade;

import java.util.List;
import java.util.Optional;

import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.TurnStreamCall;
import com.javaclaw.api.TurnStreamKind;

/**
 * Java 展示状态的恢复快照；只有成功归并的事件才能推进 cursor。
 *
 * @param turnId 目标 Turn
 * @param cursor 最后已应用的公开事件位置
 * @param messages 调用顺序的暂态正文，COMMITTED 仍需以权威 Item 正文替换
 * @param terminal 已应用的终态，尚未结束为空
 * @param finalItemSequence 终态 Item 水位，尚未结束为空
 */
public record TurnStreamSnapshot(
        TurnId turnId,
        String cursor,
        List<Message> messages,
        Optional<TurnStatus> terminal,
        Optional<Long> finalItemSequence) {
    /** 冻结快照列表。 */
    public TurnStreamSnapshot {
        messages = List.copyOf(messages);
    }

    /**
     * 一次调用的可重建正文。
     *
     * @param call 持久调用身份及最终 Item ID
     * @param text 已归并的正文，可空字符串
     * @param state STARTED、TEXT_DELTA、COMMITTED 或 CLOSED
     * @param itemSequence 最终 Item sequence，仅 COMMITTED 存在
     * @param textOffsetUtf16 缓存正文相对完整正文的起点，超过 2 MiB 时仅保留尾部
     */
    public record Message(
            TurnStreamCall call, String text, TurnStreamKind state, Optional<Long> itemSequence, long textOffsetUtf16) {
        /**
         * 创建尚未裁剪的正文快照。
         *
         * @param call 调用身份
         * @param text 完整缓存正文
         * @param state 调用状态
         * @param itemSequence 最终 Item 序号
         */
        public Message(TurnStreamCall call, String text, TurnStreamKind state, Optional<Long> itemSequence) {
            this(call, text, state, itemSequence, 0);
        }
    }
}
