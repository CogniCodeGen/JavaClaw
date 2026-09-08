package com.javaclaw.protocol;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStreamEvent;

/** 连接拥有的持久聊天流协议；批量投递不合并事件身份，不提供客户端 ACK。 */
public final class TurnStreamRpcContracts {
    /** 双方明确协商的公开正文能力。 */
    public static final String CAPABILITY = "core.turn-stream-v1";
    /** 建立连接内订阅。 */
    public static final String SUBSCRIBE = "turn/stream/subscribe";
    /** 释放连接内订阅。 */
    public static final String UNSUBSCRIBE = "turn/stream/unsubscribe";
    /** 有界日志补拉。 */
    public static final String LIST = "turn/stream/list";
    /** 服务端批量数据或有序水位通知。 */
    public static final String EVENT = "turn/stream/event";
    /** 从末尾有界读取聊天 Item。 */
    public static final String ITEM_HISTORY = "item/history";
    /** 首条公开事件之前的位置。 */
    public static final String START = "START";

    private TurnStreamRpcContracts() {}

    /**
     * 订阅输入；同一连接内 subscriptionId 不得重新激活。
     *
     * @param subscriptionId 客户端生成的不透明 ID
     * @param turnId 目标 Turn
     * @param afterCursor 最后已成功应用的位置，初次为 START
     */
    public record Subscribe(String subscriptionId, TurnId turnId, String afterCursor) {
        /** 校验订阅参数。 */
        public Subscribe {
            subscriptionId = identifier(subscriptionId);
            Objects.requireNonNull(turnId, "turnId");
            afterCursor = cursor(afterCursor);
        }
    }

    /**
     * 静态受理回执，不代表数据已经发送或客户端已经应用。
     *
     * @param subscriptionId 已受理的 ID
     * @param turnId 已受理的 Turn
     * @param acceptedAfterCursor 原请求的恢复起点
     */
    public record Receipt(String subscriptionId, TurnId turnId, String acceptedAfterCursor) {}

    /**
     * 幂等释放订阅。
     *
     * @param subscriptionId 待关闭的 ID
     */
    public record Unsubscribe(String subscriptionId) {
        /** 校验 ID。 */
        public Unsubscribe {
            subscriptionId = identifier(subscriptionId);
        }
    }

    /**
     * 释放回执；未知 ID 也被标记关闭。
     *
     * @param subscriptionId 已关闭的 ID
     */
    public record Released(String subscriptionId) {}

    /**
     * 有界补拉输入。
     *
     * @param turnId 目标 Turn
     * @param afterCursor 起点，不含该事件
     * @param limit 页条数，1 至 128
     */
    public record ListRequest(TurnId turnId, String afterCursor, int limit) {
        /** 校验分页边界。 */
        public ListRequest {
            Objects.requireNonNull(turnId, "turnId");
            afterCursor = cursor(afterCursor);
            if (limit < 1 || limit > 128) {
                throw new IllegalArgumentException("stream limit must be between 1 and 128");
            }
        }
    }

    /**
     * 日志页，保持逐条 cursor；空页 nextCursor 等于原起点。
     *
     * @param events 顺序事件，不可空
     * @param nextCursor 最后一条实际返回的位置
     * @param hasMore 是否尚有后续持久事件
     */
    public record Page(List<TurnStreamEvent> events, String nextCursor, boolean hasMore) {
        /** 冻结页内容。 */
        public Page {
            events = List.copyOf(events);
            nextCursor = cursor(nextCursor);
        }
    }

    /**
     * 有序投递水位，不推进客户端已应用位置。
     *
     * @param lastCursor 捕获的公开日志尾
     * @param terminal 是否已观察到 TURN_FINISHED
     * @param finalItemSequence 终态 Item 水位，仅 terminal 时存在
     */
    public record Watermark(String lastCursor, boolean terminal, Optional<Long> finalItemSequence) {
        /** 校验水位。 */
        public Watermark {
            lastCursor = cursor(lastCursor);
            finalItemSequence = Objects.requireNonNull(finalItemSequence, "finalItemSequence");
            if (terminal != finalItemSequence.isPresent()) {
                throw new IllegalArgumentException("invalid terminal watermark");
            }
        }
    }

    /**
     * 单一通知：非空 events 与 watermark 二选一。
     *
     * @param subscriptionId 连接内订阅身份
     * @param events 至多 128 条原始公开事件，水位帧为空
     * @param watermark 排在此前事件之后的控制帧，数据帧为空
     */
    public record Notification(String subscriptionId, List<TurnStreamEvent> events, Optional<Watermark> watermark) {
        /** 校验通知类型和批量边界。 */
        public Notification {
            subscriptionId = identifier(subscriptionId);
            events = List.copyOf(events);
            watermark = Objects.requireNonNull(watermark, "watermark");
            if (events.size() > 128 || events.isEmpty() == watermark.isEmpty()) {
                throw new IllegalArgumentException("notification must contain events or watermark");
            }
        }
    }

    /**
     * 历史窗口查询，不必从第一条扫描到当前页。
     *
     * @param threadId 目标 Thread
     * @param beforeSequence 排他上界，0 表示当前末尾
     * @param limit 页大小，1 至 100
     */
    public record ItemHistoryRequest(com.javaclaw.api.ThreadId threadId, long beforeSequence, int limit) {
        /** 校验查询边界。 */
        public ItemHistoryRequest {
            Objects.requireNonNull(threadId, "threadId");
            if (beforeSequence < 0 || limit < 1 || limit > 100) {
                throw new IllegalArgumentException("invalid Item history page");
            }
        }
    }

    private static String identifier(String value) {
        Objects.requireNonNull(value, "subscriptionId");
        if (value.isBlank() || value.length() > 100) {
            throw new IllegalArgumentException("invalid subscriptionId");
        }
        return value;
    }

    private static String cursor(String value) {
        Objects.requireNonNull(value, "cursor");
        if (value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("invalid stream cursor");
        }
        return value;
    }
}
