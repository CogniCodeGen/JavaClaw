package com.javaclaw.sdk.model;

import java.util.List;

/**
 * 重连或订阅恢复结果：持久快照、游标后事件与仍在内存的活动 Item。
 *
 * @param snapshot 当前持久 Thread 快照
 * @param events 游标之后的持久事件列表，构造时复制
 * @param liveItems 仍存在于服务端内存的活动 Item 快照；重启后可为空
 */
public record ThreadResumeInfo(ThreadSnapshot snapshot, List<EventInfo> events, List<JsonDocument> liveItems) {
    /** 复制恢复事件和活动快照集合；调用方仍需按 sequence 去重和投影。 */
    public ThreadResumeInfo {
        events = events == null ? List.of() : List.copyOf(events);
        liveItems = liveItems == null ? List.of() : List.copyOf(liveItems);
    }
}
