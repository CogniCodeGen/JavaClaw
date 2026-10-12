package com.javaclaw.desktop.service;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded per-subscriber event stream; older progress events yield to newer ones under backpressure. */
final class BoundedPublisher<T> implements Flow.Publisher<T>, AutoCloseable {
    private final CopyOnWriteArrayList<Slot> subscribers = new CopyOnWriteArrayList<>();
    private final Executor executor;
    private final int capacity;
    private volatile boolean closed;

    BoundedPublisher(Executor executor, int capacity) {
        this.executor = Objects.requireNonNull(executor);
        if (capacity < 2) throw new IllegalArgumentException("capacity must be at least two");
        this.capacity = capacity;
    }

    @Override public void subscribe(Flow.Subscriber<? super T> subscriber) {
        Objects.requireNonNull(subscriber);
        Slot slot = new Slot(subscriber);
        if (closed) {
            subscriber.onSubscribe(slot);
            subscriber.onComplete();
            return;
        }
        subscribers.add(slot);
        subscriber.onSubscribe(slot);
        if (closed) slot.complete();
    }

    void submit(T event) {
        Objects.requireNonNull(event);
        if (closed) return;
        for (Slot slot : subscribers) slot.offer(event);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (Slot slot : subscribers) slot.complete();
        subscribers.clear();
    }

    private final class Slot implements Flow.Subscription {
        private final Flow.Subscriber<? super T> subscriber;
        private final ArrayDeque<T> pending = new ArrayDeque<>();
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private long demand;
        private boolean cancelled;

        Slot(Flow.Subscriber<? super T> subscriber) { this.subscriber = subscriber; }

        synchronized void offer(T event) {
            if (cancelled) return;
            if (pending.size() == capacity) pending.removeFirst();
            pending.addLast(event);
            dispatch();
        }

        @Override public synchronized void request(long count) {
            if (count <= 0) {
                cancel();
                subscriber.onError(new IllegalArgumentException("non-positive demand"));
                return;
            }
            demand = demand > Long.MAX_VALUE - count ? Long.MAX_VALUE : demand + count;
            dispatch();
        }

        @Override public synchronized void cancel() {
            cancelled = true;
            pending.clear();
            subscribers.remove(this);
        }

        void complete() {
            synchronized (this) {
                if (cancelled) return;
                cancelled = true;
                pending.clear();
            }
            subscribers.remove(this);
            executor.execute(subscriber::onComplete);
        }

        private void dispatch() {
            if (!cancelled && demand > 0 && !pending.isEmpty()
                    && scheduled.compareAndSet(false, true)) executor.execute(this::drain);
        }

        private void drain() {
            try {
                while (true) {
                    T event;
                    synchronized (this) {
                        if (cancelled || demand == 0 || pending.isEmpty()) return;
                        event = pending.removeFirst();
                        demand--;
                    }
                    try { subscriber.onNext(event); }
                    catch (Throwable failure) {
                        cancel();
                        subscriber.onError(failure);
                        return;
                    }
                }
            } finally {
                scheduled.set(false);
                synchronized (this) { dispatch(); }
            }
        }
    }
}
