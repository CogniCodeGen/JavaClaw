package com.javaclaw.agent.runtime;

import java.util.concurrent.Flow;

import com.javaclaw.core.api.ThreadEvent;

/** Durable-event projection and transient Item delta streams owned by the runtime. */
public interface RuntimeStreams {
    /** 可接收持久事件缺口通知的订阅者；通知与主执行链解耦。 */
    interface ResyncAwareSubscriber extends Flow.Subscriber<ThreadEvent> {
        /** 通知客户端从 afterSequence 之后重新读取 threadId 的持久事件；不得阻塞 Turn 生产者。 */
        void resyncRequired(String threadId, long afterSequence);
    }

    /** 返回实时持久事件投影；订阅之前的事件和背压缺口需通过 Journal 查询恢复。 */
    Flow.Publisher<ThreadEvent> events();

    /** 返回非持久 delta 流和活动快照，不作为永久 transcript 使用。 */
    LiveItemSource liveItems();
}
