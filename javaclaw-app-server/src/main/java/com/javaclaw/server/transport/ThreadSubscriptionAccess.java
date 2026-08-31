package com.javaclaw.server.transport;

import java.util.List;

import com.javaclaw.core.api.LiveItemEvent;
import com.javaclaw.core.api.ThreadEvent;
import com.javaclaw.core.api.ThreadId;

/** Session-owned cursors exposed narrowly to the Thread RPC adapter. */
interface ThreadSubscriptionAccess {
    void register(ThreadId threadId, long sequence);

    List<ThreadEvent> subscribeAndRead(ThreadId threadId, long afterSequence);

    List<LiveItemEvent> activeItems(ThreadId threadId);

    void remove(ThreadId threadId);
}
