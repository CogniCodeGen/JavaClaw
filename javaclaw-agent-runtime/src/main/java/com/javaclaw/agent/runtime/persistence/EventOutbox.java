package com.javaclaw.agent.runtime.persistence;

import java.util.List;

import com.javaclaw.core.api.ThreadEvent;
import com.javaclaw.core.api.ThreadId;

/** Read/ack port for events atomically written by {@link ThreadJournal}. */
public interface EventOutbox {
    /** 读取最多 limit 条未标记发布的持久事件；不提前删除或确认，以支持崩溃补偿。 */
    List<ThreadEvent> unpublishedEvents(int limit);

    /** 按 Thread/sequence 标记已发布；返回是否更新了待发布记录，允许重复确认。 */
    boolean markEventPublished(ThreadId threadId, long sequence);
}
