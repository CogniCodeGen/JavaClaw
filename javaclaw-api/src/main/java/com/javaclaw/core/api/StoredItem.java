package com.javaclaw.core.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Item 的持久投影；状态、最终内容和排序位置必须一致，支持异常退出后的恢复。
 *
 * @param id 非空 Item 标识
 * @param threadId 所属 Thread 的非空标识
 * @param turnId 所属 Turn 的非空标识
 * @param ordinal Turn 内从 1 开始的排列序号
 * @param state 非空生命周期状态
 * @param kind 非空白原始 Item 类型；FAILED 可携带 ErrorItem 作为最终内容
 * @param item 最终内容；STARTED 必须为 null，终态必须非空
 * @param createdAt 创建时间，非空
 * @param updatedAt 最近更新时间，非空
 */
public record StoredItem(
        ItemId id,
        ThreadId threadId,
        TurnId turnId,
        long ordinal,
        ItemState state,
        String kind,
        ThreadItem item,
        Instant createdAt,
        Instant updatedAt) {
    /** 校验状态与内容的一致性；仅失败状态允许原始 kind 与 ErrorItem 的 kind 不同。 */
    public StoredItem {
        id = Objects.requireNonNull(id, "id");
        threadId = Objects.requireNonNull(threadId, "threadId");
        turnId = Objects.requireNonNull(turnId, "turnId");
        if (ordinal < 1) {
            throw new IllegalArgumentException("ordinal must be positive");
        }
        state = Objects.requireNonNull(state, "state");
        kind = ThreadId.required(kind, "kind");
        if (state == ItemState.STARTED && item != null) {
            throw new IllegalArgumentException("started items cannot contain final content");
        }
        if (state != ItemState.STARTED) {
            item = Objects.requireNonNull(item, "item");
            if (!kind.equals(item.kind()) && !(state == ItemState.FAILED && item instanceof ThreadItem.ErrorItem)) {
                throw new IllegalArgumentException("item kind does not match final content");
            }
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** 从最终 Item 推导 kind；不适用于没有最终内容的 STARTED Item。 */
    public StoredItem(
            ItemId id,
            ThreadId threadId,
            TurnId turnId,
            long ordinal,
            ItemState state,
            ThreadItem item,
            Instant createdAt,
            Instant updatedAt) {
        this(
                id,
                threadId,
                turnId,
                ordinal,
                state,
                Objects.requireNonNull(item, "item").kind(),
                item,
                createdAt,
                updatedAt);
    }
}
