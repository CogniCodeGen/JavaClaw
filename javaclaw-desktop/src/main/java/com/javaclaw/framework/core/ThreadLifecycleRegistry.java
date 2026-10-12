package com.javaclaw.framework.core;

import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.ThreadLifecycleListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Workspace listeners are leased only while their workspace runtime is open. */
public final class ThreadLifecycleRegistry {
    private final List<ThreadLifecycleListener> listeners = new CopyOnWriteArrayList<>();
    public AutoCloseable register(ThreadLifecycleListener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
    public void forked(ThreadSnapshot target, List<ThreadEvent> history) {
        listeners.stream().filter(value -> value.accepts(target.scope()))
                .forEach(value -> value.forked(target, history));
    }
    public void deleting(RunScope scope) {
        listeners.stream().filter(value -> value.accepts(scope)).forEach(value -> value.deleting(scope));
    }
}
