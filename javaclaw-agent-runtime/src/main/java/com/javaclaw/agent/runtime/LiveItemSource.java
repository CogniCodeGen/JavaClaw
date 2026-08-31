package com.javaclaw.agent.runtime;

import java.util.List;
import java.util.concurrent.Flow;

import com.javaclaw.core.api.LiveItemEvent;
import com.javaclaw.core.api.ThreadId;

/** Client-neutral live Item projection owned by the Agent Runtime. */
public interface LiveItemSource extends Flow.Publisher<LiveItemEvent> {
    LiveItemSource EMPTY = new LiveItemSource() {
        @Override
        public List<LiveItemEvent> active(ThreadId threadId) {
            return List.of();
        }

        @Override
        public void subscribe(Flow.Subscriber<? super LiveItemEvent> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {}

                @Override
                public void cancel() {}
            });
        }
    };

    /** 返回指定 Thread 当前仍在内存中的活动 Item 快照；已结束或重启后可为空列表。 */
    List<LiveItemEvent> active(ThreadId threadId);
}
