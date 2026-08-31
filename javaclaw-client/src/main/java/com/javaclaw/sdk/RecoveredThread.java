package com.javaclaw.sdk;

import java.util.List;

import com.javaclaw.sdk.model.EventInfo;
import com.javaclaw.sdk.model.JsonDocument;
import com.javaclaw.sdk.model.ThreadSnapshot;

/**
 * Snapshot, durable replay and active Item state obtained after a reconnect.
 *
 * @param threadId 所属 Thread 标识；有效服务端响应中非空
 * @param snapshot 当前持久 Thread 快照
 * @param events 游标之后的持久事件列表，构造时复制
 * @param liveItems 仍存在于服务端内存的活动 Item 快照；重启后可为空
 */
public record RecoveredThread(
        String threadId, ThreadSnapshot snapshot, List<EventInfo> events, List<JsonDocument> liveItems) {
    /** 复制恢复事件和活动快照集合；调用方仍需按 sequence 去重和投影。 */
    public RecoveredThread {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank");
        }
        events = events == null ? List.of() : List.copyOf(events);
        liveItems = liveItems == null ? List.of() : List.copyOf(liveItems);
    }
}
