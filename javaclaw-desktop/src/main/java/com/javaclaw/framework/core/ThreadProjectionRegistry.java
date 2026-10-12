package com.javaclaw.framework.core;

import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.ThreadProjectionListener;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ThreadProjectionRegistry {
    private final CopyOnWriteArrayList<ThreadProjectionListener> listeners = new CopyOnWriteArrayList<>();
    public AutoCloseable register(ThreadProjectionListener listener) {
        listeners.add(listener); return () -> listeners.remove(listener);
    }
    public boolean project(RunRequest request, ThreadEvent event) {
        boolean delivered = false;
        for (ThreadProjectionListener listener : listeners) {
            if (!listener.accepts(event.scope())) continue;
            listener.project(request, event); delivered = true;
        }
        return delivered;
    }
}
